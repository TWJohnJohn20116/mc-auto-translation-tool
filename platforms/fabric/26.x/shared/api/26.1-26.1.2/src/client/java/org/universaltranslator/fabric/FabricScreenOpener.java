package org.universaltranslator.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Bridges Minecraft 26.2 moving {@code setScreen} from {@link Minecraft} onto its {@code gui} field.
 *
 * <p>26.1 through 26.1.2 still expose {@code Minecraft#setScreen(Screen)} directly, so this variant
 * keeps the shared screens compiling unchanged.
 *
 * <p>Deliberately one line: this class exists only to carry that single API difference. Do not copy
 * a neighbouring class wholesale onto it — the {@code FabricLocalTextGuard} copies did that and now
 * have to be kept in step by hand.
 */
final class FabricScreenOpener {
    private FabricScreenOpener() {
    }

    static void open(Minecraft client, Screen screen) {
        client.setScreen(screen);
    }
}
