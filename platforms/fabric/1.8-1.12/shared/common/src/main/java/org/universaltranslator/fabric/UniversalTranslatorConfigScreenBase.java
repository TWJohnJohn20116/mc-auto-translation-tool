package org.universaltranslator.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.resource.language.I18n;

/**
 * Version seams for the shared settings screen.
 *
 * <p>Both Ornithe bundles compile the shared screen, so the parts that differ between them live
 * here: the text field constructor takes an id from 1.8 onwards, and this family reaches the
 * translation helper through {@link I18n} rather than {@code OrnitheClientAccess}.
 */
abstract class UniversalTranslatorConfigScreenBase extends Screen {
    protected static String tr(String key, Object... arguments) {
        return I18n.translate(key, arguments);
    }

    protected TextFieldWidget textField(int id, TextRenderer renderer,
                                        int x, int y, int width, int height) {
        return new TextFieldWidget(id, renderer, x, y, width, height);
    }

    @Override
    protected void mouseDragged(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (handleDragMove(mouseX, mouseY)) {
            return;
        }
        super.mouseDragged(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    protected abstract boolean handleDragMove(double mouseX, double mouseY);
}