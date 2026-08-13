package org.universaltranslator.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import org.lwjgl.glfw.GLFW;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;
import org.universaltranslator.core.TranslationResult;
import org.universaltranslator.core.TranslationStatusLocalizer;

/** Fabric bootstrap. Capture mixins are added incrementally after mapping verification. */
public final class UniversalTranslatorFabricClient implements ClientModInitializer {
    public static final String MOD_ID = "universal_translator";
    private static final long FAILURE_NOTIFICATION_COOLDOWN_MILLIS = 60_000L;
    private static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    private static final KeyBinding OPEN_SETTINGS = KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                    "key.universal_translator.open_settings",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_U,
                    "key.categories.misc"));
    private static final KeyBinding TOGGLE_TRANSLATION = KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                    "key.universal_translator.toggle_translation",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_F8,
                    "key.categories.misc"));
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
            boolean connected = client.world != null && client.getNetworkHandler() != null;
            if (connected && !connectedLastTick) {
                joinHintTicks = 60;
            } else if (!connected) {
                joinHintTicks = -1;
            }
            connectedLastTick = connected;
            if (connected && joinHintTicks > 0 && --joinHintTicks == 0) {
                client.inGameHud.getChatHud().addMessage(
                        new TranslatableText("message.universal_translator.join_hint"));
            }
            while (TOGGLE_TRANSLATION.wasPressed()) {
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
                    client.inGameHud.setOverlayMessage(
                            new TranslatableText("message.universal_translator.toggle",
                                    new TranslatableText(updated.enabled
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
                    client.inGameHud.setOverlayMessage(
                            new TranslatableText("message.universal_translator.toggle_failed"), false);
                }
            }
            notifyRuntimeStatus(client, connected);
            while (OPEN_SETTINGS.wasPressed()) {
                if (client.currentScreen instanceof UniversalTranslatorConfigScreen) {
                    continue;
                }
                try {
                    FabricConfig config = FabricConfig.load(FabricLoader.getInstance().getConfigDir());
                    client.openScreen(new UniversalTranslatorConfigScreen(client.currentScreen, config));
                } catch (Exception exception) {
                    LOGGER.error("Could not open MC Auto Translation Tool settings", exception);
                }
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> FabricTranslationRuntime.shutdown());
    }

    public static boolean interceptOutgoingMessage(String message) {
        if (resendingTranslatedMessage || !FabricTranslationRuntime.shouldTranslateOutgoing(message)) {
            FabricTranslationRuntime.protectOutgoingMessage(message);
            return true;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        client.inGameHud.setOverlayMessage(
                new TranslatableText("message.universal_translator.outgoing_translating"), false);
        FabricTranslationRuntime.translateOutgoing(message).whenComplete((result, error) ->
                client.execute(() -> sendCompletedMessage(client, message, result, error)));
        return false;
    }

    private static void sendCompletedMessage(
            MinecraftClient client,
            String original,
            TranslationResult result,
            Throwable error
    ) {
        if (client.getNetworkHandler() == null) {
            client.inGameHud.getChatHud().addMessage(
                    new TranslatableText("message.universal_translator.outgoing_disconnected"));
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
            if (client.player != null) {
                client.player.sendChatMessage(outgoing);
            }
        } finally {
            resendingTranslatedMessage = false;
        }
        if (failed) {
            client.inGameHud.getChatHud().addMessage(
                    new TranslatableText("message.universal_translator.outgoing_failed"));
        } else if (tooLong) {
            client.inGameHud.getChatHud().addMessage(
                    new TranslatableText("message.universal_translator.outgoing_too_long"));
        }
    }
    private static void notifyRuntimeStatus(net.minecraft.client.MinecraftClient client, boolean connected) {
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
            client.inGameHud.getChatHud().addMessage(
                    new TranslatableText("message.universal_translator.runtime_failed", localized));
        } else {
            nextFailureNotificationAt = 0L;
            client.inGameHud.setOverlayMessage(
                    new TranslatableText("message.universal_translator.runtime_status", localized), false);
        }
    }

    private static boolean isFailureStatus(String status) {
        return TranslationStatusLocalizer.isFailure(status);
    }

    private static String tr(String key, Object... arguments) {
        return new TranslatableText(key, arguments).getString();
    }
}
