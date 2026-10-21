package org.universaltranslator.fabric;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;

/**
 * Per-mapping half of the 1.13 screens, for the 1.13.2 build path.
 *
 * <p>Same as the Ornithe variant except that {@code TextRenderer} moved to
 * {@code net.minecraft.client.font} and the shadowed draw call is named {@code drawWithShadow}.
 */
abstract class UniversalTranslatorScreenBase extends Screen {
    protected TextRenderer renderer;

    protected void drawShadowedString(String text, int x, int y, int color) {
        drawWithShadow(renderer, text, x, y, color);
    }
}
