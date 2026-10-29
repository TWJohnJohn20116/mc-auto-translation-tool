package org.universaltranslator.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.universaltranslator.core.LogMessages;
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

    private boolean connectedLastTick;
    private int joinHintTicks = -1;
    private String lastRuntimeStatus = "";
    private long nextFailureNotificationAt;
    private boolean resendingTranslatedMessage;

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
     * True when {@code screen} is a chat screen.
     *
     * <p>Answered by class name rather than {@code instanceof}: this base is compiled once for every
     * generation from 1.14 to 26.3, and a compile-time {@code ChatScreen} reference would tie it to
     * one generation's package. The trees that can name the class (the Ornithe ones) still override
     * this with an exact {@code instanceof} check.
     *
     * <p>Used by {@link #openSettingsFor(Object)}: while the chat screen is open, the settings key
     * belongs to the chat box, not to this mod.
     */
    protected boolean isChatScreen(Object screen) {
        if (screen == null) {
            return false;
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
                FabricTranslationRuntime.shutdown();
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
        while (toggleKeyPressed()) {
            toggleFromKey();
        }
        notifyRuntimeStatus(connected);
        while (openSettingsKeyPressed()) {
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
            previous = FabricConfig.load(FabricLoader.getInstance().getConfigDir());
            FabricConfig updated = previous.withEnabled(!previous.enabled);
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            runtimeChanged = true;
            FabricTranslationRuntime.initialize(updated);
            lastRuntimeStatus = "";
            nextFailureNotificationAt = 0L;
            updated.save();
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
            openSettings(parent, FabricConfig.load(FabricLoader.getInstance().getConfigDir()));
        } catch (Exception exception) {
            error(LogMessages.SETTINGS_OPEN_FAILED, exception);
        }
    }

    /**
     * Reports a changed runtime status exactly once.
     *
     * <p>Failures are rate limited to one chat line per minute; recoveries reuse the overlay so a
     * flapping provider cannot flood the chat.
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
        String localized = TranslationStatusLocalizer.localize(current, this::translateKey);
        if (TranslationStatusLocalizer.isFailure(current)) {
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
