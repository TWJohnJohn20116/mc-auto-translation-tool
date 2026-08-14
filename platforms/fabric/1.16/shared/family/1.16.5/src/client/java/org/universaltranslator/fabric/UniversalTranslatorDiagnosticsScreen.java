package org.universaltranslator.fabric;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.text.LiteralText;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;

import java.util.List;

/** Secret-free runtime diagnostics that update while the screen is open. */
final class UniversalTranslatorDiagnosticsScreen extends Screen {
    private final Screen parent;

    UniversalTranslatorDiagnosticsScreen(Screen parent) {
        super(new TranslatableText("screen.universal_translator.diagnostics.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int width = Math.max(120, Math.min(220, this.width - 40));
        addDrawableChild(ButtonWidget.builder(
                new TranslatableText("screen.universal_translator.diagnostics.back"), button -> onClose())
                .dimensions((this.width - width) / 2, this.height - 28, width, 20).build());
    }

    private <T extends net.minecraft.client.gui.widget.ClickableWidget> T addDrawableChild(T child) {
        return addButton(child);
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        drawCenteredText(matrices, textRenderer, title, width / 2, 18, 0xFFFFFF);
        TranslationDiagnosticsSnapshot snapshot = FabricTranslationRuntime.diagnostics();
        List<String> lines = snapshot.localizedLines(UniversalTranslatorDiagnosticsScreen::tr);
        int left = Math.max(10, (width - Math.min(360, width - 20)) / 2);
        int y = 43;
        for (String line : lines) {
            drawTextWithShadow(matrices, textRenderer, new LiteralText(line), left, y, 0xD0D0D0);
            y += 17;
        }
        drawCenteredText(matrices, textRenderer,
                new TranslatableText("screen.universal_translator.diagnostics.note"),
                width / 2, Math.min(y + 7, height - 43), 0x808080);
        super.render(matrices, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        if (client != null) {
            client.openScreen(parent);
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
