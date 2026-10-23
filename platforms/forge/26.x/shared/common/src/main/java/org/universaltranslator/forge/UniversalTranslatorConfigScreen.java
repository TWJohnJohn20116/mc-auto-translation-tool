package org.universaltranslator.forge;

import net.minecraft.client.gui.screens.Screen;

/**
 * Entry point for the settings screen.
 *
 * <p>The body lives once in {@link UniversalTranslatorConfigScreenBase}; each version supplies the one
 * call that moved inside 26.x through {@code UniversalTranslatorConfigScreenSeams}. Nothing here is
 * version specific, so Forge and NeoForge 26.1 through 26.2 share this single file.
 */
final class UniversalTranslatorConfigScreen extends UniversalTranslatorConfigScreenBase {
    UniversalTranslatorConfigScreen(Screen parent, ForgeConfig config) {
        super(parent, config);
    }
}
