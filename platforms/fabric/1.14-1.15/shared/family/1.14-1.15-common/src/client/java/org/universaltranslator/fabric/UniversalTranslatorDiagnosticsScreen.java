package org.universaltranslator.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.text.LiteralText;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.DiagnosticsBundleExporter;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;
import org.universaltranslator.core.TranslationStats;
import org.universaltranslator.core.UiTextKeys;

import java.nio.file.Path;
import java.util.List;
import java.util.Collections;

/** Secret-free runtime diagnostics that update while the screen is open. */
final class UniversalTranslatorDiagnosticsScreen extends Screen {
    /** Vertical distance between two text lines. */
    private static final int LINE_STEP = 17;
    /** First line's y, just under the title. */
    private static final int LINE_START = 43;

    private final Screen parent;
    private ButtonWidget debugButton;
    private ButtonWidget pageButton;
    private ButtonWidget resetButton;
    /** Whether the counters page is shown instead of the engine diagnostics page. */
    private boolean statisticsPage;
    private boolean debugEnabled;
    private String exportStatus = "";
    private boolean exportFailed;
    /**
     * The rendered page contents, or {@code null} when they must be rebuilt.
     *
     * <p>{@link #render} runs at the frame rate and {@code displayLines()} used to rebuild every line
     * on each call: two {@code Files.size} calls on the render thread plus 8–10 {@code I18n} lookups
     * per frame, at 60 fps. Caching makes that cost per invalidation instead of per frame.
     */
    private List<String> cachedLines;
    /** {@link System#currentTimeMillis()} when {@link #cachedLines} was built. */
    private long cachedLinesAt;
    /** Upper bound on how long a cached page may stay stale while the screen stays open. */
    private static final long LINE_REFRESH_MILLIS = 500L;
    /** Filled in by the export worker; published on the client thread by {@link #render}. */
    private volatile boolean pendingExportDone;
    private volatile String pendingExportArchive;

    UniversalTranslatorDiagnosticsScreen(Screen parent) {
        super(new TranslatableText("screen.universal_translator.diagnostics.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        FabricConfig config = FabricTranslationRuntime.diagnosticsConfig();
        if (config != null) {
            // Re-read here rather than in the constructor so a rebuild after a resize, or a change
            // made by another screen, is reflected instead of showing a stale value.
            debugEnabled = config.debugLog();
        }
        // Entering the screen (or rebuilding it after a resize) is the natural point to recompute.
        invalidateLines();
        int totalWidth = Math.max(180, Math.min(320, this.width - 24));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (this.width - totalWidth) / 2;

        this.debugButton = addLegacyButton(ButtonWidget.builder(new LiteralText(""), button -> {
            debugEnabled = !debugEnabled;
            applyDebugMode();
            invalidateLines();
        }).dimensions(left, this.height - 76, buttonWidth, 20).build());
        addLegacyButton(ButtonWidget.builder(
                new TranslatableText("screen.universal_translator.debug.bundle"),
                button -> exportBundle())
                .dimensions(left + buttonWidth + gap, this.height - 76, buttonWidth, 20).build());

        this.pageButton = addLegacyButton(ButtonWidget.builder(new LiteralText(""), button -> {
            statisticsPage = !statisticsPage;
            refreshPageLabels();
            invalidateLines();
        }).dimensions(left, this.height - 52, buttonWidth, 20).build());
        this.resetButton = addLegacyButton(ButtonWidget.builder(
                new TranslatableText("screen.universal_translator.stats.reset"),
                button -> {
                    resetStatistics();
                    invalidateLines();
                })
                .dimensions(left + buttonWidth + gap, this.height - 52, buttonWidth, 20).build());

        addLegacyButton(ButtonWidget.builder(
                new TranslatableText("screen.universal_translator.diagnostics.back"), button -> onClose())
                .dimensions(left, this.height - 28, buttonWidth, 20).build());
        addLegacyButton(ButtonWidget.builder(
                new TranslatableText("screen.universal_translator.diagnostics.export"), button -> exportLog())
                .dimensions(left + buttonWidth + gap, this.height - 28, buttonWidth, 20).build());
        refreshDebugLabel();
        refreshPageLabels();
    }

    private <T extends net.minecraft.client.gui.widget.AbstractButtonWidget> T addLegacyButton(T child) {
        return addButton(child);
    }

    private void refreshDebugLabel() {
        if (debugButton == null) {
            return;
        }
        debugButton.setMessage(new TranslatableText("screen.universal_translator.debug.mode",
                tr(UiTextKeys.onOff(debugEnabled))));
    }

    private void refreshPageLabels() {
        if (pageButton != null) {
            pageButton.setMessage(new TranslatableText(statisticsPage
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
        FabricConfig config = FabricTranslationRuntime.diagnosticsConfig();
        if (config == null) {
            exportStatus = tr("screen.universal_translator.diagnostics.settings_unavailable");
            exportFailed = true;
            return;
        }
        try {
            FabricConfig updated = config.withDebugLog(debugEnabled);
            FabricTranslationRuntime.initialize(updated);
            updated.save();
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

    /** Clears the in-memory counters; the on-disk totals are only rewritten at shutdown. */
    private void resetStatistics() {
        TranslationStats.global().reset();
        exportStatus = tr("screen.universal_translator.stats.reset_done");
        exportFailed = false;
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        applyPendingExport();
        fill(0, 0, width, height, 0xE510151C);
        fill(Math.max(5, width / 2 - 190), 8,
                Math.min(width - 5, width / 2 + 190), height - 34, 0xD51A232E);
        drawCenteredString(font,
                statisticsPage
                        ? new TranslatableText("screen.universal_translator.stats.title").getString()
                        : title.getString(),
                width / 2, 18, 0xFFFFFFFF);
        List<String> lines = displayLines();
        int left = Math.max(10, (width - Math.min(360, width - 20)) / 2);
        // Stop before the status line and the button rows. A short window shows fewer lines instead
        // of drawing text on top of a button, and the last visible line says that it was cut.
        int limit = Math.max(1, (this.height - 96 - LINE_START) / LINE_STEP + 1);
        int shown = Math.min(lines.size(), limit);
        int y = LINE_START;
        for (int index = 0; index < shown; index++) {
            String line = lines.get(index);
            if (index == shown - 1 && shown < lines.size()) {
                line = line + " ...";
            }
            drawString(font, new LiteralText(line).getString(), left, y, 0xFFD0D0D0);
            y += LINE_STEP;
        }
        drawCenteredString(font,
                new TranslatableText(statisticsPage
                        ? "screen.universal_translator.stats.note"
                        : "screen.universal_translator.diagnostics.note").getString(),
                width / 2, Math.min(y + 7, height - 88), 0xFF909090);
        if (!exportStatus.isEmpty()) {
            // Above the first button row (height-76): drawn at height-82 the row's background and
            // hover highlight covered the bottom two pixels, cutting the descenders off the only
            // feedback the export gives.
            drawCenteredString(font, new LiteralText(exportStatus).getString(),
                    width / 2, height - 86, exportFailed ? 0xFFFF5555 : 0xFF55FF88);
        }
        super.render(mouseX, mouseY, delta);
    }

    /** The lines the current page shows, served from {@link #cachedLines} when it is still fresh. */
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

    /** Drops the cache so the next {@link #render} rebuilds the page once. */
    private void invalidateLines() {
        cachedLines = null;
        cachedLinesAt = 0L;
    }

    /**
     * Rebuilds the page contents, which is the expensive part: it localizes 4-10 lines and, on the
     * diagnostics page, goes through {@code FabricTranslationRuntime.diagnostics()} (file sizes).
     */
    private List<String> computeLines() {
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
            TranslationDiagnosticsSnapshot snapshot = FabricTranslationRuntime.diagnostics();
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
        try {
            FabricTranslationRuntime.exportDiagnostics(diagnosticsLines());
            exportStatus = tr("screen.universal_translator.diagnostics.exported");
            exportFailed = false;
        } catch (Exception ignored) {
            exportStatus = tr("screen.universal_translator.diagnostics.export_failed");
            exportFailed = true;
        }
    }

    /**
     * Writes one archive holding the diagnostics, the debug log, the redacted configuration and the
     * environment into the configuration directory.
     *
     * <p>On a background thread: the exporter reads and redacts the whole debug log (up to
     * {@code DebugLog.MAXIMUM_BYTES}) and deflates it, which froze the client for seconds on a
     * spinning disk or an antivirus-watched folder. The result is published by
     * {@link #applyPendingExport()} on the client thread.
     */
    private void exportBundle() {
        FabricConfig config = FabricTranslationRuntime.diagnosticsConfig();
        if (config == null) {
            exportStatus = tr("screen.universal_translator.diagnostics.settings_unavailable");
            exportFailed = true;
            return;
        }
        final Path configFile = config.configFile();
        final List<String> lines = diagnosticsLines();
        Thread worker = new Thread(() -> {
            String archiveName;
            try {
                Path archive = DiagnosticsBundleExporter.export(
                        configFile.getParent(), configFile,
                        configFile.resolveSibling(DebugLog.FILE_NAME), "fabric", lines);
                archiveName = archive.getFileName().toString();
            } catch (Exception failure) {
                archiveName = null;
            }
            // Published in this order: the client thread only reads the name after the flag is set.
            pendingExportArchive = archiveName;
            pendingExportDone = true;
        }, "universal-translator-diagnostics-export");
        worker.setDaemon(true);
        worker.start();
    }

    /** Publishes the export worker's result on the client thread, where the label can be localized. */
    private void applyPendingExport() {
        if (!pendingExportDone) {
            return;
        }
        pendingExportDone = false;
        String archiveName = pendingExportArchive;
        pendingExportArchive = null;
        if (archiveName == null) {
            exportStatus = tr("screen.universal_translator.diagnostics.export_failed");
            exportFailed = true;
            return;
        }
        exportStatus = tr("screen.universal_translator.debug.bundle_exported", archiveName);
        exportFailed = false;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.openScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return new TranslatableText(key, arguments).getString();
    }
}
