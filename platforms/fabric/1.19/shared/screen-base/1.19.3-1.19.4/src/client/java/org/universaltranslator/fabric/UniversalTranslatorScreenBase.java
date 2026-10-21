package org.universaltranslator.fabric;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

/**
 * Per-generation half of the 1.19 screens, for 1.19.3 and 1.19.4.
 *
 * <p>Two seams live here. {@code drawCenteredText} was carried locally on these two versions, so it
 * keeps hiding the inherited copy and the centre calculation stays exactly as before.
 *
 * <p>{@link #of} wraps the local shim's factory. The shim cannot name it {@code builder}: 1.19.3
 * added a static {@code builder(Text, PressAction)} to the vanilla class, and hiding it with this
 * shim's own {@code Builder} return type is a compile error.
 */
abstract class UniversalTranslatorScreenBase extends Screen {
    UniversalTranslatorScreenBase(Text title) {
        super(title);
    }

    public static void drawCenteredText(MatrixStack matrices, TextRenderer renderer, Text text,
                                        int centerX, int y, int color) {
        renderer.drawWithShadow(matrices, text, centerX - renderer.getWidth(text) / 2.0f, y, color);
    }

    protected static ButtonWidget.Builder of(Text message, ButtonWidget.PressAction action) {
        return ButtonWidget.of(message, action);
    }
}
