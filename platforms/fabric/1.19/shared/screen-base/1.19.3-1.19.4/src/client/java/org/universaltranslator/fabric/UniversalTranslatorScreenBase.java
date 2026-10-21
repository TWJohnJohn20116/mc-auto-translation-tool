package org.universaltranslator.fabric;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

/**
 * Per-generation half of the 1.19 screens, for 1.19.3 and 1.19.4.
 *
 * <p>Same as the earlier variant plus the {@code drawCenteredText} helper these two versions carry
 * locally; it hides the inherited one so the shadowed centre calculation stays exactly as before.
 */
abstract class UniversalTranslatorScreenBase extends Screen {
    UniversalTranslatorScreenBase(Text title) {
        super(title);
    }

    public static void drawCenteredText(MatrixStack matrices, TextRenderer renderer, Text text,
                                        int centerX, int y, int color) {
        renderer.drawWithShadow(matrices, text, centerX - renderer.getWidth(text) / 2.0f, y, color);
    }
}
