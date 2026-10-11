package org.universaltranslator.forge.legacy;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.DiagnosticsBundleExporter;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;
import org.universaltranslator.core.TranslationPrompt;
import org.universaltranslator.core.TranslationQuality;
import org.universaltranslator.core.TranslationStats;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Collections;

/** Secret-free runtime diagnostics shared by Forge 1.8.9 and 1.12.2. */
final class LegacyDiagnosticsScreen extends GuiScreen {
    /** Longest custom prompt the editor accepts, matching the settings screens' text field bound. */
    private static final int MAXIMUM_PROMPT_LENGTH = 4096;
    /** Vertical distance between two text lines. */
    private static final int LINE_STEP = 17;
    /** First line's y, below the quality and prompt controls. */
    private static final int LINE_START = 84;

    private static final int BACK = 1;
    private static final int EXPORT = 2;
    private static final int QUALITY = 3;
    private static final int PROMPT_APPLY = 4;
    private static final int DEBUG = 5;
    private static final int BUNDLE = 6;
    private static final int PAGE = 7;
    private static final int RESET = 8;
    /** The prompt editor's own id; GuiTextField ids are a separate namespace from button ids. */
    private static final int PROMPT_FIELD = 21;

    private final GuiScreen parent;
    private FontRenderer renderer;
    /** Selected quality mode; read from the live configuration on every {@link #initGui()}. */
    private TranslationQuality quality;
    private GuiButton qualityButton;
    private GuiTextField customPrompt;
    private GuiButton debugButton;
    private GuiButton pageButton;
    private GuiButton resetButton;
    /** Where the prompt field was placed, so the empty-state hint can be drawn inside it. */
    private int promptX;
    private int promptY;
    /** Whether the counters page is shown instead of the engine diagnostics page. */
    private boolean statisticsPage;
    private boolean debugEnabled;
    private String exportStatus = "";
    private boolean exportFailed;

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

        qualityButton = new GuiButton(QUALITY, left, 32, totalWidth, 20, "");
        buttonList.add(qualityButton);

        int applyWidth = Math.max(48, buttonWidth - 24);
        promptX = left;
        promptY = 56;
        customPrompt = new GuiTextField(PROMPT_FIELD, renderer, promptX, promptY,
                totalWidth - applyWidth - gap, 20);
        customPrompt.setMaxStringLength(MAXIMUM_PROMPT_LENGTH);
        customPrompt.setText(promptValue);
        buttonList.add(new GuiButton(PROMPT_APPLY, left + totalWidth - applyWidth, 56,
                applyWidth, 20, tr("screen.universal_translator.diagnostics.prompt_apply")));

        debugButton = new GuiButton(DEBUG, left, height - 76, buttonWidth, 20, "");
        buttonList.add(debugButton);
        buttonList.add(new GuiButton(BUNDLE, left + buttonWidth + gap, height - 76,
                buttonWidth, 20, tr("screen.universal_translator.debug.bundle")));

        pageButton = new GuiButton(PAGE, left, height - 52, buttonWidth, 20, "");
        buttonList.add(pageButton);
        resetButton = new GuiButton(RESET, left + buttonWidth + gap, height - 52,
                buttonWidth, 20, tr("screen.universal_translator.stats.reset"));
        buttonList.add(resetButton);

        buttonList.add(new GuiButton(BACK, left, height - 28,
                buttonWidth, 20, tr("screen.universal_translator.diagnostics.back")));
        buttonList.add(new GuiButton(EXPORT, left + buttonWidth + gap, height - 28,
                buttonWidth, 20, tr("screen.universal_translator.diagnostics.export")));
        refreshQualityLabel();
        refreshDebugLabel();
        refreshPageLabels();
    }

    private void refreshQualityLabel() {
        if (qualityButton == null) {
            return;
        }
        qualityButton.displayString = tr("screen.universal_translator.option.quality",
                tr("value.universal_translator.quality." + quality.configName()));
    }

    private void refreshDebugLabel() {
        if (debugButton == null) {
            return;
        }
        debugButton.displayString = tr("screen.universal_translator.debug.mode",
                tr(debugEnabled ? "value.universal_translator.on" : "value.universal_translator.off"));
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
        LegacyConfig config = LegacyTranslationRuntime.diagnosticsConfig();
        if (config == null) {
            exportStatus = tr("screen.universal_translator.diagnostics.settings_unavailable");
            exportFailed = true;
            return;
        }
        try {
            String prompt = customPrompt == null
                    ? config.customSystemPrompt()
                    : TranslationPrompt.fromEditorText(customPrompt.getText());
            LegacyConfig updated = config.withPromptSettings(quality, prompt);
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            LegacyTranslationRuntime.initialize(updated);
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
        LegacyConfig config = LegacyTranslationRuntime.diagnosticsConfig();
        if (config == null) {
            exportStatus = tr("screen.universal_translator.diagnostics.settings_unavailable");
            exportFailed = true;
            return;
        }
        try {
            LegacyConfig updated = config.withDebugLog(debugEnabled);
            LegacyTranslationRuntime.initialize(updated);
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
    public void updateScreen() {
        if (customPrompt != null) {
            customPrompt.updateCursorCounter();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (customPrompt != null && customPrompt.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (customPrompt != null) {
            customPrompt.mouseClicked(mouseX, mouseY, mouseButton);
        }
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
        if (customPrompt != null) {
            customPrompt.drawTextBox();
            if (customPrompt.getText().isEmpty()) {
                renderer.drawStringWithShadow(
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
                            LegacyDiagnosticsScreen::tr)
                    : diagnosticsLines();
        } catch (RuntimeException ignored) {
            return Collections.singletonList(
                    tr("screen.universal_translator.diagnostics.unavailable"));
        }
    }

    /** The engine diagnostics lines, which are also what the exported report contains. */
    private List<String> diagnosticsLines() {
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
        try {
            LegacyTranslationRuntime.exportDiagnostics(diagnosticsLines());
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
        LegacyConfig config = LegacyTranslationRuntime.diagnosticsConfig();
        if (config == null) {
            exportStatus = tr("screen.universal_translator.diagnostics.settings_unavailable");
            exportFailed = true;
            return;
        }
        try {
            Path configFile = config.configFile();
            Path archive = DiagnosticsBundleExporter.export(
                    configFile.getParent(), configFile,
                    configFile.resolveSibling(DebugLog.FILE_NAME), "forge", diagnosticsLines());
            exportStatus = tr("screen.universal_translator.debug.bundle_exported",
                    archive.getFileName().toString());
            exportFailed = false;
        } catch (Exception failure) {
            exportStatus = tr("screen.universal_translator.diagnostics.export_failed");
            exportFailed = true;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return I18n.format(key, arguments);
    }
}
