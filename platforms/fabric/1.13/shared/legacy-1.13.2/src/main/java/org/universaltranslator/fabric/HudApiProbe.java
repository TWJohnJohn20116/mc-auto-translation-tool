package org.universaltranslator.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;

/**
 * TEMPORARY probe. This file exists only to make the 1.13.2 mapping names visible in a CI log: every
 * line below names a candidate member, and the compiler reports the ones that do not exist.
 * Delete this file as soon as the names are known.
 */
final class HudApiProbe {
    private HudApiProbe() {
    }

    static void probeHud(MinecraftClient client) {
        Object a = client.inGameHud.getScaledWidth();
        Object b = client.inGameHud.getScaledHeight();
        Object c = client.inGameHud.getWidth();
        Object d = client.inGameHud.getHeight();
        Object e = client.inGameHud.getChatHud().getWidth();
        Object f = client.inGameHud.getChatHud().getHeight();
    }

    static void probeClient(MinecraftClient client) {
        Object a = client.getWindow();
        Object b = client.window;
        Object c = client.getFramebuffer();
        Object d = client.getMainWindow();
        Object e = client.getWindowHandle();
    }

    static void probeText(TextRenderer font) {
        Object a = font.getStringWidth("x");
        Object b = font.drawWithShadow("x", 0.0F, 0.0F, 0);
    }
}
