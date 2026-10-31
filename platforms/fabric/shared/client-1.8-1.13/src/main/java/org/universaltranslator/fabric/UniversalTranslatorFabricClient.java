package org.universaltranslator.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Ornithe Fabric 1.8.9-1.13.1 client: same seams, the pre-1.13 screen and chat API. */
public final class UniversalTranslatorFabricClient implements ClientModInitializer {
    public static final String MOD_ID = "universal_translator";
    private static final Logger LOGGER = Logger.getLogger(MOD_ID);
    private static final Impl INSTANCE = new Impl();
    /**
     * The body {@code FabricClientBootstrapBase} handed to {@link Impl#registerTickHook}.
     *
     * <p>These generations have no Fabric API lifecycle events on the classpath (the build depends on
     * the loader only), so the tick is driven by a {@code Minecraft} mixin calling
     * {@link #tickFromMixin()} from {@code tick()V}. The hook used to be dropped on the floor, which
     * silently removed everything the tick body does — the join hint, and anything later added to
     * {@code onClientTick()} — from 1.8.9 through 1.13.1, while 1.13.2 (which keeps its own copy of
     * this class) already ran it.
     */
    private static volatile Runnable tickHook;
    /** The {@code FabricTranslationRuntime.shutdown()} runnable, run once on JVM exit. */
    private static volatile Runnable stopHook;
    /** Set once the runtime has been shut down, so the JVM hook cannot run the body twice. */
    private static volatile boolean shutdownRequested;
    /**
     * How long the JVM shutdown hook waits for the teardown before letting the process exit.
     *
     * <p>The runtime's own close settles open requests and waits up to five seconds for a worker
     * parked in a provider read, then flushes the whole cache store; running that inline on the hook
     * made "close the game" wait for all of it with no upper bound.
     */
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
        }
    }

    /** Runs the runtime's shutdown once; safe to call from both the JVM hook and a manual close. */
    static void shutdownFromMixin() {
        if (shutdownRequested) {
            return;
        }
        shutdownRequested = true;
        // The screens' shared pool is dropped here so a closed game does not keep two daemon threads
        // (and whatever request they are waiting on) alive.
        ScreenWorkers.shutdown();
        // Dropped here, not just flagged: the tick mixin keeps calling tickFromMixin() on every
        // tick after this point, and a hook left in place kept resetting the render context and
        // re-running the status notifier against a runtime with no session.
        tickHook = null;
        Runnable hook = stopHook;
        stopHook = null;
        if (hook == null) {
            return;
        }
        // Bounded wait instead of running the teardown inline: it closes the coordinator and then
        // flushes the whole cache store, which on the JVM shutdown hook made "close the game" wait
        // for all of it with no upper bound. Past the timeout the store's own flush path covers
        // whatever did not get written.
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
            Minecraft.getInstance().execute(() -> {
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
            // Kept instead of dropped: the Minecraft tick mixin calls tickFromMixin(), so
            // onClientTick() — the render-context reset, the join hint and the runtime status — really
            // runs in these generations. Same shape as the 1.13.2 copy of this class.
            tickHook = onTick;
        }

        @Override
        protected void registerStopHook(final Runnable onStop) {
            // The loader is the only dependency here (no Fabric API lifecycle), so there is no
            // ClientLifecycleEvents.CLIENT_STOPPING. A JVM shutdown hook covers every exit path and
            // needs no mapping.
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
