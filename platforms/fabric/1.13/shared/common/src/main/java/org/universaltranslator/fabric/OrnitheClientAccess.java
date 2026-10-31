package org.universaltranslator.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.text.LiteralText;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.universaltranslator.core.DebugLog;

public final class OrnitheClientAccess {
    private static final Logger LOGGER = Logger.getLogger(UniversalTranslatorFabricClient.MOD_ID);
    /** One-shot switches: a drifted mapping must be reported once, not once per tick. */
    private static final AtomicBoolean OVERLAY_FALLBACK_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean CHAT_FALLBACK_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean HOTKEY_FAILURE_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean TICK_SURFACE_REPORTED = new AtomicBoolean();

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

    /**
     * True when F1 has hidden the HUD.
     *
     * <p>The only read of the flag in this tree: the name has moved between generations (26.x no
     * longer exposes it on the options object at all), and this tree is compiled by the scheduled
     * legacy-targets run rather than by every pull request, so a name that no longer matches should
     * cost one line here instead of one call site per HUD hook.
     */
    public static boolean hudHidden(Minecraft client) {
        if (client == null || client.options == null) {
            return false;
        }
        return client.options.hudHidden;
    }

    public static void setScreen(Screen screen) {
        openScreen(screen);
    }

    public static void renderTextField(TextFieldWidget field, int mouseX, int mouseY, float delta) {
        field.render(mouseX, mouseY, delta);
    }

    public static String tr(String key, Object... arguments) {
        return I18n.translate(key, arguments);
    }

    public static void overlay(String key, Object... arguments) {
        String message = tr(key, arguments);
        if (client() == null || client().gui == null) {
            // Start-up and shutdown leave the HUD unset; the message has nowhere to go and this is
            // not a mapping drift, so it is dropped without a report.
            return;
        }
        try {
            client().gui.setOverlayMessage(message, false);
        } catch (LinkageError error) {
            // A renamed setOverlayMessage surfaces as an Error, which used to escape into
            // Minecraft.tick() through the status notification.
            reportOnce(OVERLAY_FALLBACK_REPORTED, "setOverlayMessage", error);
        }
    }

    public static void chat(String key, Object... arguments) {
        String message = tr(key, arguments);
        if (client() == null || client().gui == null) {
            return;
        }
        try {
            client().gui.getChat().addMessage(new LiteralText(message));
        } catch (LinkageError error) {
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

    /**
     * Records once that the hotkey poll threw, and drops the failure.
     *
     * <p>The poll runs from {@code Minecraft.tick()} through a mixin, so a drifted mapping there used
     * to throw out of the vanilla tick loop and take the client down for one key press. Reported once
     * because the poll runs again on the next tick and would otherwise flood the log.
     */
    public static void reportHotkeyFailure(Throwable failure) {
        if (!HOTKEY_FAILURE_REPORTED.compareAndSet(false, true)) {
            return;
        }
        LOGGER.log(Level.SEVERE, "MC Auto Translation Tool hotkey failed", failure);
        DebugLog.global().log("hotkey",
                "result", "failed",
                "failure", failure.getClass().getName());
    }

    /**
     * Records once that the tick injection really fired.
     *
     * <p>The hook is {@code require = 0} on purpose, so a renamed {@code tick()V} costs the hotkeys
     * and the status line instead of the launch. That choice used to be silent: "the keys stopped
     * working" and "this build never hooked the tick" looked identical in the log.
     */
    public static void noteTickSurface() {
        if (TICK_SURFACE_REPORTED.compareAndSet(false, true)) {
            DebugLog.global().log("render-surface", "applied", "minecraft-tick");
        }
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
