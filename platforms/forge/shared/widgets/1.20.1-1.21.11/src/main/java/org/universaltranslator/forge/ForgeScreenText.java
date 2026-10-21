package org.universaltranslator.forge;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Component factory for the shared settings widgets.
 *
 * <p>1.20.1 and 1.21 build literals and translatables through {@code Component} and set edit box
 * hints directly; 1.18.2 and 1.19.2 still need the concrete {@code TextComponent} and
 * {@code TranslatableComponent} classes and only have suggestions. The shared sources go through
 * this seam instead of naming either form.
 */
final class ForgeScreenText {
    private ForgeScreenText() {
    }

    static Component literal(String text) {
        return Component.literal(text);
    }

    static Component translatable(String key, Object... arguments) {
        return Component.translatable(key, arguments);
    }

    static Component empty() {
        return Component.empty();
    }

    static void hint(EditBox box, Component text) {
        box.setHint(text);
    }
}