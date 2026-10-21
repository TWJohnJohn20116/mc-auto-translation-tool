package org.universaltranslator.forge;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

/**
 * Component factory for the shared settings screens.
 *
 * <p>1.18.2 still builds components through the concrete {@link TextComponent} and
 * {@link TranslatableComponent} classes; 1.19.2 moved both onto {@code Component}, so the shared
 * sources go through this seam instead of naming either form.
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
}