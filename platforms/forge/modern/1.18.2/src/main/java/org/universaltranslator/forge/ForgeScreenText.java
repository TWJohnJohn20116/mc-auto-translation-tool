package org.universaltranslator.forge;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

/**
 * Component factory for the shared settings screens.
 *
 * <p>1.18.2 is the last version with the concrete {@link TextComponent} and
 * {@link TranslatableComponent} classes and with only an edit box suggestion; 1.19 replaced both
 * with {@code Component.literal}/{@code Component.translatable}. The shared sources go through this
 * seam instead of naming either form.
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