package org.universaltranslator.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Ornithe Fabric 1.13.2 client: MinecraftClient renaming lands here. */
public final class UniversalTranslatorFabricClient implements ClientModInitializer {
    public static final String MOD_ID = "universal_translator";
    private static final Logger LOGGER = Logger.getLogger(MOD_ID);
    private static final Impl INSTANCE = new Impl();

    private UniversalTranslatorFabricClient() {
    }

    @Override
    public void onInitializeClient() {
        INSTANCE.onInitializeClient();
    }

    /** The settings key, handed over by the screen mixin. */
    public static void openSettingsFromKey() {
        INSTANCE.openSettingsFromKey();
    }

    /** The toggle key, handed over by the keyboard mixin. */
    public static void toggleFromKey() {
        INSTANCE.toggleFromKey();
    }

    /** Intercepts an outgoing chat line; called by the chat mixin. */
    public static boolean interceptOutgoingMessage(String message) {
        return INSTANCE.interceptOutgoingMessage(message);
    }

    /** Reports the runtime status; called by the world-join mixin. */
    public static void notifyRuntimeStatus(boolean connected) {
        INSTANCE.notifyRuntimeStatus(connected);
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
            LOGGER.log(Level.SEVERE, message, failure);
        }

        @Override
        protected Object text(String key, Object... arguments) {
            return OrnitheClientAccess.tr(key, arguments);
        }

        @Override
        protected void chat(String key, Object... arguments) {
            OrnitheClientAccess.chat(key, arguments);
        }

        @Override
        protected void overlay(String key, Object... arguments) {
            OrnitheClientAccess.overlay(key, arguments);
        }

        @Override
        protected String translateKey(String key, Object... arguments) {
            return OrnitheClientAccess.tr(key, arguments);
        }

        @Override
        protected Object currentScreen() {
            return MinecraftClient.getInstance().currentScreen;
        }

        @Override
        protected void openSettings(Object parent, FabricConfig config) {
            OrnitheClientAccess.setScreen(new UniversalTranslatorConfigScreen((Screen) parent, config));
        }

        @Override
        protected boolean inWorld() {
            // The mixins already gate on the world; the tick body is never used here.
            return true;
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
            MinecraftClient.getInstance().execute(() -> {
                task.run();
                return null;
            });
        }

        @Override
        protected int maximumChatLength() {
            return OrnitheClientAccess.maximumChatLength();
        }

        @Override
        protected boolean isChatScreen(Object screen) {
            return screen instanceof ChatScreen;
        }

        @Override
        protected void registerTickHook(Runnable onTick) {
            // The tick is driven by OrnitheClientAccess from the tick mixin.
        }

        @Override
        protected void registerStopHook(Runnable onStop) {
            // Ornithe has no client-stopping callback in this generation.
        }

        @Override
        protected void registerSendMessageHooks() {
            // Outgoing chat is intercepted by a mixin, which calls the public static above.
        }

        @Override
        protected boolean openSettingsKeyPressed() {
            return false;
        }

        @Override
        protected boolean toggleKeyPressed() {
            return false;
        }
    }
}
