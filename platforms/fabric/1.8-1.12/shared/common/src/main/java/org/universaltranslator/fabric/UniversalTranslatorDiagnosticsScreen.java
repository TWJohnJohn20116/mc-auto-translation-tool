package org.universaltranslator.fabric;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.resource.language.I18n;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.DiagnosticsBundleExporter;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;
import org.universaltranslator.core.TranslationPrompt;
import org.universaltranslator.core.TranslationQuality;
import org.universaltranslator.core.TranslationStats;

import java.nio.file.Path;
import java.util.List;
import java.util.Collections;

/** Secret-free runtime diagnostics shared by Forge 1.8.9 and 1.12.2. */
final class UniversalTranslatorDiagnosticsScreen extends Screen {
    /** Longest custom prompt the editor accepts, matching the settings screens' text field bound. */
    private static final int MAXIMUM_PROMPT_LENGTH = 4096;
    /** Vertical distance between two text lines. */
    private static final int LINE_STEP = 17;
    /** First line's y, below the quality and prompt controls. */
    private static final int LINE_START = 84;

    private static final int BACK = 1;
    private static final int EXPORT = 2;
    private static final int QUALITY = 3;
    private static final int PROMPT = 4;
    private static final int PROMPT_APPLY = 5;
    private static final int DEBUG = 6;
    private static final int BUNDLE = 7;
    private static final int PAGE = 8;
    private static final int RESET = 9;

    private final Screen parent;
    private TextRenderer renderer;
    /** Selected quality mode; read from the live configuration on every {@link #init()}. */
    private TranslationQuality quality;
    private ButtonWidget qualityButton;
    private TextFieldWidget customPrompt;
    private ButtonWidget debugButton;
    private ButtonWidget pageButton;
    private ButtonWidget resetButton;
    /** Where the prompt field was placed, so the empty-state hint can be drawn inside it. */
    private int promptX;
    private int promptY;
    /** Whether the counters page is shown instead of the engine diagnostics page. */
    private boolean statisticsPage;
    private boolean debugEnabled;
    private String exportStatus = "";
    private boolean exportFailed;

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
            quality = config.translationQuality();
            debugEnabled = config.debugLog();
        }
        if (quality == null) {
            quality = TranslationQuality.DEFAULT;
        }
        String promptValue = customPrompt == null
                ? TranslationPrompt.toEditorText(config == null ? "" : config.customSystemPrompt())
                : customPrompt.getText();
        int totalWidth = Math.max(180, Math.min(320, width - 24));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (width - totalWidth) / 2;

        qualityButton = new ButtonWidget(QUALITY, left, 32, totalWidth, 20, "");
        buttons.add(qualityButton);

        int applyWidth = Math.max(48, buttonWidth - 24);
        promptX = left;
        promptY = 56;
        customPrompt = new TextFieldWidget(
                PROMPT, renderer, promptX, promptY, totalWidth - applyWidth - gap, 20);
        customPrompt.setMaxLength(MAXIMUM_PROMPT_LENGTH);
        customPrompt.setText(promptValue);
        buttons.add(new ButtonWidget(PROMPT_APPLY, left + totalWidth - applyWidth, 56,
                applyWidth, 20, tr("screen.universal_translator.diagnostics.prompt_apply")));

        debugButton = new ButtonWidget(DEBUG, left, height - 76, buttonWidth, 20, "");
        buttons.add(debugButton);
        buttons.add(new ButtonWidget(BUNDLE, left + buttonWidth + gap, height - 76,
                buttonWidth, 20, tr("screen.universal_translator.debug.bundle")));

        pageButton = new ButtonWidget(PAGE, left, height - 52, buttonWidth, 20, "");
        buttons.add(pageButton);
        resetButton = new ButtonWidget(RESET, left + buttonWidth + gap, height - 52,
                buttonWidth, 20, tr("screen.universal_translator.stats.reset"));
        buttons.add(resetButton);

        buttons.add(new ButtonWidget(BACK, left, height - 28,
                buttonWidth, 20, tr("screen.universal_translator.diagnostics.back")));
        buttons.add(new ButtonWidget(EXPORT, left + buttonWidth + gap, height - 28,
                buttonWidth, 20, tr("screen.universal_translator.diagnostics.export")));
        refreshQualityLabel();
        refreshDebugLabel();
        refreshPageLabels();
    }

    private void refreshQualityLabel() {
        if (qualityButton == null) {
            return;
        }
        qualityButton.message = tr("screen.universal_translator.option.quality",
                tr("value.universal_translator.quality." + quality.configName()));
    }

    private void refreshDebugLabel() {
        if (debugButton == null) {
            return;
        }
        debugButton.message = tr("screen.universal_translator.debug.mode",
                tr(debugEnabled ? "value.universal_translator.on" : "value.universal_translator.off"));
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

    @Override
    protected void buttonClicked(ButtonWidget button) {
        if (button.id == BACK) {
            OrnitheClientAccess.openScreen(parent);
        } else if (button.id == EXPORT) {
            exportLog();
        } else if (button.id == QUALITY) {
            quality = quality.next();
            applyAdvancedSettings();
        } else if (button.id == PROMPT_APPLY) {
            applyAdvancedSettings();
        } else if (button.id == DEBUG) {
            debugEnabled = !debugEnabled;
            applyDebugMode();
        } else if (button.id == BUNDLE) {
            exportBundle();
        } else if (button.id == PAGE) {
            statisticsPage = !statisticsPage;
            refreshPageLabels();
        } else if (button.id == RESET) {
            resetStatistics();
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
                    : TranslationPrompt.fromEditorText(customPrompt.getText());
            FabricConfig updated = config.withPromptSettings(quality, prompt);
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            FabricTranslationRuntime.initialize(updated);
            updated.save();
            exportStatus = tr("screen.universal_translator.diagnostics.settings_saved");
            exportFailed = false;
        } catch (Exception failure) {
            exportStatus = settingsFailure(failure);
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
            exportStatus = settingsFailure(failure);
            exportFailed = true;
        }
    }

    /** Clears the process-wide counters and writes the cleared values out. */
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
    public void tick() {
        if (customPrompt != null) {
            customPrompt.tick();
        }
    }

    @Override
    protected void keyPressed(char typedChar, int keyCode) {
        if (customPrompt != null && customPrompt.keyPressed(typedChar, keyCode)) {
            return;
        }
        super.keyPressed(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (customPrompt != null) {
            customPrompt.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
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
        super.render(mouseX, mouseY, partialTicks);
        if (customPrompt != null) {
            customPrompt.render();
            if (customPrompt.getText().isEmpty()) {
                renderer.drawWithShadow(
                        tr("screen.universal_translator.diagnostics.prompt_hint"),
                        promptX + 4, promptY + 6, 0x70A0A0A0);
            }
        }
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
    public boolean shouldPauseGame() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return I18n.translate(key, arguments);
    }
}
