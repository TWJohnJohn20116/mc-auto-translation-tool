package org.universaltranslator.forge;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.DiagnosticsBundleExporter;
import org.universaltranslator.core.DiagnosticsLogExporter;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;
import org.universaltranslator.core.TranslationStats;
import org.universaltranslator.core.UiTextKeys;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Secret-free runtime diagnostics that update while the screen is open. */
final class UniversalTranslatorDiagnosticsScreen extends Screen {
    private static final String EXPORT_THREAD_NAME = "universal-translator-diagnostics-export";
    /**
     * Serializes archive exports away from the render thread.
     *
     * <p>Both buttons used to run the whole I/O chain inline: the debug log is read line by line,
     * redacted with a regex per line, copied into a {@code StringBuilder} and deflated into a zip,
     * all inside the click handler — hundreds of milliseconds to seconds of frozen window on a
     * multi-megabyte log, with every tick stopped. One daemon thread keeps the cost off the frame
     * budget and keeps two presses from writing the same archive twice.
     */
    private static final ExecutorService EXPORTER =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, EXPORT_THREAD_NAME);
                thread.setDaemon(true);
                return thread;
            });

    static {
        // The screen has no lifecycle of its own, so class load is the only place a game exit can be
        // observed from here: without this the export thread was still parked on its queue, together
        // with its unbounded task queue, after the client was gone.
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                EXPORTER.shutdownNow();
            }
        }, "universal-translator-diagnostics-export-shutdown"));
    }
    /** Vertical distance between two text lines. */
    private static final int LINE_STEP = 17;
    /** First line's y, just under the title. */
    private static final int LINE_START = 43;
    /**
     * How long a rebuilt page of lines is reused.
     *
     * <p>Same window the shared runtime uses for its diagnostics snapshot: a reader cannot notice
     * 250 ms, and one frame is far below it. Without it the counters page ran a full
     * {@code TranslationStats} snapshot (boxing 512 latency samples, sorting them for the 95th
     * percentile, rebuilding every provider row) once per drawn frame while the screen stood still.
     */
    private static final long LINES_CACHE_MILLIS = 250L;

    private final Screen parent;
    private final ForgeConfig config;
    private Button debugButton;
    private Button pageButton;
    private Button resetButton;
    private Button exportButton;
    private Button bundleButton;
    /** Whether the counters page is shown instead of the engine diagnostics page. */
    private boolean statisticsPage;
    private boolean debugEnabled;
    /** Written from the export thread, read by the render thread. */
    private volatile String exportStatus = "";
    private volatile boolean exportFailed;
    /** True while an archive is being written; the buttons are flat and further presses ignored. */
    private volatile boolean exporting;
    /** The current page's lines, rebuilt at most every {@link #LINES_CACHE_MILLIS}. */
    private List<Component> cachedLines;
    private long cachedLinesAt;
    private boolean cachedLinesAreStatistics;

    UniversalTranslatorDiagnosticsScreen(Screen parent, ForgeConfig config) {
        super(Component.translatable("screen.universal_translator.diagnostics.title"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        ForgeConfig live = liveConfig();
        if (live != null) {
            // Re-read here rather than in the constructor so a rebuild after a resize, or a change
            // made by another screen, is reflected instead of showing a stale value.
            debugEnabled = live.debugLog();
        }
        int totalWidth = Math.max(180, Math.min(320, this.width - 24));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (this.width - totalWidth) / 2;

        this.debugButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            debugEnabled = !debugEnabled;
            applyDebugMode();
        }).bounds(left, this.height - 76, buttonWidth, 20).build());
        this.bundleButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.universal_translator.debug.bundle"),
                button -> exportBundle())
                .bounds(left + buttonWidth + gap, this.height - 76, buttonWidth, 20).build());

        this.pageButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            statisticsPage = !statisticsPage;
            // The page just changed, so the cached page of lines cannot be reused.
            cachedLines = null;
            refreshPageLabels();
        }).bounds(left, this.height - 52, buttonWidth, 20).build());
        this.resetButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.universal_translator.stats.reset"),
                button -> resetStatistics())
                .bounds(left + buttonWidth + gap, this.height - 52, buttonWidth, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("screen.universal_translator.diagnostics.back"), button -> onClose())
                .bounds(left, this.height - 28, buttonWidth, 20).build());
        this.exportButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.universal_translator.diagnostics.export"), button -> exportLog())
                .bounds(left + buttonWidth + gap, this.height - 28, buttonWidth, 20).build());
        refreshDebugLabel();
        refreshPageLabels();
        refreshExportButtons();
    }

    /** Flattens both archive buttons while a write is in flight. */
    private void refreshExportButtons() {
        boolean idle = !exporting;
        if (exportButton != null) {
            exportButton.active = idle;
        }
        if (bundleButton != null) {
            bundleButton.active = idle;
        }
    }

    /**
     * The configuration the controls read and write.
     *
     * <p>The runtime's copy is preferred so a screen rebuilt after a resize, or a change made by
     * another screen, is picked up; the configuration handed to the constructor is the fallback for
     * the window before the first load.
     */
    private ForgeConfig liveConfig() {
        ForgeConfig live = ForgeTranslationRuntime.diagnosticsConfig();
        return live == null ? config : live;
    }

    private void refreshDebugLabel() {
        if (debugButton == null) {
            return;
        }
        debugButton.setMessage(Component.translatable("screen.universal_translator.debug.mode",
                tr(UiTextKeys.onOff(debugEnabled))));
    }

    private void refreshPageLabels() {
        if (pageButton != null) {
            pageButton.setMessage(Component.translatable(statisticsPage
                    ? "screen.universal_translator.stats.show_diagnostics"
                    : "screen.universal_translator.stats.show"));
        }
        if (resetButton != null) {
            // Only the counters page can be cleared, so the button says so by going flat.
            resetButton.active = statisticsPage;
        }
    }

    /**
     * Switches the request trace on or off and persists the switch.
     *
     * <p>{@link DebugLog} is reconfigured directly because the switch takes effect immediately; the
     * configuration write only makes it survive the next launch.
     */
    private void applyDebugMode() {
        refreshDebugLabel();
        ForgeConfig live = liveConfig();
        if (live == null) {
            exportStatus = tr("screen.universal_translator.diagnostics.settings_unavailable");
            exportFailed = true;
            return;
        }
        try {
            ForgeConfig updated = live.withDebugLog(debugEnabled);
            ForgeTranslationRuntime.initialize(updated);
            ForgeConfigWriter.saveNow(updated);
            Path configFile = updated.configFile();
            DebugLog.global().configure(
                    configFile.resolveSibling(DebugLog.FILE_NAME), debugEnabled);
            exportStatus = tr("screen.universal_translator.debug.changed",
                    tr(UiTextKeys.onOff(debugEnabled)));
            exportFailed = false;
        } catch (Exception failure) {
            String message = failure.getMessage();
            exportStatus = tr("screen.universal_translator.diagnostics.settings_failed",
                    message == null || message.trim().isEmpty()
                            ? failure.getClass().getSimpleName() : message);
            exportFailed = true;
        }
    }

    /** Clears the process-wide counters and writes the cleared values out. */
    private void resetStatistics() {
        TranslationStats.global().reset();
        // Show the cleared counters immediately instead of the pre-reset page for one cache window.
        cachedLines = null;
        exportStatus = tr("screen.universal_translator.stats.reset_done");
        exportFailed = false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xE510151C);
        graphics.fill(Math.max(5, width / 2 - 190), 8,
                Math.min(width - 5, width / 2 + 190), height - 34, 0xD51A232E);
        graphics.centeredText(this.font,
                statisticsPage ? Component.translatable("screen.universal_translator.stats.title") : this.title,
                this.width / 2, 18, 0xFFFFFFFF);
        List<Component> lines = displayLineComponents();
        int left = Math.max(10, (this.width - Math.min(360, this.width - 20)) / 2);
        // Stop before the status line and the button rows. A short window shows fewer lines instead
        // of drawing text on top of a button, and the last visible line says that it was cut.
        int limit = Math.max(1, (this.height - 96 - LINE_START) / LINE_STEP + 1);
        int shown = Math.min(lines.size(), limit);
        int y = LINE_START;
        for (int index = 0; index < shown; index++) {
            Component line = lines.get(index);
            if (index == shown - 1 && shown < lines.size()) {
                // Only the cut line is rebuilt; every other line is the cached component.
                line = Component.literal(line.getString() + " ...");
            }
            graphics.text(this.font, line, left, y, 0xFFD0D0D0);
            y += LINE_STEP;
        }
        graphics.centeredText(this.font,
                Component.translatable(statisticsPage
                        ? "screen.universal_translator.stats.note"
                        : "screen.universal_translator.diagnostics.note"),
                this.width / 2, Math.min(y + 7, this.height - 88), 0xFF909090);
        if (!exportStatus.isEmpty()) {
            graphics.centeredText(this.font, Component.literal(exportStatus),
                    this.width / 2, this.height - 82, exportFailed ? 0xFFFF5555 : 0xFF55FF88);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    /**
     * The current page's lines as drawable components, rebuilt at most every
     * {@link #LINES_CACHE_MILLIS}.
     *
     * <p>Both pages were rebuilt on every frame, and each lookup builds a line of text, a
     * {@code Component} and a font measurement; the counters page on top of that takes a whole
     * statistics snapshot. Reusing the components keeps the screen still cheap when nothing changed.
     */
    private List<Component> displayLineComponents() {
        long now = System.currentTimeMillis();
        List<Component> cached = cachedLines;
        if (cached != null && cachedLinesAreStatistics == statisticsPage
                && now - cachedLinesAt < LINES_CACHE_MILLIS) {
            return cached;
        }
        List<String> strings = displayLines();
        List<Component> components = new ArrayList<Component>(strings.size());
        for (String line : strings) {
            components.add(Component.literal(line));
        }
        cachedLines = components;
        cachedLinesAreStatistics = statisticsPage;
        cachedLinesAt = now;
        return components;
    }

    /** The lines the current page shows. */
    private List<String> displayLines() {
        try {
            return statisticsPage
                    ? TranslationStats.global().localizedLines(
                            UniversalTranslatorDiagnosticsScreen::tr)
                    : diagnosticsLines();
        } catch (RuntimeException ignored) {
            return Collections.singletonList(
                    tr("screen.universal_translator.diagnostics.unavailable"));
        }
    }

    /** The engine diagnostics lines, which are also what the exported report contains. */
    private List<String> diagnosticsLines() {
        try {
            TranslationDiagnosticsSnapshot snapshot = ForgeTranslationRuntime.diagnostics();
            return snapshot == null
                    ? Collections.singletonList(
                            tr("screen.universal_translator.diagnostics.unavailable"))
                    : snapshot.localizedLines(UniversalTranslatorDiagnosticsScreen::tr);
        } catch (RuntimeException ignored) {
            return Collections.singletonList(
                    tr("screen.universal_translator.diagnostics.unavailable"));
        }
    }

    private void exportLog() {
        final Path directory = config.offlineDirectory.getParent()
                .resolve("universal-translator-diagnostics");
        // The page is already built and cached by the render thread; the worker only writes it out.
        final List<String> lines = diagnosticsLines();
        startExport("screen.universal_translator.diagnostics.export",
                "screen.universal_translator.diagnostics.exported", null,
                () -> DiagnosticsLogExporter.export(directory, lines));
    }

    /**
     * Writes one archive holding the diagnostics, the debug log, the redacted configuration and the
     * environment into the configuration directory.
     */
    private void exportBundle() {
        ForgeConfig live = liveConfig();
        if (live == null) {
            exportStatus = tr("screen.universal_translator.diagnostics.settings_unavailable");
            exportFailed = true;
            return;
        }
        final Path configFile = live.configFile();
        // Snapshot the page here: the worker thread must never walk this screen's state.
        final List<String> lines = diagnosticsLines();
        startExport("screen.universal_translator.debug.bundle",
                "screen.universal_translator.debug.bundle_exported", "", () -> {
                    Path archive = DiagnosticsBundleExporter.export(
                            configFile.getParent(), configFile,
                            configFile.resolveSibling(DebugLog.FILE_NAME), "forge", lines);
                    return archive;
                });
    }

    /**
     * Runs one export on {@link #EXPORTER} and reports the outcome back on the client thread.
     *
     * <p>The click handler returns immediately: the status line names the export that is running and
     * both archive buttons go flat until the worker reports. No {@code CompletableFuture} is involved
     * on purpose — any join or {@code get()} on the render path would reintroduce the freeze.
     */
    private void startExport(String busyKey, String successKey, String successExtras, ExportJob job) {
        if (exporting) {
            return;
        }
        exporting = true;
        exportFailed = false;
        exportStatus = tr(busyKey) + "\u2026";
        refreshExportButtons();
        EXPORTER.execute(() -> {
            Path written = null;
            boolean failed;
            try {
                written = job.run();
                failed = false;
            } catch (Exception ignored) {
                failed = true;
            }
            finishExport(successKey, successExtras, written, failed);
        });
    }

    /** Publishes one export result on the client thread and re-enables the buttons. */
    private void finishExport(String successKey, String successExtras, Path written, boolean failed) {
        Runnable apply = () -> {
            exporting = false;
            exportFailed = failed;
            if (failed) {
                exportStatus = tr("screen.universal_translator.diagnostics.export_failed");
            } else if (successExtras == null) {
                exportStatus = tr(successKey);
            } else {
                exportStatus = tr(successKey, written == null
                        ? successExtras : written.getFileName().toString());
            }
            refreshExportButtons();
        };
        if (minecraft == null) {
            apply.run();
        } else {
            minecraft.execute(apply);
        }
    }

    /** One export job, run on the exporter thread and never on the client thread. */
    private interface ExportJob {
        Path run() throws Exception;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return Component.translatable(key, arguments).getString();
    }
}
