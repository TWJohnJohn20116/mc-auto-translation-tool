package org.universaltranslator.fabric;

import net.minecraft.client.gui.screen.Screen;

/**
 * Entry point for the settings screen.
 *
 * <p>The body lives once in {@link UniversalTranslatorSettingsScreen}; each generation supplies its
 * render surface, button factory and mouse callbacks through {@code UniversalTranslatorConfigScreenBase}.
 * Nothing in this shell is version specific, so all generations share this single file.
 */
final class UniversalTranslatorConfigScreen extends UniversalTranslatorConfigScreenBase {
    UniversalTranslatorConfigScreen(Screen parent, FabricConfig config) {
        super(parent, config);
    }
}
