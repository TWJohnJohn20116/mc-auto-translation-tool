package org.universaltranslator.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Bridges Minecraft 26.2 moving {@code setScreen} and the current-screen accessor off {@link
 * Minecraft} itself onto its {@code gui} field.
 *
 * <p>26.2 and later expose the screen stack through {@code Minecraft#gui}, so the shared screens
 * call this instead of {@code Minecraft#setScreen(Screen)} / {@code Minecraft#screen}.
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
        client.gui.setScreen(screen);
    }

    static Screen currentScreen(Minecraft client) {
        return client == null ? null : client.gui.screen();
    }
}
