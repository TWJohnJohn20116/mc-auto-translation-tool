package org.universaltranslator.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.universaltranslator.core.LogMessages;
import org.universaltranslator.core.ShutdownBudget;
import org.universaltranslator.core.TranslationResult;
import org.universaltranslator.core.TranslationStatusLocalizer;

/**
 * The client bootstrap every Fabric tree shares.
 *
 * <p>This class used to exist fifteen times. The bodies were the same control flow — load the
 * config, keep one tick handler, notify the runtime status, intercept the outgoing chat line and
 * re-send the translation — and the copies differed only in how they reached the game: the screen
 * accessor, the chat HUD, the overlay, the connection, the key binding, the text factory, the
 * logger. Those are the seam methods below; each generation implements them and nothing else.
 *
 * <p>Nothing here mentions a mapped type. {@code Screen}, {@code Text}/{@code Component}, the
 * {@code KeyMapping}/{@code KeyBinding} pair and the client class are all handled by the subclass,
 * which is what lets one file compile against 1.14 yarn, 1.21.9 yarn, 26.x Mojmap and Ornithe.
 *
 * <p>The bootstrap contract stays exactly as it was: a subclass is a {@code ClientModInitializer}
 * whose {@code onInitializeClient()} calls {@link #onInitializeClient()} from this class through
 * its own instance, and whose public statics forward to the same instance. Call sites — mixins,
 * screens, the HUD overlay — are untouched.
 */
public abstract class FabricClientBootstrapBase implements ClientModInitializer {
    private static final long FAILURE_NOTIFICATION_COOLDOWN_MILLIS = 60_000L;
    /**
     * The thread a blocked session close runs on.
     *
     * <p>Also the marker the stop hook reads: five generations already start this thread themselves
     * and hand it the hook, so a hook that finds itself on it runs the close inline instead of
     * nesting a second one.
     */
    private static final String TEARDOWN_THREAD_NAME = "universal-translator-stop";

    private boolean connectedLastTick;
    private int joinHintTicks = -1;
    private String lastRuntimeStatus = "";
    private long nextFailureNotificationAt;
    private boolean resendingTranslatedMessage;
    /** Raw runtime status the two fields below were derived from; see {@link #notifyRuntimeStatus}. */
    private String localizedStatusSource;
    private String localizedStatusText = "";
    private boolean localizedStatusFailure;

    protected FabricClientBootstrapBase() {
    }

    // ---------------------------------------------------------------- logging

    /** Informational line; the caller has already formatted any placeholders. */
    protected abstract void info(String message);

    /** Failure line with its cause. */
    protected abstract void error(String message, Throwable failure);

    // ------------------------------------------------------------------- text

    /** A translatable text object, used as an argument to another translatable. */
    protected abstract Object text(String key, Object... arguments);

    /** Appends a translatable line to the chat HUD. */
    protected abstract void chat(String key, Object... arguments);

    /** Shows a translatable line as the overlay (action-bar) message. */
    protected abstract void overlay(String key, Object... arguments);

    /** Resolves a translatable key to plain text on the current thread. */
    protected abstract String translateKey(String key, Object... arguments);

    // ----------------------------------------------------------------- client

    /** The screen the client is showing, or {@code null}. */
    protected abstract Object currentScreen();

    /** Opens the settings screen on top of {@code parent}. */
    protected abstract void openSettings(Object parent, FabricConfig config);

    /** True when a world is loaded. */
    protected abstract boolean inWorld();

    /** True when a server connection exists. */
    protected abstract boolean isConnected();

    /** Sends one already translated chat line. */
    protected abstract void sendChat(String message);

    /** Runs {@code task} on the client thread. */
    protected abstract void executeOnClientThread(Runnable task);

    // ----------------------------------------------------------- registration

    /** Registers the per-tick callback, if this generation has one. */
    protected abstract void registerTickHook(Runnable onTick);

    /** Registers the client-stopping callback, if this generation has one. */
    protected abstract void registerStopHook(Runnable onStop);

    /** Registers the outgoing-chat hooks, if this generation has them. */
    protected abstract void registerSendMessageHooks();

    /** True when the settings key was pressed this tick (consumed here). */
    protected abstract boolean openSettingsKeyPressed();

    /** True when the toggle key was pressed this tick (consumed here). */
    protected abstract boolean toggleKeyPressed();

    /**
     * The names {@code ChatScreen} is known by, one per naming scheme this mod ships against.
     *
     * <p>Answered by class rather than {@code instanceof}: this base is compiled once for every
     * generation from 1.14 to 26.3, and a compile-time {@code ChatScreen} reference would tie it to
     * one generation's package. A bare name comparison is not enough either, because the game remaps
     * the class: {@code net.minecraft.class_408} in a production 1.14+ client, the yarn name in a
     * development client, and 26.x's Mojmap package. Every shipped generation overrides
     * {@link #isChatScreen(Object)} with an exact {@code instanceof}, but the generation that forgot
     * to — eight of the thirteen client layers — left the chat-screen guard answering {@code false}
     * for the whole session, so the list below is resolved once and answers for them.
     *
     * <p>Used by {@link #openSettingsFor(Object)}: while the chat screen is open, the settings key
     * belongs to the chat box, not to this mod.
     */
    private static final String[] CHAT_SCREEN_TYPES = {
            "net.minecraft.client.gui.screen.ChatScreen",
            "net.minecraft.client.gui.screens.ChatScreen",
            "net.minecraft.class_408",
    };
    /**
     * The resolved form of {@link #CHAT_SCREEN_TYPES}; {@code null} until the first lookup.
     *
     * <p>Resolved without initializing the classes and cached for the life of the process: the
     * entries are {@code Class} objects the class loader already holds, so there is no per-session
     * state to release.
     */
    private static volatile Class<?>[] chatScreenTypes;

    private static Class<?>[] chatScreenTypes() {
        Class<?>[] resolved = chatScreenTypes;
        if (resolved != null) {
            return resolved;
        }
        java.util.List<Class<?>> found = new java.util.ArrayList<Class<?>>(CHAT_SCREEN_TYPES.length);
        ClassLoader loader = FabricClientBootstrapBase.class.getClassLoader();
        for (String name : CHAT_SCREEN_TYPES) {
            try {
                found.add(Class.forName(name, false, loader));
            } catch (ClassNotFoundException absent) {
                // Not one of this tree's names: try the next one.
            } catch (LinkageError unreadable) {
                // A name that resolves but cannot be linked is equally not an answer here.
            }
        }
        resolved = found.toArray(new Class<?>[found.size()]);
        chatScreenTypes = resolved;
        return resolved;
    }

    /**
     * True when {@code screen} is a chat screen.
     *
     * <p>Tries the resolved {@code ChatScreen} classes first and keeps the name comparison as the
     * last resort for a tree where none of them resolves. Used by
     * {@link #openSettingsFor(Object)}: while the chat screen is open, the settings key belongs to
     * the chat box, not to this mod.
     */
    protected boolean isChatScreen(Object screen) {
        if (screen == null) {
            return false;
        }
        for (Class<?> type : chatScreenTypes()) {
            if (type.isInstance(screen)) {
                return true;
            }
        }
        String name = screen.getClass().getName();
        return name.endsWith(".ChatScreen") || name.endsWith(".ChatScreenBase");
    }

    /** Longest accepted chat payload; the Ornithe trees read it from the game instead. */
    protected int maximumChatLength() {
        return 256;
    }

    // -------------------------------------------------------------- lifecycle

    @Override
    public final void onInitializeClient() {
        try {
            FabricConfig config = FabricConfig.load(FabricLoader.getInstance().getConfigDir());
            String corruptBackup = FabricConfig.lastCorruptionBackup();
            if (corruptBackup != null) {
                // The settings themselves were replaced by the defaults, so this has to be said out
                // loud: the player's own values are in the file named here, and without this line
                // nothing would ever mention that they had been moved.
                info("MC Auto Translation Tool configuration was damaged; the defaults are in use and"
                        + " the previous file was kept as " + corruptBackup);
            }
            FabricTranslationRuntime.initialize(config);
            info("MC Auto Translation Tool initialized; enabled=" + config.enabled);
        } catch (Exception exception) {
            error("MC Auto Translation Tool configuration failed; translation remains disabled", exception);
            FabricTranslationRuntime.shutdown();
        }
        registerTickHook(new Runnable() {
            @Override
            public void run() {
                onClientTick();
            }
        });
        registerStopHook(new Runnable() {
            @Override
            public void run() {
                // Flush a queued settings write first: the teardown below can spend seconds closing
                // the session, and a write queued behind it would never be finished.
                FabricTranslationRuntimeBase.drainPendingSaves();
                // The 1.8–1.13 trees already run this hook on a teardown thread of their own, so the
                // close stays inline there and one shutdown keeps one thread. Every other generation
                // calls the hook on the client thread, where the same close blocks "close the game"
                // for up to five seconds with no upper bound, so it gets the bounded wait those trees
                // already used — the budget the older copies took from ShutdownBudget.
                if (TEARDOWN_THREAD_NAME.equals(Thread.currentThread().getName())) {
                    FabricTranslationRuntime.shutdown();
                    return;
                }
                final Thread teardown = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        FabricTranslationRuntime.shutdown();
                    }
                }, TEARDOWN_THREAD_NAME);
                teardown.setDaemon(false);
                teardown.start();
                try {
                    teardown.join(ShutdownBudget.TEARDOWN_JOIN_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        registerSendMessageHooks();
    }

    /**
     * One client tick.
     *
     * <p>The generations that expose the settings and toggle keys as public statics instead of a
     * tick hook call {@link #openSettingsFromKey()} and {@link #toggleFromKey()} from their mixins;
     * the body is the same either way.
     */
    final void onClientTick() {
        // Before anything else: a render method that threw between its HEAD and RETURN injection
        // left a TextKind and a text-input depth latched on this thread, which silently disables
        // translation for the rest of the session. Every generation already calls this hook once
        // per tick, so this is the single place that guarantees recovery.
        TranslationRenderContext.reset();
        boolean connected = inWorld() && isConnected();
        if (connected && !connectedLastTick) {
            joinHintTicks = 60;
        } else if (!connected) {
            joinHintTicks = -1;
        }
        connectedLastTick = connected;
        if (connected && joinHintTicks > 0 && --joinHintTicks == 0) {
            chat("message.universal_translator.join_hint");
        }
        // One toggle per tick: the body below rebuilds the provider, the session and the file, so a
        // loop over a leftover press count repeated all three that many times on the client thread.
        if (toggleKeyPressed()) {
            toggleFromKey();
        }
        notifyRuntimeStatus(connected);
        if (openSettingsKeyPressed()) {
            openSettingsFor(currentScreen());
        }
    }

    /**
     * The F8 toggle.
     *
     * <p>Build, swap, save: the runtime is only replaced after {@code validateProviderConfiguration}
     * accepted the new configuration, and a failure puts the previous one back so a broken provider
     * setting never leaves the client without translation.
     */
    final void toggleFromKey() {
        FabricConfig previous = null;
        boolean runtimeChanged = false;
        try {
            previous = liveConfig();
            FabricConfig updated = previous.withEnabled(!previous.enabled);
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            runtimeChanged = true;
            FabricTranslationRuntime.initialize(updated);
            lastRuntimeStatus = "";
            nextFailureNotificationAt = 0L;
            // Same writer the runtime's own home toggles use: the press must not write the file on
            // the tick thread, and the player already sees the new state.
            FabricTranslationRuntimeBase.saveInBackground(updated);
            overlay("message.universal_translator.toggle",
                    text(updated.enabled
                            ? "value.universal_translator.enabled"
                            : "value.universal_translator.disabled"));
        } catch (Exception exception) {
            if (runtimeChanged && previous != null) {
                try {
                    FabricTranslationRuntime.initialize(previous);
                } catch (Exception restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
            }
            error("Could not toggle MC Auto Translation Tool", exception);
            overlay("message.universal_translator.toggle_failed");
        }
    }

    /**
     * The settings key, as the Ornithe generations reach it.
     *
     * <p>Those trees hand the key to a mixin instead of doing their own key handling in the tick
     * body. The guards now live in {@link #openSettingsFor(Object)}, which both entry points use, so
     * this method is only the "read the key, open the screen" half.
     */
    final void openSettingsFromKey() {
        openSettingsFor(currentScreen());
    }

    /** Opens the settings screen on top of {@code parent}, logging any failure. */
    final void openSettingsFor(Object parent) {
        // Every entry point funnels through here, so the guard belongs here: the tick loop called
        // this method directly and skipped the chat-screen and text-input checks that
        // openSettingsFromKey applied, which is how the settings screen could open on top of an
        // active chat field and take the player's half-typed line with it.
        if (parent instanceof UniversalTranslatorConfigScreen
                || isChatScreen(parent)
                || TranslationRenderContext.isTextInput()) {
            return;
        }
        try {
            openSettings(parent, liveConfig());
        } catch (Exception exception) {
            error(LogMessages.SETTINGS_OPEN_FAILED, exception);
        }
    }

    /**
     * The configuration to build the next screen or toggle from.
     *
     * <p>The runtime already publishes the snapshot it is running, which is the one that was last
     * written, so the settings key and the toggle key no longer re-read the file: {@code load()}
     * creates a directory, stats the file, parses it, applies the POSIX restriction (an exception
     * path on Windows), attaches the statistics file and reconfigures the debug log, all on the
     * client thread and all visible as a frame hitch when {@code config/} sits on a network drive.
     * Before the first {@code initialize} — a failed start — there is no snapshot, and the file is
     * then the only answer.
     */
    private static FabricConfig liveConfig() throws java.io.IOException {
        FabricConfig live = FabricTranslationRuntime.diagnosticsConfig();
        return live != null ? live : FabricConfig.load(FabricLoader.getInstance().getConfigDir());
    }

    /**
     * Reports a changed runtime status exactly once.
     *
     * <p>Failures are rate limited to one chat line per minute; recoveries reuse the overlay so a
     * flapping provider cannot flood the chat.
     *
     * <p>The localization and the failure classification are memoized per distinct raw status: both
     * walk a table of twenty Chinese phrases, and {@link #toggleFromKey} clears
     * {@link #lastRuntimeStatus} so the very next tick re-ran the whole scan for a status that had
     * not changed at all.
     */
    final void notifyRuntimeStatus(boolean connected) {
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
        String localized = localizeStatus(current);
        if (localizedStatusFailure) {
            long now = System.currentTimeMillis();
            if (now < nextFailureNotificationAt) {
                return;
            }
            nextFailureNotificationAt = now + FAILURE_NOTIFICATION_COOLDOWN_MILLIS;
            chat("message.universal_translator.runtime_failed", localized);
        } else {
            nextFailureNotificationAt = 0L;
            overlay("message.universal_translator.runtime_status", localized);
        }
    }

    /** Localized form of {@code raw}, reusing the previous answer when the raw status repeats. */
    private String localizeStatus(String raw) {
        if (raw.equals(localizedStatusSource)) {
            return localizedStatusText;
        }
        String text = TranslationStatusLocalizer.localize(raw, this::translateKey);
        localizedStatusSource = raw;
        localizedStatusText = text;
        localizedStatusFailure = TranslationStatusLocalizer.isFailure(raw);
        return text;
    }

    /**
     * Intercepts an outgoing chat line.
     *
     * <p>Returns {@code true} to let the line through unchanged; {@code false} means it was taken
     * over and will be re-sent once the translation lands.
     */
    final boolean interceptOutgoingMessage(String message) {
        if (resendingTranslatedMessage || !FabricTranslationRuntime.shouldTranslateOutgoing(message)) {
            FabricTranslationRuntime.protectOutgoingMessage(message);
            return true;
        }
        overlay("message.universal_translator.outgoing_translating");
        FabricTranslationRuntime.translateOutgoing(message).whenComplete((result, error) ->
                executeOnClientThread(new Runnable() {
                    @Override
                    public void run() {
                        sendCompletedMessage(message, result, error);
                    }
                }));
        return false;
    }

    /**
     * Re-sends the translated line, falling back to the original on failure or when the translation
     * would not fit the chat box.
     */
    private void sendCompletedMessage(String original, TranslationResult result, Throwable error) {
        if (!isConnected()) {
            chat("message.universal_translator.outgoing_disconnected");
            return;
        }
        boolean failed = error != null || result == null || result.isFailure();
        String outgoing = failed || !result.isTranslated()
                ? original : result.getTranslatedText();
        boolean tooLong = outgoing.length() > maximumChatLength();
        if (tooLong) {
            outgoing = original;
        }
        FabricTranslationRuntime.protectOutgoingMessage(outgoing);
        resendingTranslatedMessage = true;
        try {
            sendChat(outgoing);
        } finally {
            resendingTranslatedMessage = false;
        }
        if (failed) {
            chat("message.universal_translator.outgoing_failed");
        } else if (tooLong) {
            chat("message.universal_translator.outgoing_too_long");
        }
    }
}
