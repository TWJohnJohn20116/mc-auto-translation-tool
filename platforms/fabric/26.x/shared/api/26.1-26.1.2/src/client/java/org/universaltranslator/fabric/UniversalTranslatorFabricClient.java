package org.universaltranslator.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
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
            return Component.translatable(key, arguments);
        }

        @Override
        protected void chat(String key, Object... arguments) {
            Minecraft.getInstance().gui.getChat().addClientSystemMessage(Component.translatable(key, arguments));
        }

        @Override
        protected void overlay(String key, Object... arguments) {
            Minecraft.getInstance().gui.setOverlayMessage(Component.translatable(key, arguments), false);
        }

        @Override
        protected String translateKey(String key, Object... arguments) {
            return Component.translatable(key, arguments).getString();
        }

        @Override
        protected Object currentScreen() {
            return Minecraft.getInstance().screen;
        }

        @Override
        protected void openSettings(Object parent, FabricConfig config) {
            Minecraft.getInstance().setScreen(new UniversalTranslatorConfigScreen((Screen) parent, config));
        }

        @Override
        protected boolean inWorld() {
            return Minecraft.getInstance().level != null;
        }

        @Override
        protected boolean isConnected() {
            return Minecraft.getInstance().getConnection() != null;
        }

        @Override
        protected void sendChat(String message) {
            Minecraft.getInstance().getConnection().sendChat(message);
        }

        @Override
        protected void executeOnClientThread(Runnable task) {
            Minecraft.getInstance().execute(task);
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
            ClientSendMessageEvents.ALLOW_CHAT.register(
                UniversalTranslatorFabricClient::interceptOutgoingMessage);
        ClientSendMessageEvents.CHAT.register(FabricTranslationRuntime::protectOutgoingMessage);
        }

        @Override
        protected boolean openSettingsKeyPressed() {
            return OPEN_SETTINGS.consumeClick();
        }

        @Override
        protected boolean toggleKeyPressed() {
            return TOGGLE_TRANSLATION.consumeClick();
        }
    }
}
