package org.universaltranslator.fabric;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.resource.language.I18n;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.DiagnosticsBundleExporter;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;
import org.universaltranslator.core.TranslationStats;
import org.universaltranslator.core.UiTextKeys;

import java.nio.file.Path;
import java.util.List;
import java.util.Collections;

/**
 * Secret-free runtime diagnostics for the 1.13 Fabric builds.
 *
 * <p>It used to claim it was shared with Forge 1.8.9 and 1.12.2. It is not: those build from
 * {@code platforms/forge/shared/legacy-common} and their own {@code Legacy*Screen} classes, so the
 * comment sent readers to the wrong file (audit S2-095).
 */
final class UniversalTranslatorDiagnosticsScreen extends UniversalTranslatorScreenBase {
    /** Vertical distance between two text lines. */
    private static final int LINE_STEP = 17;
    /** First line's y, just under the title. */
    private static final int LINE_START = 43;

    private static final int BACK = 1;
    private static final int EXPORT = 2;
    private static final int DEBUG = 6;
    private static final int BUNDLE = 7;
    private static final int PAGE = 8;
    private static final int RESET = 9;

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
     * on each call: two {@code Files.size} calls on the render thread plus 8–10 {@code I18n.translate}
     * formattings per frame, at 60 fps. Caching makes that cost per invalidation instead of per frame;
     * the counters page still refreshes on its own because a stale entry is rebuilt once
     * {@link #LINE_REFRESH_MILLIS} has passed.
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
        this.parent = parent;
    }

    @Override
    public void init() {
        buttons.clear();
        renderer = OrnitheClientAccess.textRenderer();
        FabricConfig config = FabricTranslationRuntime.diagnosticsConfig();
        if (config != null) {
            // Re-read here rather than in the constructor so a rebuild after a resize, or a change
            // made by another screen, is reflected instead of showing a stale value.
            debugEnabled = config.debugLog();
        }
        // Entering the screen (or rebuilding it after a resize) is the natural point to recompute.
        invalidateLines();
        int totalWidth = Math.max(180, Math.min(320, width - 24));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (width - totalWidth) / 2;

        debugButton = new ActionButton(DEBUG, left, height - 76, buttonWidth, 20, "");
        addButton(debugButton);
        addButton(new ActionButton(BUNDLE, left + buttonWidth + gap, height - 76,
                buttonWidth, 20, tr("screen.universal_translator.debug.bundle")));

        pageButton = new ActionButton(PAGE, left, height - 52, buttonWidth, 20, "");
        addButton(pageButton);
        resetButton = new ActionButton(RESET, left + buttonWidth + gap, height - 52,
                buttonWidth, 20, tr("screen.universal_translator.stats.reset"));
        addButton(resetButton);

        addButton(new ActionButton(BACK, left, height - 28,
                buttonWidth, 20, tr("screen.universal_translator.diagnostics.back")));
        addButton(new ActionButton(EXPORT, left + buttonWidth + gap, height - 28,
                buttonWidth, 20, tr("screen.universal_translator.diagnostics.export")));
        refreshDebugLabel();
        refreshPageLabels();
    }

    private void refreshDebugLabel() {
        if (debugButton == null) {
            return;
        }
        debugButton.message = tr("screen.universal_translator.debug.mode",
                tr(UiTextKeys.onOff(debugEnabled)));
    }

    private void refreshPageLabels() {
        if (pageButton != null) {
            pageButton.message = tr(statisticsPage
                    ? "screen.universal_translator.stats.show_diagnostics"
                    : "screen.universal_translator.stats.show");
        }
        if (resetButton != null) {
            // Only the counters page can be cleared, so the button says so by going flat.
            resetButton.active = statisticsPage;
        }
    }

    void buttonClicked(ButtonWidget button) {
        if (button.id == BACK) {
            OrnitheClientAccess.openScreen(parent);
        } else if (button.id == EXPORT) {
            exportLog();
        } else if (button.id == DEBUG) {
            debugEnabled = !debugEnabled;
            applyDebugMode();
            invalidateLines();
        } else if (button.id == BUNDLE) {
            exportBundle();
        } else if (button.id == PAGE) {
            statisticsPage = !statisticsPage;
            refreshPageLabels();
            invalidateLines();
        } else if (button.id == RESET) {
            resetStatistics();
            invalidateLines();
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
            // Same writer as the F8 toggles, so an older queued snapshot cannot overwrite this one.
            FabricTranslationRuntimeBase.saveInBackgroundAndWait(updated);
            Path configFile = updated.configFile();
            DebugLog.global().configure(
                    configFile.resolveSibling(DebugLog.FILE_NAME), debugEnabled);
            exportStatus = tr("screen.universal_translator.debug.changed",
                    tr(UiTextKeys.onOff(debugEnabled)));
            exportFailed = false;
        } catch (Exception failure) {
            exportStatus = settingsFailure(failure);
            exportFailed = true;
        }
    }

    /** Clears the in-memory counters; the on-disk totals are only rewritten at shutdown. */
    private void resetStatistics() {
        TranslationStats.global().reset();
        exportStatus = tr("screen.universal_translator.stats.reset_done");
        exportFailed = false;
    }

    private String settingsFailure(Exception failure) {
        String message = failure.getMessage();
        return tr("screen.universal_translator.diagnostics.settings_failed",
                message == null || message.trim().isEmpty()
                        ? failure.getClass().getSimpleName() : message);
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        applyPendingExport();
        renderBackground();
        fill(Math.max(5, width / 2 - 190), 8,
                Math.min(width - 5, width / 2 + 190), height - 34, 0xD51A232E);
        drawCenteredString(renderer,
                statisticsPage
                        ? tr("screen.universal_translator.stats.title")
                        : tr("screen.universal_translator.diagnostics.title"),
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
            drawShadowedString(line, left, y, 0xFFD0D0D0);
            y += LINE_STEP;
        }
        drawCenteredString(renderer,
                tr(statisticsPage
                        ? "screen.universal_translator.stats.note"
                        : "screen.universal_translator.diagnostics.note"),
                width / 2, Math.min(y + 7, height - 88), 0xFF909090);
        if (!exportStatus.isEmpty()) {
            // Above the first button row (height-76): drawn at height-82 the row's background and
            // hover highlight covered the bottom two pixels, cutting the descenders off the only
            // feedback the export gives.
            drawCenteredString(renderer, exportStatus, width / 2, height - 86,
                    exportFailed ? 0xFFFF5555 : 0xFF55FF88);
        }
        super.render(mouseX, mouseY, partialTicks);
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
     * Rebuilds the page contents, which is the expensive part: it localizes 4–10 lines and, on the
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
        ScreenWorkers.export(() -> {
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
        });
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
    public boolean shouldPauseGame() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return I18n.translate(key, arguments);
    }
}
