package org.universaltranslator.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Bridges the screen stack on 26.1 through 26.1.2, where {@link Minecraft} still exposes {@code
 * setScreen(Screen)} and the {@code screen} field directly.
 *
 * <p>Deliberately one line per method: this class exists only to carry that single API difference.
 * Do not copy a neighbouring class wholesale onto it — the {@code FabricLocalTextGuard} and
 * {@code FabricTranslationRuntime} copies did that and now have to be kept in step by hand for one
 * statement each.
 */
final class FabricScreenOpener {
    private FabricScreenOpener() {
    }

    static void open(Minecraft client, Screen screen) {
        client.setScreen(screen);
    }

    static Screen currentScreen(Minecraft client) {
        return client == null ? null : client.screen;
    }
}
