package org.universaltranslator.forge;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.universaltranslator.core.DiagnosticsLogExporter;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;

import java.util.List;
import java.util.Collections;

/** Secret-free runtime diagnostics that update while the screen is open. */
final class UniversalTranslatorDiagnosticsScreen extends Screen {
    private final Screen parent;
    private final ForgeConfig config;
    private String exportStatus = "";
    private boolean exportFailed;

    UniversalTranslatorDiagnosticsScreen(Screen parent, ForgeConfig config) {
        super(Component.translatable("screen.universal_translator.diagnostics.title"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected void init() {
        int totalWidth = Math.max(180, Math.min(320, this.width - 24));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (this.width - totalWidth) / 2;
        addRenderableWidget(Button.builder(
                Component.translatable("screen.universal_translator.diagnostics.back"), button -> onClose())
                .bounds(left, this.height - 28, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(
                Component.translatable("screen.universal_translator.diagnostics.export"), button -> exportLog())
                .bounds(left + buttonWidth + gap, this.height - 28, buttonWidth, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, 0xE510151C);
        graphics.fill(Math.max(5, width / 2 - 190), 8,
                Math.min(width - 5, width / 2 + 190), height - 34, 0xD51A232E);
        graphics.centeredText(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        List<String> lines = safeLines();
        int left = Math.max(10, (this.width - Math.min(360, this.width - 20)) / 2);
        int y = 43;
        for (String line : lines) {
            graphics.text(this.font, Component.literal(line), left, y, 0xD0D0D0);
            y += 17;
        }
        graphics.centeredText(this.font,
                Component.translatable("screen.universal_translator.diagnostics.note"),
                this.width / 2, Math.min(y + 7, this.height - 58), 0x909090);
        if (!exportStatus.isEmpty()) {
            graphics.centeredText(this.font, Component.literal(exportStatus),
                    this.width / 2, this.height - 43, exportFailed ? 0xFF5555 : 0x55FF88);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    private List<String> safeLines() {
        try {
            TranslationDiagnosticsSnapshot snapshot = ForgeTranslationRuntime.diagnostics();
            return snapshot == null
                    ? Collections.singletonList(tr("screen.universal_translator.diagnostics.unavailable"))
                    : snapshot.localizedLines(UniversalTranslatorDiagnosticsScreen::tr);
        } catch (RuntimeException ignored) {
            return Collections.singletonList(tr("screen.universal_translator.diagnostics.unavailable"));
        }
    }

    private void exportLog() {
        try {
            DiagnosticsLogExporter.export(config.offlineDirectory.getParent()
                    .resolve("universal-translator-diagnostics"), safeLines());
            exportStatus = tr("screen.universal_translator.diagnostics.exported");
            exportFailed = false;
        } catch (Exception ignored) {
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
