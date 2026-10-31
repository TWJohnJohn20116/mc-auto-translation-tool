package org.universaltranslator.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Ornithe Fabric 1.3.1-1.8.8 client: the generation everything older mirrors. */
public final class UniversalTranslatorFabricClient implements ClientModInitializer {
    public static final String MOD_ID = "universal_translator";
    private static final Logger LOGGER = Logger.getLogger(MOD_ID);
    private static final Impl INSTANCE = new Impl();
    /**
     * The body {@code FabricClientBootstrapBase} handed to {@link Impl#registerTickHook}.
     *
     * <p>1.3.1-1.8.8 has no Fabric API lifecycle events on the classpath (the build depends on the
     * loader only), so the tick is driven by {@code MinecraftMixin}, which calls
     * {@link #tickFromMixin()} from {@code Minecraft.tick()V}. The hook used to be dropped on the
     * floor, which silently removed everything the tick body does — the join hint, the render-context
     * reset and the runtime status line — from this generation.
     */
    private static volatile Runnable tickHook;
    /** The {@code FabricTranslationRuntime.shutdown()} runnable, run once on JVM exit. */
    private static volatile Runnable stopHook;
    /** Set once the runtime has been shut down, so the JVM hook cannot run the body twice. */
    private static volatile boolean shutdownRequested;
    /** Upper bound on how long the JVM shutdown hook waits for the teardown before letting it exit. */
    private static final long SHUTDOWN_JOIN_MILLIS = 1500L;

    private UniversalTranslatorFabricClient() {
    }

    /**
     * Runs one client tick.
     *
     * <p>Called from the {@code Minecraft} tick mixin on the client thread. A failing tick body is
     * logged and swallowed: an exception thrown out of the vanilla tick loop kills the game.
     */
    public static void tickFromMixin() {
        Runnable hook = tickHook;
        if (hook == null || shutdownRequested) {
            return;
        }
        try {
            hook.run();
        } catch (RuntimeException failure) {
            LOGGER.log(Level.SEVERE, "MC Auto Translation Tool tick failed", failure);
        } catch (Error failure) {
            // This generation's tick injection point is the only thing keeping the hotkeys and the
            // client task queue alive, so an Error from a drifted mapping is logged instead of
            // being allowed to take the game down.
            LOGGER.log(Level.SEVERE, "MC Auto Translation Tool tick failed", failure);
        }
    }

    /** Runs the runtime's shutdown once; safe to call from both the JVM hook and a manual close. */
    static void shutdownFromMixin() {
        if (shutdownRequested) {
            return;
        }
        shutdownRequested = true;
        // Dropped here, not just flagged: the tick mixin keeps calling tickFromMixin() on every tick
        // after this point, and a hook left in place kept resetting the render context against a
        // runtime with no session.
        tickHook = null;
        // The screens' shared pool is dropped here so a closed game does not keep two daemon threads
        // (and whatever request they are waiting on) alive.
        ScreenWorkers.shutdown();
        Runnable hook = stopHook;
        stopHook = null;
        if (hook == null) {
            return;
        }
        // Bounded wait instead of running the teardown inline: it closes the coordinator (settling
        // open requests, shutting its workers down and waiting up to 5 s for them) and then flushes
        // the whole cache store, so running it on the JVM shutdown hook would make "close the game"
        // wait for all of that with no upper bound. A teardown still running after the join is cut
        // short by the JVM exiting, and the store's own flush path covers what did not get written.
        final Thread teardown = new Thread(hook, "universal-translator-stop");
        teardown.setDaemon(false);
        teardown.start();
        try {
            teardown.join(SHUTDOWN_JOIN_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onInitializeClient() {
        // A fresh initialization (a client that re-enters after a full shutdown) must be able to
        // shut down again; the flag describes one stopped hook, not the whole process.
        shutdownRequested = false;
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
            return Minecraft.getInstance().screen;
        }

        @Override
        protected void openSettings(Object parent, FabricConfig config) {
            OrnitheClientAccess.openScreen(new UniversalTranslatorConfigScreen((Screen) parent, config));
        }

        @Override
        protected boolean inWorld() {
            // The mixins already gate on the world; the tick body is never used here.
            return true;
        }

        @Override
        protected boolean isConnected() {
            return Minecraft.getInstance().getNetworkHandler() != null;
        }

        @Override
        protected void sendChat(String message) {
            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.sendChat(message);
            }
        }

        @Override
        protected void executeOnClientThread(Runnable task) {
            OrnitheClientAccess.execute(task);
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
            // Kept instead of dropped: MinecraftMixin calls tickFromMixin() from Minecraft.tick()V, so
            // onClientTick() — the render-context reset, the join hint and the runtime status — really
            // runs in this generation. Same shape as the 1.8.9-1.13.1 copy of this class.
            tickHook = onTick;
        }

        @Override
        protected void registerStopHook(final Runnable onStop) {
            // The loader is the only dependency here (no Fabric API lifecycle), so there is no
            // ClientLifecycleEvents.CLIENT_STOPPING. A JVM shutdown hook covers every exit path and
            // needs no mapping.
            stopHook = onStop;
            try {
                Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                    @Override
                    public void run() {
                        shutdownFromMixin();
                    }
                }, "universal-translator-shutdown"));
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
