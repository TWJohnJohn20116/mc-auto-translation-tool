package org.universaltranslator.fabric;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;

import java.util.List;

/** Secret-free runtime diagnostics that update while the screen is open. */
final class UniversalTranslatorDiagnosticsScreen extends Screen {
    private final Screen parent;

    UniversalTranslatorDiagnosticsScreen(Screen parent) {
        super(Text.literal("翻译诊断"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int width = Math.max(120, Math.min(220, this.width - 40));
        addDrawableChild(ButtonWidget.builder(Text.literal("返回设置"), button -> close())
                .dimensions((this.width - width) / 2, this.height - 28, width, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 18, 0xFFFFFF);
        TranslationDiagnosticsSnapshot snapshot = FabricTranslationRuntime.diagnostics();
        List<String> lines = snapshot.displayLines();
        int left = Math.max(10, (width - Math.min(360, width - 20)) / 2);
        int y = 43;
        for (String line : lines) {
            context.drawTextWithShadow(textRenderer, Text.literal(line), left, y, 0xD0D0D0);
            y += 17;
        }
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("状态会实时更新；不显示端点或密钥。"),
                width / 2, Math.min(y + 7, height - 43), 0x808080);
        super.render(context, mouseX, mouseY, delta);
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
}
