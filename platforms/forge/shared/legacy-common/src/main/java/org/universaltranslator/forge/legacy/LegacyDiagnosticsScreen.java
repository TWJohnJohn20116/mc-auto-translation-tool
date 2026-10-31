package org.universaltranslator.forge.legacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.DiagnosticsBundleExporter;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;
import org.universaltranslator.core.TranslationStats;
import org.universaltranslator.core.UiTextKeys;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Secret-free runtime diagnostics shared by Forge 1.8.9 and 1.12.2. */
final class LegacyDiagnosticsScreen extends GuiScreen {
    /** Vertical distance between two text lines. */
    private static final int LINE_STEP = 17;
    /** First line's y, just under the title. */
    private static final int LINE_START = 43;
    /**
     * How long a built page may be reused: {@code drawScreen} runs every frame and rebuilding the
     * page localizes several lines and re-sorts the 512 latency samples.
     */
    private static final long LINE_REFRESH_MILLIS = 250L;
    /**
     * The one background thread that writes diagnostic archives.
     *
     * <p>The export used to run inside the button handler: read the debug log line by line, redact
     * each line with a regex, copy it into a {@code StringBuilder} and deflate the result into a zip,
     * all on the client thread. On a multi-megabyte log that froze the window for hundreds of
     * milliseconds to seconds. A daemon thread keeps it off the tick, and a single thread keeps two
     * presses from writing the same archive twice.
     */
    private static final ExecutorService EXPORTER = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable task) {
                    Thread thread = new Thread(task, "universal-translator-diagnostics-export");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private static final int BACK = 1;
    private static final int EXPORT = 2;
    private static final int DEBUG = 5;
    private static final int BUNDLE = 6;
    private static final int PAGE = 7;
    private static final int RESET = 8;

    private final GuiScreen parent;
    private FontRenderer renderer;
    private GuiButton debugButton;
    private GuiButton pageButton;
    private GuiButton resetButton;
    private GuiButton exportButton;
    private GuiButton bundleButton;
    /** Whether the counters page is shown instead of the engine diagnostics page. */
    private boolean statisticsPage;
    private boolean debugEnabled;
    /** Rendered page contents, or {@code null} when the next frame must rebuild them. */
    private List<String> cachedLines;
    /** {@link System#currentTimeMillis()} when {@link #cachedLines} was built. */
    private long cachedLinesAt;
    /** Written from the export thread, read by the render thread. */
    private volatile String exportStatus = "";
    private volatile boolean exportFailed;
    /** True while an archive is being written; the buttons are flat and further presses ignored. */
    private volatile boolean exporting;

    LegacyDiagnosticsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        renderer = LegacyVersionAccess.fontRenderer();
        LegacyConfig config = LegacyTranslationRuntime.diagnosticsConfig();
        if (config != null) {
            // Re-read here rather than in the constructor so a rebuild after a resize, or a change
            // made by another screen, is reflected instead of showing a stale value.
            debugEnabled = config.debugLog();
        }
        // Entering the screen, or rebuilding it after a resize, is the natural point to recompute.
        invalidateLines();
        int totalWidth = Math.max(180, Math.min(320, width - 24));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (width - totalWidth) / 2;

        debugButton = new GuiButton(DEBUG, left, height - 76, buttonWidth, 20, "");
        buttonList.add(debugButton);
        bundleButton = new GuiButton(BUNDLE, left + buttonWidth + gap, height - 76,
                buttonWidth, 20, tr("screen.universal_translator.debug.bundle"));
        buttonList.add(bundleButton);

        pageButton = new GuiButton(PAGE, left, height - 52, buttonWidth, 20, "");
        buttonList.add(pageButton);
        resetButton = new GuiButton(RESET, left + buttonWidth + gap, height - 52,
                buttonWidth, 20, tr("screen.universal_translator.stats.reset"));
        buttonList.add(resetButton);

        buttonList.add(new GuiButton(BACK, left, height - 28,
                buttonWidth, 20, tr("screen.universal_translator.diagnostics.back")));
        exportButton = new GuiButton(EXPORT, left + buttonWidth + gap, height - 28,
                buttonWidth, 20, tr("screen.universal_translator.diagnostics.export"));
        buttonList.add(exportButton);
        refreshDebugLabel();
        refreshPageLabels();
        refreshExportButtons();
    }

    /** Drops the built page so the next frame rebuilds it once. */
    private void invalidateLines() {
        cachedLines = null;
        cachedLinesAt = 0L;
    }

    /** True while an archive is being written; both archive buttons go flat so one cannot queue twice. */
    private void refreshExportButtons() {
        boolean idle = !exporting;
        if (bundleButton != null) {
            bundleButton.enabled = idle;
        }
        if (exportButton != null) {
            exportButton.enabled = idle;
        }
    }

    private void refreshDebugLabel() {
        if (debugButton == null) {
            return;
        }
        debugButton.displayString = tr("screen.universal_translator.debug.mode",
                tr(UiTextKeys.onOff(debugEnabled)));
    }

    private void refreshPageLabels() {
        if (pageButton != null) {
            pageButton.displayString = tr(statisticsPage
                    ? "screen.universal_translator.stats.show_diagnostics"
                    : "screen.universal_translator.stats.show");
        }
        if (resetButton != null) {
            // Only the counters page can be cleared, so the button says so by going flat.
            resetButton.enabled = statisticsPage;
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == BACK) {
            mc.displayGuiScreen(parent);
        } else if (button.id == EXPORT) {
            exportLog();
        } else if (button.id == DEBUG) {
            debugEnabled = !debugEnabled;
            applyDebugMode();
        } else if (button.id == BUNDLE) {
            exportBundle();
        } else if (button.id == PAGE) {
            statisticsPage = !statisticsPage;
            invalidateLines();
            refreshPageLabels();
        } else if (button.id == RESET) {
            resetStatistics();
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
        LegacyConfig config = LegacyTranslationRuntime.diagnosticsConfig();
        if (config == null) {
            exportStatus = tr("screen.universal_translator.diagnostics.settings_unavailable");
            exportFailed = true;
            return;
        }
        try {
            LegacyConfig updated = config.withDebugLog(debugEnabled);
            LegacyTranslationRuntime.initialize(updated);
            LegacyConfigWriter.saveNow(updated);
            Path configFile = updated.configFile();
            DebugLog.global().configure(
                    configFile.resolveSibling(DebugLog.FILE_NAME), debugEnabled);
            exportStatus = tr("screen.universal_translator.debug.changed",
                    tr(UiTextKeys.onOff(debugEnabled)));
            exportFailed = false;
            invalidateLines();
        } catch (Exception failure) {
            exportStatus = settingsFailure(failure);
            exportFailed = true;
        }
    }

    /** Clears the process-wide counters and writes the cleared values out. */
    private void resetStatistics() {
        TranslationStats.global().reset();
        exportStatus = tr("screen.universal_translator.stats.reset_done");
        exportFailed = false;
        invalidateLines();
    }

    private String settingsFailure(Exception failure) {
        String message = failure.getMessage();
        return tr("screen.universal_translator.diagnostics.settings_failed",
                message == null || message.trim().isEmpty()
                        ? failure.getClass().getSimpleName() : message);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawRect(Math.max(5, width / 2 - 190), 8,
                Math.min(width - 5, width / 2 + 190), height - 34, 0xD51A232E);
        drawCenteredString(renderer,
                tr(statisticsPage
                        ? "screen.universal_translator.stats.title"
                        : "screen.universal_translator.diagnostics.title"),
                width / 2, 18, 0xFFFFFFFF);
        List<String> lines = displayLines();
        int left = Math.max(10, (width - Math.min(360, width - 20)) / 2);
        // Stop before the status line and the button rows. A short window shows fewer lines instead
        // of drawing text on top of a button, and the last visible line says that it was cut.
        int limit = Math.max(1, (height - 96 - LINE_START) / LINE_STEP + 1);
        int shown = Math.min(lines.size(), limit);
        int y = LINE_START;
        for (int index = 0; index < shown; index++) {
            String line = lines.get(index);
            if (index == shown - 1 && shown < lines.size()) {
                line = line + " ...";
            }
            drawString(renderer, line, left, y, 0xFFD0D0D0);
            y += LINE_STEP;
        }
        drawCenteredString(renderer,
                tr(statisticsPage
                        ? "screen.universal_translator.stats.note"
                        : "screen.universal_translator.diagnostics.note"),
                width / 2, Math.min(y + 7, height - 88), 0xFF909090);
        if (!exportStatus.isEmpty()) {
            drawCenteredString(renderer, exportStatus, width / 2, height - 82,
                    exportFailed ? 0xFFFF5555 : 0xFF55FF88);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** The lines the current page shows, served from {@link #cachedLines} while it is still fresh. */
    private List<String> displayLines() {
        long now = System.currentTimeMillis();
        List<String> cached = cachedLines;
        if (cached == null || now - cachedLinesAt >= LINE_REFRESH_MILLIS) {
            cached = computeLines();
            cachedLines = cached;
            cachedLinesAt = now;
        }
        return cached;
    }

    /** Rebuilds the page, which is the expensive part: several localized lines plus the counters. */
    private List<String> computeLines() {
        try {
            return statisticsPage
                    ? TranslationStats.global().localizedLines(
                            LegacyDiagnosticsScreen::tr)
                    : diagnosticsLines();
        } catch (RuntimeException ignored) {
            return Collections.singletonList(
                    tr("screen.universal_translator.diagnostics.unavailable"));
        }
    }

    /** The engine diagnostics lines, which are also what the exported report contains. */
    private List<String> diagnosticsLines() {
        // Before the first configuration load there is nothing to report, and the runtime's empty
        // status would be localized as "waiting for the first translation". Say what is true.
        if (LegacyTranslationRuntime.diagnosticsConfig() == null) {
            return Collections.singletonList(
                    tr("screen.universal_translator.diagnostics.settings_unavailable"));
        }
        try {
            TranslationDiagnosticsSnapshot snapshot = LegacyTranslationRuntime.diagnostics();
            return snapshot == null
                    ? Collections.singletonList(
                            tr("screen.universal_translator.diagnostics.unavailable"))
                    : snapshot.localizedLines(LegacyDiagnosticsScreen::tr);
        } catch (RuntimeException ignored) {
            return Collections.singletonList(
                    tr("screen.universal_translator.diagnostics.unavailable"));
        }
    }

    private void exportLog() {
        // Built on this thread, written on the exporter thread.
        final List<String> lines = diagnosticsLines();
        startExport("screen.universal_translator.diagnostics.export",
                "screen.universal_translator.diagnostics.exported", null, () -> {
                    LegacyTranslationRuntime.exportDiagnostics(lines);
                    return null;
                });
    }

    /**
     * Writes one archive holding the diagnostics, the debug log, the redacted configuration and the
     * environment into the configuration directory.
     */
    private void exportBundle() {
        LegacyConfig config = LegacyTranslationRuntime.diagnosticsConfig();
        if (config == null) {
            exportStatus = tr("screen.universal_translator.diagnostics.settings_unavailable");
            exportFailed = true;
            return;
        }
        final Path configFile = config.configFile();
        final List<String> lines = diagnosticsLines();
        startExport("screen.universal_translator.debug.bundle",
                "screen.universal_translator.debug.bundle_exported", "", () -> DiagnosticsBundleExporter.export(
                        configFile.getParent(), configFile,
                        configFile.resolveSibling(DebugLog.FILE_NAME), "forge", lines));
    }

    /**
     * Runs one export on {@link #EXPORTER} and reports the outcome through the client thread.
     *
     * <p>The handler returns immediately; the status line names the running export and both archive
     * buttons go flat until the worker answers. Nothing waits for the result on the render path.
     *
     * @param busyKey       key naming the export while it runs; an ellipsis is appended
     * @param successKey    key shown when the job returns
     * @param successExtras named argument for {@code successKey}, or {@code null} when it takes none
     * @param job           performs the I/O on the exporter thread
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

    /** Publishes one export result through the client thread and re-enables the buttons. */
    private void finishExport(final String successKey, final String successExtras,
                              final Path written, final boolean failed) {
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
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            apply.run();
        } else {
            minecraft.addScheduledTask(apply);
        }
    }

    /** One export job, run on the exporter thread and never on the client thread. */
    private interface ExportJob {
        Path run() throws Exception;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return I18n.format(key, arguments);
    }
}
