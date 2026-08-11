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

/** Fabric bootstrap. Capture mixins are added incrementally after mapping verification. */
public final class UniversalTranslatorFabricClient implements ClientModInitializer {
    public static final String MOD_ID = "universal_translator";
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
                client.gui.getChat().addClientSystemMessage(Component.literal(
                        "\u00a7b[MC 自动翻译工具] \u00a7f按 U 打开控制面板；按 F8 一键开关翻译。"));
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
                    updated.save();
                    client.gui.setOverlayMessage(
                            Component.literal("MC 自动翻译工具: " + (updated.enabled ? "已开启" : "已关闭")),
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
                    client.gui.setOverlayMessage(
                            Component.literal("MC 自动翻译工具: 切换失败"), false);
                }
            }
            notifyRuntimeStatus(client, connected);
            while (OPEN_SETTINGS.consumeClick()) {
                if (client.screen instanceof UniversalTranslatorConfigScreen) {
                    continue;
                }
                try {
                    FabricConfig config = FabricConfig.load(FabricLoader.getInstance().getConfigDir());
                    client.setScreen(new UniversalTranslatorConfigScreen(client.screen, config));
                } catch (Exception exception) {
                    LOGGER.error("Could not open MC Auto Translation Tool settings", exception);
                }
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> FabricTranslationRuntime.shutdown());
        ClientSendMessageEvents.CHAT.register(FabricTranslationRuntime::protectOutgoingMessage);
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
            return;
        }
        if (isFailureStatus(current)) {
            client.gui.getChat().addClientSystemMessage(Component.literal(
                    "\u00a7c[MC 自动翻译工具] " + current));
        } else {
            client.gui.setOverlayMessage(
                    Component.literal("MC 自动翻译工具: " + current), false);
        }
    }

    private static boolean isFailureStatus(String status) {
        return status.startsWith("翻译失败") || status.startsWith("离线翻译失败");
    }
}
