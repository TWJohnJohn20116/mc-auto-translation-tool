package org.universaltranslator.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ChatScreen;
import org.universaltranslator.core.TranslationResult;
import org.universaltranslator.core.TranslationStatusLocalizer;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Ornithe Fabric 1.8.9-1.12.2 client: FontRenderer capture, settings, and keybinds. */
public final class UniversalTranslatorFabricClient implements ClientModInitializer {
    public static final String MOD_ID = "universal_translator";
    private static final long FAILURE_NOTIFICATION_COOLDOWN_MILLIS = 60_000L;
    private static final Logger LOGGER = Logger.getLogger(MOD_ID);
    private static boolean resendingTranslatedMessage;
    private static String lastRuntimeStatus = "";
    private static long nextFailureNotificationAt;

    @Override
    public void onInitializeClient() {
        try {
            FabricConfig config = FabricConfig.load(FabricLoader.getInstance().getConfigDir());
            FabricTranslationRuntime.initialize(config);
            LOGGER.info("MC Auto Translation Tool initialized; enabled=" + config.enabled);
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE,
                    "MC Auto Translation Tool configuration failed; translation remains disabled", exception);
            FabricTranslationRuntime.shutdown();
        }
    }

    public static void openSettingsFromKey() {
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof UniversalTranslatorConfigScreen
                || client.screen instanceof ChatScreen
                || TranslationRenderContext.isTextInput()) {
            return;
        }
        try {
            FabricConfig config = FabricConfig.load(FabricLoader.getInstance().getConfigDir());
            OrnitheClientAccess.openScreen(new UniversalTranslatorConfigScreen(client.screen, config));
        } catch (Exception exception) {
            LOGGER.log(Level.SEVERE, "Could not open MC Auto Translation Tool settings", exception);
        }
    }

    public static void toggleFromKey() {
        FabricConfig previous = null;
        boolean runtimeChanged = false;
        try {
            previous = FabricConfig.load(FabricLoader.getInstance().getConfigDir());
            FabricConfig updated = previous.withEnabled(!previous.enabled);
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            runtimeChanged = true;
            FabricTranslationRuntime.initialize(updated);
            lastRuntimeStatus = "";
            nextFailureNotificationAt = 0L;
            updated.save();
            OrnitheClientAccess.overlay("message.universal_translator.toggle",
                    OrnitheClientAccess.tr(updated.enabled
                            ? "value.universal_translator.enabled"
                            : "value.universal_translator.disabled"));
        } catch (Exception exception) {
            if (runtimeChanged && previous != null) {
                try {
                    FabricTranslationRuntime.initialize(previous);
                } catch (Exception restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
            }
            LOGGER.log(Level.SEVERE, "Could not toggle MC Auto Translation Tool", exception);
            OrnitheClientAccess.overlay("message.universal_translator.toggle_failed");
        }
    }

    public static boolean interceptOutgoingMessage(String message) {
        if (resendingTranslatedMessage || !FabricTranslationRuntime.shouldTranslateOutgoing(message)) {
            FabricTranslationRuntime.protectOutgoingMessage(message);
            return true;
        }
        OrnitheClientAccess.overlay("message.universal_translator.outgoing_translating");
        FabricTranslationRuntime.translateOutgoing(message).whenComplete((result, error) ->
                Minecraft.getInstance().execute(() -> {
                    sendCompletedMessage(message, result, error);
                    return null;
                }));
        return false;
    }

    private static void sendCompletedMessage(String original, TranslationResult result, Throwable error) {
        Minecraft client = Minecraft.getInstance();
        if (client.getNetworkHandler() == null) {
            OrnitheClientAccess.chat("message.universal_translator.outgoing_disconnected");
            return;
        }
        boolean failed = error != null || result == null || result.isFailure();
        String outgoing = failed || !result.isTranslated() ? original : result.getTranslatedText();
        boolean tooLong = outgoing.length() > OrnitheClientAccess.maximumChatLength();
        if (tooLong) {
            outgoing = original;
        }
        FabricTranslationRuntime.protectOutgoingMessage(outgoing);
        resendingTranslatedMessage = true;
        try {
            if (client.player != null) {
                client.player.sendChat(outgoing);
            }
        } finally {
            resendingTranslatedMessage = false;
        }
        if (failed) {
            OrnitheClientAccess.chat("message.universal_translator.outgoing_failed");
        } else if (tooLong) {
            OrnitheClientAccess.chat("message.universal_translator.outgoing_too_long");
        }
    }

    public static void notifyRuntimeStatus(boolean connected) {
        String current = connected ? FabricTranslationRuntime.status() : "";
        if (current == null) {
            current = "";
        }
        if (current.equals(lastRuntimeStatus)) {
            return;
        }
        lastRuntimeStatus = current;
        if (current.isEmpty()) {
            nextFailureNotificationAt = 0L;
            return;
        }
        String localized = TranslationStatusLocalizer.localize(current, OrnitheClientAccess::tr);
        if (TranslationStatusLocalizer.isFailure(current)) {
            long now = System.currentTimeMillis();
            if (now < nextFailureNotificationAt) {
                return;
            }
            nextFailureNotificationAt = now + FAILURE_NOTIFICATION_COOLDOWN_MILLIS;
            OrnitheClientAccess.chat("message.universal_translator.runtime_failed", localized);
        } else {
            nextFailureNotificationAt = 0L;
            OrnitheClientAccess.overlay("message.universal_translator.runtime_status", localized);
        }
    }
}
