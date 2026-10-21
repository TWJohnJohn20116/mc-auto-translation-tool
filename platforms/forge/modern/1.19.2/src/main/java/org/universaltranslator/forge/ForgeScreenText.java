package org.universaltranslator.forge;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Component factory for the shared settings screens.
 *
 * <p>1.19 dropped the concrete {@code TextComponent} and {@code TranslatableComponent} classes for
 * {@code Component.literal}/{@code Component.translatable}, but the edit box still only has a
 * suggestion until 1.19.3. The shared sources go through this seam instead of naming either form.
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
        box.setSuggestion(text.getString());
    }
}