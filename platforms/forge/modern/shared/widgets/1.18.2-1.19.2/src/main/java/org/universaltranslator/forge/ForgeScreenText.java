package org.universaltranslator.forge;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

/**
 * Component factory for the shared settings widgets.
 *
 * <p>1.18.2 and 1.19.2 build components through the concrete {@link TextComponent} and
 * {@link TranslatableComponent} classes, and their {@code EditBox} has no hint yet; 1.20.1 moved
 * both onto {@code Component}. The shared sources go through this seam instead of naming either
 * form.
 */
final class ForgeScreenText {
    private ForgeScreenText() {
    }

    static Component literal(String text) {
        return new TextComponent(text);
    }

    static Component translatable(String key, Object... arguments) {
        return new TranslatableComponent(key, arguments);
    }

    static Component empty() {
        return TextComponent.EMPTY;
    }

    static void hint(EditBox box, Component text) {
        box.setSuggestion(text.getString());
    }
}