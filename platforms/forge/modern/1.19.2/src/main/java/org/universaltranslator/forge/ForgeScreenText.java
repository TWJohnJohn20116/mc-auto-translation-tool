package org.universaltranslator.forge;

import net.minecraft.network.chat.Component;

/**
 * Component factory for the shared settings screens.
 *
 * <p>1.19.2 builds literals and translatables through {@code Component}; 1.18.2 still needs the
 * concrete {@code TextComponent} and {@code TranslatableComponent} classes, so the shared sources
 * go through this seam instead of naming either form.
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
}