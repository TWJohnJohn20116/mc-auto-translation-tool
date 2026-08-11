package org.universaltranslator.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.universaltranslator.core.TranslationStatusLocalizer;

/** Fabric bootstrap. Capture mixins are added incrementally after mapping verification. */
public final class UniversalTranslatorFabricClient implements ClientModInitializer {
    public static final String MOD_ID = "universal_translator";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final KeyBinding OPEN_SETTINGS = KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                    "key.universal_translator.open_settings",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_U,
                    KeyBinding.Category.MISC));
    private static final KeyBinding TOGGLE_TRANSLATION = KeyBindingHelper.registerKeyBinding(
            new KeyBinding(
                    "key.universal_translator.toggle_translation",
                    InputUtil.Type.KEYSYM,
                    GLFW.GLFW_KEY_F8,
                    KeyBinding.Category.MISC));
    private static boolean connectedLastTick;
    private static int joinHintTicks = -1;
    private static String lastRuntimeStatus = "";

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
                        Text.translatable("message.universal_translator.join_hint"));
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
                    updated.save();
                    client.inGameHud.setOverlayMessage(
                            Text.translatable("message.universal_translator.toggle",
                                    Text.translatable(updated.enabled
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
                            Text.translatable("message.universal_translator.toggle_failed"), false);
                }
            }
            notifyRuntimeStatus(client, connected);
            while (OPEN_SETTINGS.wasPressed()) {
                if (client.currentScreen instanceof UniversalTranslatorConfigScreen) {
                    continue;
                }
                try {
                    FabricConfig config = FabricConfig.load(FabricLoader.getInstance().getConfigDir());
                    client.setScreen(new UniversalTranslatorConfigScreen(client.currentScreen, config));
                } catch (Exception exception) {
                    LOGGER.error("Could not open MC Auto Translation Tool settings", exception);
                }
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> FabricTranslationRuntime.shutdown());
        ClientSendMessageEvents.CHAT.register(FabricTranslationRuntime::protectOutgoingMessage);
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
            return;
        }
        String localized = TranslationStatusLocalizer.localize(current,
                UniversalTranslatorFabricClient::tr);
        if (isFailureStatus(current)) {
            client.inGameHud.getChatHud().addMessage(
                    Text.translatable("message.universal_translator.runtime_failed", localized));
        } else {
            client.inGameHud.setOverlayMessage(
                    Text.translatable("message.universal_translator.runtime_status", localized), false);
        }
    }

    private static boolean isFailureStatus(String status) {
        return TranslationStatusLocalizer.isFailure(status);
    }

    private static String tr(String key, Object... arguments) {
        return Text.translatable(key, arguments).getString();
    }
}
