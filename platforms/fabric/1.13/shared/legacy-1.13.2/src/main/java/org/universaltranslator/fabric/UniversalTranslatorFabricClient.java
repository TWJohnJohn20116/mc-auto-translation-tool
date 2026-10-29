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
    /**
     * The body {@code FabricClientBootstrapBase} handed to {@link Impl#registerTickHook}.
     *
     * <p>1.13.2 has no Fabric API lifecycle events on the classpath (the build depends on the loader
     * only), so the tick is driven by {@code MinecraftMixin}, which calls {@link #tickFromMixin} from
     * {@code MinecraftClient.tick()V}. The hook used to be dropped on the floor, which silently
     * removed everything the tick body does — the join hint, and anything later added to
     * {@code onClientTick()} — from this generation.
     */
    private static volatile Runnable tickHook;
    /** The {@code FabricTranslationRuntime.shutdown()} runnable, run once on JVM exit. */
    private static volatile Runnable stopHook;
    /** Set once the runtime has been shut down, so the JVM hook cannot run the body twice. */
    private static volatile boolean shutdownRequested;

    private UniversalTranslatorFabricClient() {
    }

    /**
     * Runs one client tick.
     *
     * <p>Called from the {@code MinecraftClient} tick mixin on the client thread. A failing tick body
     * is logged and swallowed: an exception thrown out of the vanilla tick loop kills the game.
     */
    public static void tickFromMixin() {
        Runnable hook = tickHook;
        if (hook == null) {
            return;
        }
        try {
            hook.run();
        } catch (RuntimeException failure) {
            LOGGER.log(Level.SEVERE, "MC Auto Translation Tool tick failed", failure);
        }
    }

    /** Runs the runtime's shutdown once; safe to call from both the JVM hook and a manual close. */
    static void shutdownFromMixin() {
        if (shutdownRequested) {
            return;
        }
        shutdownRequested = true;
        Runnable hook = stopHook;
        if (hook == null) {
            return;
        }
        try {
            hook.run();
        } catch (RuntimeException failure) {
            LOGGER.log(Level.SEVERE, "MC Auto Translation Tool shutdown failed", failure);
        }
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
            // Kept instead of dropped: MinecraftMixin calls tickFromMixin() from MinecraftClient.tick()V,
            // so onClientTick() — the join hint included — really runs in this generation.
            tickHook = onTick;
        }

        @Override
        protected void registerStopHook(final Runnable onStop) {
            // This generation ships the loader only (see legacy.gradle), so there is no
            // ClientLifecycleEvents.CLIENT_STOPPING. A JVM shutdown hook runs on every exit path
            // (window close, F3+C, the launcher terminating the process) and needs no mapping.
            stopHook = onStop;
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(() -> shutdownFromMixin(),
                        "universal-translator-shutdown"));
            } catch (IllegalStateException alreadyShuttingDown) {
                shutdownFromMixin();
            }
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
