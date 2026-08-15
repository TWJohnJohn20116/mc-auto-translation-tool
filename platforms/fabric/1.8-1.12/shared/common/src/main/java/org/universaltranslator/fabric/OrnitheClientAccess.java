package org.universaltranslator.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.LiteralText;

public final class OrnitheClientAccess {
    private OrnitheClientAccess() {
    }

    public static Minecraft client() {
        return Minecraft.getInstance();
    }

    public static TextRenderer textRenderer() {
        return client().textRenderer;
    }

    public static void openScreen(Screen screen) {
        client().openScreen(screen);
    }

    public static String tr(String key, Object... arguments) {
        return I18n.translate(key, arguments);
    }

    public static void overlay(String key, Object... arguments) {
        client().gui.setOverlayMessage(tr(key, arguments), false);
    }

    public static void chat(String key, Object... arguments) {
        client().gui.getChat().addMessage(new LiteralText(tr(key, arguments)));
    }

    static int maximumChatLength() {
        try {
            String version = FabricLoader.getInstance().getModContainer("minecraft")
                    .map(container -> container.getMetadata().getVersion().getFriendlyString())
                    .orElse("");
            if (version.startsWith("1.8.") || version.startsWith("1.9") || version.startsWith("1.10")) {
                return 100;
            }
        } catch (RuntimeException ignored) {
        }
        return 256;
    }
}
