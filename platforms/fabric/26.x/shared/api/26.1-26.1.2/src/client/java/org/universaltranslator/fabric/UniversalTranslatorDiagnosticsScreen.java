package org.universaltranslator.fabric;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.DiagnosticsBundleExporter;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;
import org.universaltranslator.core.TranslationPrompt;
import org.universaltranslator.core.TranslationQuality;
import org.universaltranslator.core.TranslationStats;

import java.nio.file.Path;
import java.util.List;
import java.util.Collections;

/** Secret-free runtime diagnostics that update while the screen is open. */
final class UniversalTranslatorDiagnosticsScreen extends Screen {
    /** Longest custom prompt the editor accepts, matching the settings screens' text field bound. */
    private static final int MAXIMUM_PROMPT_LENGTH = 4096;
    /** Vertical distance between two text lines. */
    private static final int LINE_STEP = 17;
    /** First line's y, below the quality and prompt controls. */
    private static final int LINE_START = 84;

    private final Screen parent;
    /** Selected quality mode; read from the live configuration on every {@link #init()}. */
    private TranslationQuality quality;
    private Button qualityButton;
    private EditBox customPrompt;
    private Button debugButton;
    private Button pageButton;
    private Button resetButton;
    /** Whether the counters page is shown instead of the engine diagnostics page. */
    private boolean statisticsPage;
    private boolean debugEnabled;
    private String exportStatus = "";
    private boolean exportFailed;

    UniversalTranslatorDiagnosticsScreen(Screen parent) {
        super(Component.translatable("screen.universal_translator.diagnostics.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        FabricConfig config = FabricTranslationRuntime.diagnosticsConfig();
        if (config != null) {
            // Re-read here rather than in the constructor so a rebuild after a resize, or a change
            // made by another screen, is reflected instead of showing a stale value.
            quality = config.translationQuality();
            debugEnabled = config.debugLog();
        }
        if (quality == null) {
            quality = TranslationQuality.DEFAULT;
        }
        String promptValue = customPrompt == null
                ? TranslationPrompt.toEditorText(config == null ? "" : config.customSystemPrompt())
                : customPrompt.getValue();
        int totalWidth = Math.max(180, Math.min(320, this.width - 24));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (this.width - totalWidth) / 2;

        this.qualityButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            quality = quality.next();
            applyAdvancedSettings();
        }).bounds(left, 32, totalWidth, 20).build());

        int applyWidth = Math.max(48, buttonWidth - 24);
        this.customPrompt = addRenderableWidget(new EditBox(
                this.font, left, 56, totalWidth - applyWidth - gap, 20,
                Component.translatable("screen.universal_translator.diagnostics.prompt")));
        this.customPrompt.setMaxLength(MAXIMUM_PROMPT_LENGTH);
        this.customPrompt.setValue(promptValue);
        this.customPrompt.setHint(
                Component.translatable("screen.universal_translator.diagnostics.prompt_hint"));
        addRenderableWidget(Button.builder(
                Component.translatable("screen.universal_translator.diagnostics.prompt_apply"),
                button -> applyAdvancedSettings())
                .bounds(left + totalWidth - applyWidth, 56, applyWidth, 20).build());

        this.debugButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            debugEnabled = !debugEnabled;
            applyDebugMode();
        }).bounds(left, this.height - 76, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("screen.universal_translator.debug.bundle"),
                button -> exportBundle())
                .bounds(left + buttonWidth + gap, this.height - 76, buttonWidth, 20).build());

        this.pageButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            statisticsPage = !statisticsPage;
            refreshPageLabels();
        }).bounds(left, this.height - 52, buttonWidth, 20).build());
        this.resetButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.universal_translator.stats.reset"),
                button -> resetStatistics())
                .bounds(left + buttonWidth + gap, this.height - 52, buttonWidth, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("screen.universal_translator.diagnostics.back"), button -> onClose())
                .bounds(left, this.height - 28, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("screen.universal_translator.diagnostics.export"), button -> exportLog())
                .bounds(left + buttonWidth + gap, this.height - 28, buttonWidth, 20).build());
        refreshQualityLabel();
        refreshDebugLabel();
        refreshPageLabels();
    }

    private void refreshQualityLabel() {
        if (qualityButton == null) {
            return;
        }
        qualityButton.setMessage(Component.translatable(
                "screen.universal_translator.option.quality",
                tr("value.universal_translator.quality." + quality.configName())));
    }

    private void refreshDebugLabel() {
        if (debugButton == null) {
            return;
        }
        debugButton.setMessage(Component.translatable("screen.universal_translator.debug.mode",
                tr(debugEnabled
                        ? "value.universal_translator.on" : "value.universal_translator.off")));
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
     * Applies the selected quality mode and custom prompt to the live configuration and persists
     * them.
     *
     * <p>Only the system prompt changes: the provider is rebuilt through exactly the path the
     * settings screen uses, so the new prompt is used by the next request and the cache key moves
     * with it. A failure leaves the previous configuration running and reports it in place.
     */
    private void applyAdvancedSettings() {
        refreshQualityLabel();
        FabricConfig config = FabricTranslationRuntime.diagnosticsConfig();
        if (config == null) {
            exportStatus = tr("screen.universal_translator.diagnostics.settings_unavailable");
            exportFailed = true;
            return;
        }
        try {
            String prompt = customPrompt == null
                    ? config.customSystemPrompt()
                    : TranslationPrompt.fromEditorText(customPrompt.getValue());
            FabricConfig updated = config.withPromptSettings(quality, prompt);
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            FabricTranslationRuntime.initialize(updated);
            updated.save();
            exportStatus = tr("screen.universal_translator.diagnostics.settings_saved");
            exportFailed = false;
        } catch (Exception failure) {
            String message = failure.getMessage();
            exportStatus = tr("screen.universal_translator.diagnostics.settings_failed",
                    message == null || message.trim().isEmpty()
                            ? failure.getClass().getSimpleName() : message);
            exportFailed = true;
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
                    tr(debugEnabled
                            ? "value.universal_translator.on" : "value.universal_translator.off"));
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
        List<String> lines = displayLines();
        int left = Math.max(10, (this.width - Math.min(360, this.width - 20)) / 2);
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
            graphics.text(this.font, Component.literal(line), left, y, 0xFFD0D0D0);
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
     */
    private void exportBundle() {
        FabricConfig config = FabricTranslationRuntime.diagnosticsConfig();
        if (config == null) {
            exportStatus = tr("screen.universal_translator.diagnostics.settings_unavailable");
            exportFailed = true;
            return;
        }
        try {
            Path configFile = config.configFile();
            Path archive = DiagnosticsBundleExporter.export(
                    configFile.getParent(), configFile,
                    configFile.resolveSibling(DebugLog.FILE_NAME), "fabric", diagnosticsLines());
            exportStatus = tr("screen.universal_translator.debug.bundle_exported",
                    archive.getFileName().toString());
            exportFailed = false;
        } catch (Exception failure) {
            exportStatus = tr("screen.universal_translator.diagnostics.export_failed");
            exportFailed = true;
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
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
