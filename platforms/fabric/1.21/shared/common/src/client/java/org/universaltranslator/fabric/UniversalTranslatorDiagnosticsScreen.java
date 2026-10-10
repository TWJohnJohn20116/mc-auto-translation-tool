package org.universaltranslator.fabric;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;
import org.universaltranslator.core.TranslationQuality;

import java.util.List;
import java.util.Collections;

/** Secret-free runtime diagnostics that update while the screen is open. */
final class UniversalTranslatorDiagnosticsScreen extends Screen {
    private final Screen parent;
    /** Selected quality mode; read from the live configuration on every {@link #init()}. */
    private TranslationQuality quality;
    private ButtonWidget qualityButton;
    private String exportStatus = "";
    private boolean exportFailed;

    UniversalTranslatorDiagnosticsScreen(Screen parent) {
        super(Text.translatable("screen.universal_translator.diagnostics.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        FabricConfig config = FabricTranslationRuntime.diagnosticsConfig();
        if (config != null) {
            // Re-read here rather than in the constructor so a rebuild after a resize, or a change
            // made by another screen, is reflected instead of showing a stale value.
            quality = config.translationQuality();
        }
        if (quality == null) {
            quality = TranslationQuality.DEFAULT;
        }
        int totalWidth = Math.max(180, Math.min(320, this.width - 24));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (this.width - totalWidth) / 2;
        this.qualityButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            quality = quality.next();
            applyQuality();
        }).dimensions(left, this.height - 52, totalWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.universal_translator.diagnostics.back"), button -> close())
                .dimensions(left, this.height - 28, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.universal_translator.diagnostics.export"), button -> exportLog())
                .dimensions(left + buttonWidth + gap, this.height - 28, buttonWidth, 20).build());
        refreshQualityLabel();
    }

    private void refreshQualityLabel() {
        if (qualityButton == null) {
            return;
        }
        qualityButton.setMessage(Text.translatable(
                "screen.universal_translator.option.quality",
                tr("value.universal_translator.quality." + quality.configName())));
    }

    /**
     * Applies the selected quality mode to the live configuration and persists it.
     *
     * <p>Only the system prompt changes: the provider is rebuilt through exactly the path the
     * settings screen uses, so the new prompt is used by the next request and the cache key moves
     * with it. A failure leaves the previous configuration running and reports it in place.
     */
    private void applyQuality() {
        refreshQualityLabel();
        FabricConfig config = FabricTranslationRuntime.diagnosticsConfig();
        if (config == null) {
            exportStatus = tr("screen.universal_translator.diagnostics.settings_unavailable");
            exportFailed = true;
            return;
        }
        try {
            FabricConfig updated = config.withPromptSettings(quality, config.customSystemPrompt());
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

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xE510151C);
        context.fill(Math.max(5, width / 2 - 190), 8,
                Math.min(width - 5, width / 2 + 190), height - 34, 0xD51A232E);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 18, 0xFFFFFFFF);
        List<String> lines = safeLines();
        int left = Math.max(10, (width - Math.min(360, width - 20)) / 2);
        int y = 43;
        for (String line : lines) {
            context.drawTextWithShadow(textRenderer, Text.literal(line), left, y, 0xFFD0D0D0);
            y += 17;
        }
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("screen.universal_translator.diagnostics.note"),
                width / 2, Math.min(y + 7, height - 84), 0xFF909090);
        if (!exportStatus.isEmpty()) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(exportStatus),
                    width / 2, height - 70, exportFailed ? 0xFFFF5555 : 0xFF55FF88);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    private List<String> safeLines() {
        try {
            TranslationDiagnosticsSnapshot snapshot = FabricTranslationRuntime.diagnostics();
            return snapshot == null
                    ? Collections.singletonList(tr("screen.universal_translator.diagnostics.unavailable"))
                    : snapshot.localizedLines(UniversalTranslatorDiagnosticsScreen::tr);
        } catch (RuntimeException ignored) {
            return Collections.singletonList(tr("screen.universal_translator.diagnostics.unavailable"));
        }
    }

    private void exportLog() {
        try {
            FabricTranslationRuntime.exportDiagnostics(safeLines());
            exportStatus = tr("screen.universal_translator.diagnostics.exported");
            exportFailed = false;
        } catch (Exception ignored) {
            exportStatus = tr("screen.universal_translator.diagnostics.export_failed");
            exportFailed = true;
        }
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return Text.translatable(key, arguments).getString();
    }
}
