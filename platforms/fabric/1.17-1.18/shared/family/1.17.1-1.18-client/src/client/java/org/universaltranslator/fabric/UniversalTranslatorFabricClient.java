package org.universaltranslator.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.TranslatableText;
import org.lwjgl.glfw.GLFW;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

/** Fabric bootstrap. Capture mixins are added incrementally after mapping verification. */
public final class UniversalTranslatorFabricClient implements ClientModInitializer {
    public static final String MOD_ID = "universal_translator";
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
    private static final Impl INSTANCE = new Impl();

    private UniversalTranslatorFabricClient() {
    }

    @Override
    public void onInitializeClient() {
        INSTANCE.onInitializeClient();
    }

    /**
     * Intercepts an outgoing chat line; kept public because the chat mixin of the generations that
     * predate {@code ClientSendMessageEvents} calls it directly.
     */
    public static boolean interceptOutgoingMessage(String message) {
        return INSTANCE.interceptOutgoingMessage(message);
    }

    /** Opens the settings screen on top of {@code parent}. */
    public static void openSettingsScreen(Screen parent) {
        INSTANCE.openSettingsFor(parent);
    }

    private static final class Impl extends FabricClientBootstrapBase {
        @Override
        protected void info(String message) {
            LOGGER.info(message);
        }

        @Override
        protected void error(String message, Throwable failure) {
            LOGGER.error(message, failure);
        }

        @Override
        protected Object text(String key, Object... arguments) {
            return new TranslatableText(key, arguments);
        }

        @Override
        protected void chat(String key, Object... arguments) {
            MinecraftClient.getInstance().inGameHud.getChatHud().addMessage(new TranslatableText(key, arguments));
        }

        @Override
        protected void overlay(String key, Object... arguments) {
            MinecraftClient.getInstance().inGameHud.setOverlayMessage(new TranslatableText(key, arguments), false);
        }

        @Override
        protected String translateKey(String key, Object... arguments) {
            return new TranslatableText(key, arguments).getString();
        }

        @Override
        protected Object currentScreen() {
            return MinecraftClient.getInstance().currentScreen;
        }

        @Override
        protected void openSettings(Object parent, FabricConfig config) {
            MinecraftClient.getInstance().setScreen(new UniversalTranslatorConfigScreen((Screen) parent, config));
        }

        @Override
        protected boolean inWorld() {
            return MinecraftClient.getInstance().world != null;
        }

        @Override
        protected boolean isConnected() {
            return MinecraftClient.getInstance().getNetworkHandler() != null;
        }

        @Override
        protected void sendChat(String message) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                client.player.sendChatMessage(message);
            }
        }

        @Override
        protected void executeOnClientThread(Runnable task) {
            MinecraftClient.getInstance().execute(task);
        }

        @Override
        protected void registerTickHook(Runnable onTick) {
            ClientTickEvents.END_CLIENT_TICK.register(client -> onTick.run());
        }

        @Override
        protected void registerStopHook(Runnable onStop) {
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> onStop.run());
        }

        @Override
        protected void registerSendMessageHooks() {
            // Outgoing chat is intercepted by a mixin here, which calls the public static below.
        }

        @Override
        protected boolean openSettingsKeyPressed() {
            return OPEN_SETTINGS.wasPressed();
        }

        @Override
        protected boolean toggleKeyPressed() {
            return TOGGLE_TRANSLATION.wasPressed();
        }
    }
}
