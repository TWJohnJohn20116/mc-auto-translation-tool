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
                client.inGameHud.getChatHud().addMessage(Text.literal(
                        "\u00a7b[MC 自动翻译工具] \u00a7f按 U 打开控制面板；按 F8 一键开关翻译。"));
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
                    updated.save();
                    client.inGameHud.setOverlayMessage(
                            Text.literal("MC 自动翻译工具: " + (updated.enabled ? "已开启" : "已关闭")),
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
                            Text.literal("MC 自动翻译工具: 切换失败"), false);
                }
            }
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
}
