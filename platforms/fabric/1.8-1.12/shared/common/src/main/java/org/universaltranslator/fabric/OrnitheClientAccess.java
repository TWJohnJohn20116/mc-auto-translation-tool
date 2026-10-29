package org.universaltranslator.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.LiteralText;

import java.util.concurrent.atomic.AtomicBoolean;

import org.universaltranslator.core.DebugLog;

public final class OrnitheClientAccess {
    /** One-shot switches: a drifted mapping must be reported once, not once per tick. */
    private static final AtomicBoolean OVERLAY_FALLBACK_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean CHAT_FALLBACK_REPORTED = new AtomicBoolean();

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
        String message = tr(key, arguments);
        if (client().gui == null) {
            // Start-up and shutdown leave the HUD unset; the message has nowhere to go and this is
            // not a mapping drift, so it is dropped without a report.
            return;
        }
        try {
            client().gui.setOverlayMessage(message, false);
        } catch (LinkageError error) {
            reportOnce(OVERLAY_FALLBACK_REPORTED, "setOverlayMessage", error);
        }
    }

    public static void chat(String key, Object... arguments) {
        String message = tr(key, arguments);
        if (client().gui == null) {
            return;
        }
        try {
            client().gui.getChat().addMessage(new LiteralText(message));
        } catch (LinkageError error) {
            // A renamed method or a changed component type surfaces as an Error here, which used to
            // escape into Minecraft.tick() through the status notification.
            reportOnce(CHAT_FALLBACK_REPORTED, "ChatHud.addMessage", error);
        } catch (NullPointerException error) {
            // The chat HUD itself is not built yet: nothing to append the line to.
            reportOnce(CHAT_FALLBACK_REPORTED, "ChatHud.addMessage", error);
        }
    }

    private static void reportOnce(AtomicBoolean reported, String call, Throwable failure) {
        if (!reported.compareAndSet(false, true)) {
            return;
        }
        DebugLog.global().log("reflection",
                "call", call,
                "result", "unavailable",
                "failure", failure == null ? "none" : failure.getClass().getName());
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
