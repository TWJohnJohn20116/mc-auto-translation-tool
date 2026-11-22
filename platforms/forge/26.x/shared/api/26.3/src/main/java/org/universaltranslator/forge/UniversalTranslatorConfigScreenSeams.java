package org.universaltranslator.forge;

import net.minecraft.client.gui.screens.Screen;

/**
 * Per-version half of the settings screen for Minecraft 26.2.
 *
 * <p>Everything else lives one level up in {@code 26.x/shared/common}, which the whole 26.1 to 26.2
 * range shares. Only what genuinely moved inside that range stays here.
 */
abstract class UniversalTranslatorConfigScreenSeams extends UniversalTranslatorConfigScreenBase {
    UniversalTranslatorConfigScreenSeams(Screen parent, ForgeConfig config) {
        super(parent, config);
    }

    @Override
    protected void showScreen(Screen screen) {
        if (minecraft != null) {
            minecraft.gui.setScreen(screen);
        }
    }
}
