package org.universaltranslator.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.universaltranslator.core.TranslationResult;
import org.universaltranslator.core.TranslationStatusLocalizer;

/** Fabric bootstrap. Capture mixins are added incrementally after mapping verification. */
public final class UniversalTranslatorFabricClient implements ClientModInitializer {
    public static final String MOD_ID = "universal_translator";
    private static final long FAILURE_NOTIFICATION_COOLDOWN_MILLIS = 60_000L;
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final KeyMapping OPEN_SETTINGS = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.universal_translator.open_settings",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_U,
                    KeyMapping.Category.MISC));
    private static final KeyMapping TOGGLE_TRANSLATION = KeyMappingHelper.registerKeyMapping(
            new KeyMapping(
                    "key.universal_translator.toggle_translation",
                    InputConstants.Type.KEYSYM,
                    GLFW.GLFW_KEY_F8,
                    KeyMapping.Category.MISC));
    private static boolean connectedLastTick;
    private static int joinHintTicks = -1;
    private static String lastRuntimeStatus = "";
    private static long nextFailureNotificationAt;
    private static boolean resendingTranslatedMessage;

    @Override
    public void onInitializeClient() {
        try {
            FabricConfig config = FabricConfig.load(FabricLoader.getInstance().getConfigDir());
            FabricTranslationRuntime.initialize(config);
            LOGGER.info("MC Auto Translation Tool initialized; enabled={}", config.enabled);
        } catch (Exception exception) {
            LOGGER.error("MC Auto Translation Tool configuration failed; translation remains disabled", exception);
            FabricTranslationRuntime.shutdown();
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean connected = client.level != null && client.getConnection() != null;
            if (connected && !connectedLastTick) {
                joinHintTicks = 60;
            } else if (!connected) {
                joinHintTicks = -1;
            }
            connectedLastTick = connected;
            if (connected && joinHintTicks > 0 && --joinHintTicks == 0) {
                client.gui.hud.getChat().addClientSystemMessage(
                        Component.translatable("message.universal_translator.join_hint"));
            }
            while (TOGGLE_TRANSLATION.consumeClick()) {
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
                    client.gui.hud.setOverlayMessage(
                            Component.translatable("message.universal_translator.toggle",
                                    Component.translatable(updated.enabled
                                            ? "value.universal_translator.enabled"
                                            : "value.universal_translator.disabled")),
                            false);
                } catch (Exception exception) {
                    if (runtimeChanged && previous != null) {
                        try {
                            FabricTranslationRuntime.initialize(previous);
                        } catch (Exception restoreFailure) {
                            exception.addSuppressed(restoreFailure);
                        }
                    }
                    LOGGER.error("Could not toggle MC Auto Translation Tool", exception);
                    client.gui.hud.setOverlayMessage(
                            Component.translatable("message.universal_translator.toggle_failed"), false);
                }
            }
            notifyRuntimeStatus(client, connected);
            while (OPEN_SETTINGS.consumeClick()) {
                if (client.gui.screen() instanceof UniversalTranslatorConfigScreen) {
                    continue;
                }
                try {
                    FabricConfig config = FabricConfig.load(FabricLoader.getInstance().getConfigDir());
                    client.gui.setScreen(new UniversalTranslatorConfigScreen(client.gui.screen(), config));
                } catch (Exception exception) {
                    LOGGER.error("Could not open MC Auto Translation Tool settings", exception);
                }
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> FabricTranslationRuntime.shutdown());
        ClientSendMessageEvents.ALLOW_CHAT.register(
                UniversalTranslatorFabricClient::interceptOutgoingMessage);
        ClientSendMessageEvents.CHAT.register(FabricTranslationRuntime::protectOutgoingMessage);
    }

    private static boolean interceptOutgoingMessage(String message) {
        if (resendingTranslatedMessage || !FabricTranslationRuntime.shouldTranslateOutgoing(message)) {
            FabricTranslationRuntime.protectOutgoingMessage(message);
            return true;
        }
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        client.gui.hud.setOverlayMessage(
                Component.translatable("message.universal_translator.outgoing_translating"), false);
        FabricTranslationRuntime.translateOutgoing(message).whenComplete((result, error) ->
                client.execute(() -> sendCompletedMessage(client, message, result, error)));
        return false;
    }

    private static void sendCompletedMessage(
            net.minecraft.client.Minecraft client,
            String original,
            TranslationResult result,
            Throwable error
    ) {
        if (client.getConnection() == null) {
            client.gui.hud.getChat().addClientSystemMessage(
                    Component.translatable("message.universal_translator.outgoing_disconnected"));
            return;
        }
        boolean failed = error != null || result == null || result.isFailure();
        String outgoing = failed || !result.isTranslated()
                ? original : result.getTranslatedText();
        boolean tooLong = outgoing.length() > 256;
        if (tooLong) {
            outgoing = original;
        }
        FabricTranslationRuntime.protectOutgoingMessage(outgoing);
        resendingTranslatedMessage = true;
        try {
            client.getConnection().sendChat(outgoing);
        } finally {
            resendingTranslatedMessage = false;
        }
        if (failed) {
            client.gui.hud.getChat().addClientSystemMessage(
                    Component.translatable("message.universal_translator.outgoing_failed"));
        } else if (tooLong) {
            client.gui.hud.getChat().addClientSystemMessage(
                    Component.translatable("message.universal_translator.outgoing_too_long"));
        }
    }

    private static void notifyRuntimeStatus(net.minecraft.client.Minecraft client, boolean connected) {
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
        String localized = TranslationStatusLocalizer.localize(current,
                UniversalTranslatorFabricClient::tr);
        if (isFailureStatus(current)) {
            long now = System.currentTimeMillis();
            if (now < nextFailureNotificationAt) {
                return;
            }
            nextFailureNotificationAt = now + FAILURE_NOTIFICATION_COOLDOWN_MILLIS;
            client.gui.hud.getChat().addClientSystemMessage(
                    Component.translatable("message.universal_translator.runtime_failed", localized));
        } else {
            nextFailureNotificationAt = 0L;
            client.gui.hud.setOverlayMessage(
                    Component.translatable("message.universal_translator.runtime_status", localized), false);
        }
    }

    private static boolean isFailureStatus(String status) {
        return TranslationStatusLocalizer.isFailure(status);
    }

    private static String tr(String key, Object... arguments) {
        return Component.translatable(key, arguments).getString();
    }
}
