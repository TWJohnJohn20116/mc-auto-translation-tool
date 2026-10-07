package org.universaltranslator.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;

/**
 * TEMPORARY probe. This file exists only to make the 1.13.2 mapping names visible in a CI log: every
 * line below names a candidate class or member, and the compiler reports the ones that do not exist.
 * Delete this file as soon as the names are known.
 */
final class HudApiProbe {
    private HudApiProbe() {
    }

    static void probeWindowClass() {
        Object a = (net.minecraft.client.Window) null;
        Object b = (net.minecraft.class_1041) null;
        Object c = (net.minecraft.client.util.Window) null;
        Object d = (com.mojang.blaze3d.platform.Window) null;
    }

    static void probeWindowAccess(MinecraftClient client) {
        Object a = client.window;
        Object b = client.getWindow();
    }

    static void probeWindowSize(MinecraftClient client) {
        Object a = client.window.getScaledWidth();
        Object b = client.window.getScaledHeight();
        Object c = client.window.getGuiScaledWidth();
        Object d = client.window.getGuiScaledHeight();
        Object e = client.getWindow().getScaledWidth();
    }

    static void probeTextWidth(TextRenderer font) {
        Object a = font.getStringWidth("x");
        Object b = font.getWidth("x");
    }
}
