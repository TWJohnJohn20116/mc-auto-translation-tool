package org.universaltranslator.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Bridges Minecraft 26.2 moving {@code setScreen} from {@link Minecraft} onto its {@code gui} field.
 *
 * <p>26.2 and later expose the screen stack through {@code Minecraft#gui}, so the shared screens
 * call this instead of {@code Minecraft#setScreen(Screen)}.
 */
final class FabricScreenOpener {
    private FabricScreenOpener() {
    }

    static void open(Minecraft client, Screen screen) {
        client.gui.setScreen(screen);
    }
}
