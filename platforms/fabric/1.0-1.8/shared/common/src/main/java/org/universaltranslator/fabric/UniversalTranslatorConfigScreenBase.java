package org.universaltranslator.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.render.TextRenderer;

/**
 * Version seams for the shared settings screen.
 *
 * <p>This bundle compiles for 1.3.1 through 1.8.8 from one source set, so the parts that differ
 * across that range cannot live in the shared screen: the text field constructor gained an id in
 * 1.8, {@code mouseDragged} only exists from 1.7.10, and the translation helper differs.
 */
abstract class UniversalTranslatorConfigScreenBase extends Screen {
    protected static String tr(String key, Object... arguments) {
        return OrnitheClientAccess.tr(key, arguments);
    }

    /** The {@code id} argument is ignored here: this bundle's TextFieldWidget has no id. */
    protected TextFieldWidget textField(int id, TextRenderer renderer,
                                        int x, int y, int width, int height) {
        return new TextFieldWidget(renderer, x, y, width, height);
    }

    // Deliberately no @Override and no super call: Screen only gained mouseDragged in 1.7.10, and
    // this bundle also compiles for 1.3.1-1.5.2 where the method does not exist. Dragging the HUD
    // preview therefore works from 1.7.10 onwards; older versions still have the offset buttons.
    protected void mouseDragged(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        handleDragMove(mouseX, mouseY);
    }

    protected abstract boolean handleDragMove(double mouseX, double mouseY);
}