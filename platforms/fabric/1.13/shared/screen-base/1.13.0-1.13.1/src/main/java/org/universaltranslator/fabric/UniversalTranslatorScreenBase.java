package org.universaltranslator.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.TextRenderer;

/**
 * Per-mapping half of the 1.13 screens, for the Ornithe builds (1.13 and 1.13.1).
 *
 * <p>The three screens live once in {@code 1.13/shared/screens}. Everything that differs between
 * the two 1.13 build paths lives here: this variant uses {@code net.minecraft.client.render
 * .TextRenderer} and the {@code drawString} name.
 */
abstract class UniversalTranslatorScreenBase extends Screen {
    protected TextRenderer renderer;

    protected void drawShadowedString(String text, int x, int y, int color) {
        drawString(renderer, text, x, y, color);
    }
}
