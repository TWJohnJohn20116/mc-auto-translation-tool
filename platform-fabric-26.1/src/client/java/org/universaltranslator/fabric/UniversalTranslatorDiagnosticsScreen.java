package org.universaltranslator.fabric;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;

import java.util.List;

/** Secret-free runtime diagnostics that update while the screen is open. */
final class UniversalTranslatorDiagnosticsScreen extends Screen {
    private final Screen parent;

    UniversalTranslatorDiagnosticsScreen(Screen parent) {
        super(Component.translatable("screen.universal_translator.diagnostics.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int buttonWidth = Math.max(120, Math.min(220, this.width - 40));
        addRenderableWidget(Button.builder(
                Component.translatable("screen.universal_translator.diagnostics.back"), button -> onClose())
                .bounds((this.width - buttonWidth) / 2, this.height - 28, buttonWidth, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.centeredText(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        TranslationDiagnosticsSnapshot snapshot = FabricTranslationRuntime.diagnostics();
        List<String> lines = snapshot.localizedLines(UniversalTranslatorDiagnosticsScreen::tr);
        int left = Math.max(10, (this.width - Math.min(360, this.width - 20)) / 2);
        int y = 43;
        for (String line : lines) {
            graphics.text(this.font, Component.literal(line), left, y, 0xD0D0D0);
            y += 17;
        }
        graphics.centeredText(this.font,
                Component.translatable("screen.universal_translator.diagnostics.note"),
                this.width / 2, Math.min(y + 7, this.height - 43), 0x808080);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
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
