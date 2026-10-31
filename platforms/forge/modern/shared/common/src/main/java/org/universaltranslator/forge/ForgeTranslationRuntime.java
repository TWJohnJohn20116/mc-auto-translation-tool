package org.universaltranslator.forge;

import net.minecraft.client.Minecraft;
import org.universaltranslator.core.RenderTranslationSession;
import org.universaltranslator.core.MinecraftContentScope;
import org.universaltranslator.core.HomeQuickSettingsState;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.core.PersistentTranslationCache;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.TranslationCache;
import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationProviderStatus;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;
import org.universaltranslator.core.TranslationStatusLocalizer;
import org.universaltranslator.core.TranslationStore;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.RecentUserText;
import org.universaltranslator.core.TranslationResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ForgeTranslationRuntime {
    private static final long PLAYER_NAME_SNAPSHOT_MILLIS = 5_000L;
    // Match ProtectedText's bounded literal limit so large network lobbies do not silently
    // drop names after the first few tab-list pages.
    private static final int MAX_PROTECTED_PLAYER_NAMES = 1_000;

    private static volatile RenderTranslationSession session;
    private static volatile ForgeConfig activeConfig;
    /**
     * Settings snapshot the render hooks read.
     *
     * <p>Published by {@link #initialize} and read from the render thread, so it is a volatile field
     * instead of a value rebuilt under the class monitor: {@code initialize} holds that monitor for
     * the whole provider build, and a render hook waiting behind it is a visible frame hitch on every
     * settings save and F8 toggle.
     */
    private static volatile HomeQuickSettingsState cachedHome;
    private static volatile TranslationProvider activeProvider;
    // Short-lived diagnostics snapshot: see diagnostics(). Read only by the UI, so one writer and any
    // number of readers is all this needs.
    private static final long DIAGNOSTICS_CACHE_MILLIS = 250L;
    private static volatile TranslationDiagnosticsSnapshot diagnosticsSnapshot;
    private static volatile long diagnosticsSnapshotAt;
    private static volatile List<String> protectedPlayerNames = Collections.emptyList();
    private static volatile long protectedPlayerNamesExpireAt;
    /**
     * Shared fallback published while no configuration is loaded.
     *
     * <p>The HUD reads {@link #homeSettings()} several times per frame, and {@code cachedHome} is
     * null not only before the first {@code initialize}: a failed initialization and the state
     * after {@link #shutdown()} are null too. Allocating a fresh object per read there made a
     * broken configuration cost one allocation per HUD read, every frame.
     */
    private static final HomeQuickSettingsState DISABLED_HOME =
            new HomeQuickSettingsState(false, true, TargetLanguage.SIMPLIFIED_CHINESE);

    // ---------------------------------------------------------------------------------------------
    // Short-window memo in front of the render lookup (P2-075)
    // ---------------------------------------------------------------------------------------------
    // A drawn line is looked up at least twice (once to measure it, once to draw it), and a HUD
    // repeats the same handful of strings every frame. The session lookup builds a RenderKey and
    // probes three maps per call, so the immediate repeat is answered here instead. The window is
    // one client tick: results are allowed to lag the arrival of a worker-thread translation by at
    // most one tick (50 ms), which is below perception, while a stale config can never leak because
    // initialize() and shutdown() bump the same epoch.
    private static final int RENDER_MEMO_SLOTS = 64;
    private static final String[] RENDER_MEMO_KEYS = new String[RENDER_MEMO_SLOTS];
    private static final String[] RENDER_MEMO_VALUES = new String[RENDER_MEMO_SLOTS];
    private static final TextKind[] RENDER_MEMO_KINDS = new TextKind[RENDER_MEMO_SLOTS];
    private static final int[] RENDER_MEMO_EPOCHS = new int[RENDER_MEMO_SLOTS];
    private static volatile int renderMemoEpoch = 1;

    /** Guards the player-name refresh so a burst of render-thread lookups rebuilds once per window. */
    private static final AtomicBoolean playerNameRefresh = new AtomicBoolean();
    private static final RecentUserText RECENT_USER_TEXT = new RecentUserText();
    private static volatile CompletableFuture<Void> outgoingTail = CompletableFuture.completedFuture(null);
    /**
     * Guards {@link #outgoingTail} only.
     *
     * <p>{@code translateOutgoing} used to carry the class monitor, which {@code initialize} holds
     * for a whole provider build: pressing Enter while a provider was being replaced waited behind
     * that build for no reason, because the send path only reads the volatile session/config
     * snapshots and needs mutual exclusion for the ordering tail, not for the runtime.
     */
    private static final Object OUTGOING_LOCK = new Object();
    // A single daemon thread serializes the teardown of replaced sessions: at most one reaper
    // thread exists no matter how often the configuration is switched, and two replaced sessions
    // can never tear down concurrently. Threads are created lazily, so a client that never switches
    // pays nothing, and the daemon flag keeps the reaper from holding the JVM open.
    private static final ExecutorService SESSION_REAPER = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "universal-translator-session-reaper");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private ForgeTranslationRuntime() {
    }

    private static volatile java.util.function.Consumer<Object> titleSettingsOpener;

    public static void setTitleSettingsOpener(java.util.function.Consumer<Object> opener) {
        titleSettingsOpener = opener;
    }

    public static void openSettingsScreen(Object parent) {
        java.util.function.Consumer<Object> opener = titleSettingsOpener;
        if (opener != null) {
            opener.accept(parent);
        }
    }

    /**
     * Installs a replacement configuration without blocking the caller on the teardown of the one
     * it replaces.
     *
     * <p>Build first: the replacement provider, store and session are constructed before any live
     * field is touched, so a construction failure (a rejected provider configuration, an unreadable
     * cache path) leaves the previous session fully usable. The old order shut the previous session
     * down first, so failing halfway left the client with no translation at all.
     *
     * <p>Swap second: {@code session}, {@code activeConfig} and {@code activeProvider} are replaced
     * together under the class monitor. The previous session keeps serving every lookup until that
     * instant, so switching costs no translation gap.
     *
     * <p>Close the replaced session in the background: {@code close()} interrupts the workers and
     * then waits up to five seconds for one parked in a provider read, and running that on the
     * client tick thread is what froze the game for hundreds of milliseconds to several seconds on
     * every F8 toggle and settings save.
     */
    static synchronized void initialize(ForgeConfig config) throws IOException {
        // Build the replacement without touching session/activeConfig/activeProvider: if anything
        // here throws, the caller still has the previous, fully working session.
        TranslationProvider createdProvider = null;
        RenderTranslationSession created = null;
        if (config.enabled) {
            TranslationProvider provider = config.createProvider();
            try {
                // Record the provider before the store and the session are built around it: a
                // failure in either must not orphan a provider that may already hold resources.
                createdProvider = provider;
                // One store per cache file: a settings change must not make the next session's first
                // lookup parse the whole file again.
                TranslationStore store = config.diskCache
                        ? PersistentTranslationCache.shared(config.cacheFile, 10_000)
                        : new TranslationCache(10_000);
                // The offline llama.cpp server is single threaded, so it always gets one worker no
                // matter what the configuration asks for.
                int workers = provider.id().contains("offline-llama:") ? 1 : config.translationWorkers();
                created = new RenderTranslationSession(
                        provider, TargetLanguage.AUTO, config.targetLanguage, store, workers, config.displayMode,
                        config.translateEnglishOnly);
                created.setBlockedKeywords(config.blockedKeywords);
                created.setProtectedLiteralsSupplier(ForgeTranslationRuntime::playerNameSnapshot);
            } catch (Throwable buildFailure) {
                // The session was never handed to anyone and its own close() needs a live provider,
                // so the provider is what has to be released here. TranslationProvider does not
                // declare close(); the providers that own resources expose it via AutoCloseable.
                if (createdProvider instanceof AutoCloseable) {
                    try {
                        ((AutoCloseable) createdProvider).close();
                    } catch (Throwable closeFailure) {
                        // Keep the build failure as the one the caller sees (F8 and
                        // applyHomeSettings recover from it) and record the teardown failure as
                        // suppressed information instead of letting it replace the original.
                        buildFailure.addSuppressed(closeFailure);
                    }
                }
                throw buildFailure;
            }
        }
        // Swap last, in one synchronized block. The replaced session is captured in a local
        // variable because the field no longer refers to it after the next three assignments.
        RenderTranslationSession replaced = session;
        activeConfig = config;
        activeProvider = createdProvider;
        session = created;
        cachedHome = homeSettingsState(config);
        protectedPlayerNames = Collections.emptyList();
        protectedPlayerNamesExpireAt = 0L;
        // The previous diagnostics snapshot describes the previous provider; drop it so the screen
        // never shows a provider id that has just been replaced.
        diagnosticsSnapshot = null;
        diagnosticsSnapshotAt = 0L;
        invalidateRenderMemo();
        RECENT_USER_TEXT.clear();
        outgoingTail = CompletableFuture.completedFuture(null);
        closeInBackground(replaced);
    }

    /** Teardowns queued but not finished; caps the reaper's backlog (see {@link #closeInBackground}). */
    private static final AtomicInteger PENDING_TEARDOWNS = new AtomicInteger();
    /** Backlog the reaper accepts before the caller performs the teardown itself. */
    private static final int MAX_PENDING_TEARDOWNS = 2;

    /**
     * Hands a replaced session to the shared reaper so its teardown never runs on the caller's
     * thread. {@link #shutdown()} stays synchronous on purpose: it runs while the game is closing,
     * where blocking is free, and it closes whichever session is current at that moment, which is
     * never one that was already handed to the reaper.
     *
     * <p>The reaper is a single unbounded queue, so a burst of switches — holding F8 down, or a
     * HUD drag that re-initializes several times a second — used to queue one live session with its
     * thread pool and caches per press, each worth up to five seconds of teardown. Past
     * {@link #MAX_PENDING_TEARDOWNS} the caller now performs the teardown itself: the tick that
     * switched pays the cost instead of the process growing an unbounded backlog of live sessions.
     */
    private static void closeInBackground(final RenderTranslationSession replaced) {
        if (replaced == null) {
            return;
        }
        if (PENDING_TEARDOWNS.incrementAndGet() > MAX_PENDING_TEARDOWNS) {
            try {
                replaced.close();
            } catch (Throwable ignored) {
                // Same contract as the reaper body: a teardown must never take the caller down.
            } finally {
                PENDING_TEARDOWNS.decrementAndGet();
            }
            return;
        }
        try {
            SESSION_REAPER.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        replaced.close();
                    } catch (Throwable ignored) {
                        // A reaper must never die with a teardown still pending. The session is
                        // already unreachable from the live fields, so a failure here can only
                        // leak its resources; there is no caller left to report it to.
                    } finally {
                        PENDING_TEARDOWNS.decrementAndGet();
                    }
                }
            });
        } catch (Throwable rejected) {
            // The executor only rejects once it has been shut down, which this class never does.
            // Leaving the replaced session unclosed still beats blocking the caller.
            PENDING_TEARDOWNS.decrementAndGet();
        }
    }

    static String translateForRender(String original, TextKind kind) {
        RenderTranslationSession active = session;
        ForgeConfig config = activeConfig;
        Minecraft client = Minecraft.getInstance();
        if (active == null || config == null || !config.allows(kind)
                || (!config.translateVanilla && kind == TextKind.OTHER
                && MinecraftContentScope.isVanillaScreen(ForgeLocalTextGuard.currentScreen(client)))
                || client.screen instanceof UniversalTranslatorConfigScreen
                || client.screen instanceof UniversalTranslatorDiagnosticsScreen
                || client.screen instanceof UniversalTranslatorLlmConfigScreen
                || ForgeLocalTextGuard.isLocalChatInput(client, original)
                || RECENT_USER_TEXT.shouldPreserve(original)) {
            return original;
        }
        return lookupWithMemo(active, original, kind);
    }

    /**
     * Starts a new memo window. Called once per client tick by every Forge client entry point,
     * before that tick's frames render.
     */
    static void beginRenderFrame() {
        renderMemoEpoch++;
    }

    /** Epoch of the current memo window; the shared {@code RenderedTextBridge} memos use it too. */
    static int currentRenderEpoch() {
        return renderMemoEpoch;
    }

    /** Drops every memoised lookup without waiting for the next tick. */
    private static void invalidateRenderMemo() {
        renderMemoEpoch++;
    }

    private static String lookupWithMemo(
            RenderTranslationSession active, String original, TextKind kind) {
        if (original == null) {
            // Null reaches here from the render hooks; it is not worth a memo slot.
            return active.lookup(original, kind);
        }
        int slot = (original.hashCode() * 31 + (kind == null ? 0 : kind.ordinal()))
                & (RENDER_MEMO_SLOTS - 1);
        int epoch = renderMemoEpoch;
        if (RENDER_MEMO_EPOCHS[slot] == epoch
                && RENDER_MEMO_KINDS[slot] == kind
                && original.equals(RENDER_MEMO_KEYS[slot])) {
            return RENDER_MEMO_VALUES[slot];
        }
        String translated = active.lookup(original, kind);
        RENDER_MEMO_KEYS[slot] = original;
        RENDER_MEMO_VALUES[slot] = translated;
        RENDER_MEMO_KINDS[slot] = kind;
        // Published last: a reader that matches the epoch must find the matching entry.
        RENDER_MEMO_EPOCHS[slot] = epoch;
        return translated;
    }

    static synchronized void shutdown() {
        RenderTranslationSession active = session;
        session = null;
        // Clear the published settings too, not just the session. Leaving them behind made a failed
        // initialize() permanent: session == null meant "nothing translates", while the HUD read
        // cachedHome.enabled == true and kept painting the "translation is on" indicator,
        // diagnostics() reported a provider that no longer exists, and requireActiveConfig() handed
        // the broken configuration back to the HUD toggles, which then saved it over the file.
        activeConfig = null;
        activeProvider = null;
        cachedHome = null;
        diagnosticsSnapshot = null;
        diagnosticsSnapshotAt = 0L;
        protectedPlayerNames = Collections.emptyList();
        protectedPlayerNamesExpireAt = 0L;
        RECENT_USER_TEXT.clear();
        outgoingTail = CompletableFuture.completedFuture(null);
        invalidateRenderMemo();
        // Both caches outlive the session on purpose (they are shared by every snapshot of the same
        // file), so releasing the configuration is the point where they are dropped instead.
        ForgeConfig.clearCaches();
        // The queued writes are the ones this runtime accepted; let them land, then stop the writer so
        // no daemon thread outlives the configuration it was writing.
        ForgeConfigWriter.shutdown();
        if (active != null) {
            active.close();
        }
    }

    private static List<String> playerNameSnapshot() {
        long now = System.currentTimeMillis();
        List<String> snapshot = protectedPlayerNames;
        if (now < protectedPlayerNamesExpireAt) {
            return snapshot;
        }
        // Refreshed outside the class monitor: the coordinator asks for this on every submitted
        // string, so the render thread reaches it for every new line, and initialize() holds that
        // monitor for the whole provider build. One refresher per window is enough; the rest keep
        // using the published snapshot, which only decides which names are excluded.
        if (!playerNameRefresh.compareAndSet(false, true)) {
            return snapshot;
        }
        // Refreshed on the shared reaper instead of the calling thread: this runs on the render
        // thread for every new chat line, and one refresh walks the whole tab list and allocates
        // three containers. Readers keep the published snapshot until the new one replaces it.
        try {
            SESSION_REAPER.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        refreshPlayerNames(System.currentTimeMillis());
                    } catch (Throwable ignored) {
                        // The previous snapshot stays published and the next window retries.
                    } finally {
                        playerNameRefresh.set(false);
                    }
                }
            });
        } catch (Throwable rejected) {
            // Only reachable once the executor is shut down, which this class never does.
            playerNameRefresh.set(false);
        }
        return snapshot;
    }

    private static List<String> refreshPlayerNames(long now) {
        Minecraft client = Minecraft.getInstance();
        boolean protectPlayerNames = activeConfig == null || !activeConfig.translatePlayerNames;
        if (client.getConnection() == null) {
            protectedPlayerNames = Collections.emptyList();
        } else {
            // LinkedHashSet removes duplicates in O(1) per name and keeps insertion order, so the
            // snapshot no longer rescans the whole list for every player on the render thread.
            LinkedHashSet<String> names = new LinkedHashSet<String>();
            if (protectPlayerNames) {
                addProtectedLiteral(names, client.getUser().getName());
            }
            if (client.getCurrentServer() != null) {
                addProtectedLiteral(names, client.getCurrentServer().ip);
            }
            if (protectPlayerNames) {
                // limit() stops the source after the bounded number of names, so a 500-player tab
                // list is no longer walked in full after the cap is reached; the cap itself lives
                // only here instead of being repeated inside addProtectedLiteral.
                client.getConnection().getOnlinePlayers().stream()
                        .limit(MAX_PROTECTED_PLAYER_NAMES)
                        .forEach(entry -> addProtectedLiteral(names, entry.getProfile().getName()));
            }
            protectedPlayerNames = Collections.unmodifiableList(new ArrayList<String>(names));
        }
        protectedPlayerNamesExpireAt = now + PLAYER_NAME_SNAPSHOT_MILLIS;
        return protectedPlayerNames;
    }

    /**
     * Refreshes the protected-name snapshot from the client tick instead of from a draw call.
     *
     * <p>The supplier below is asked for this on the render thread for every submitted string, so
     * the refresh runs inside a frame: on a full server the 5 s window expiring on an unlucky frame
     * walks the whole tab list between two draw calls. Refreshing here means a frame only reads the
     * published snapshot, and the client thread is where the tab list may be read at all.
     */
    static void refreshProtectedNames() {
        playerNameSnapshot();
    }

    private static void addProtectedLiteral(LinkedHashSet<String> values, String value) {
        if (value == null) {
            return;
        }
        String normalized = value.trim();
        if (!normalized.isEmpty() && normalized.length() <= 255 && !values.contains(normalized)) {
            values.add(normalized);
        }
    }

    static String status() {
        RenderTranslationSession active = session;
        TranslationProvider provider = activeProvider;
        String providerStatus = provider instanceof TranslationProviderStatus
                ? ((TranslationProviderStatus) provider).status() : "";
        // The offline provider already has the specific startup diagnostic. Prefer it over the
        // session's generic wrapper so one failure cannot alternate between two chat messages.
        // isFailure() rather than one literal prefix: the wording of the provider status is not a
        // contract, and a status that stopped matching here was reported as a healthy one.
        if (TranslationStatusLocalizer.isFailure(providerStatus)) {
            return providerStatus;
        }
        if (active != null && !active.lastFailureStatus().isEmpty()) {
            return active.lastFailureStatus();
        }
        return providerStatus;
    }

    static TranslationDiagnosticsSnapshot diagnostics() {
        // Reused when fresh. The diagnostics screen calls this once per frame and every call did two
        // filesystem queries (model file and cache file, each an isRegularFile plus a size) and then
        // rebuilt the whole page of lines on top of it. On a config directory backed by a cloud sync
        // folder that was visible stutter while translation was running. 250 ms is far below what a
        // reader can notice and far above one frame.
        TranslationDiagnosticsSnapshot cached = diagnosticsSnapshot;
        long now = System.currentTimeMillis();
        if (cached != null && now - diagnosticsSnapshotAt < DIAGNOSTICS_CACHE_MILLIS) {
            return cached;
        }
        ForgeConfig config = activeConfig;
        TranslationProvider provider = activeProvider;
        TranslationDiagnosticsSnapshot snapshot;
        if (config == null) {
            // Empty status, not a hardcoded Chinese placeholder: the screen renders its own
            // "settings unavailable" key, and this string reached English and Japanese users
            // untranslated because it never went through a lang file.
            snapshot = new TranslationDiagnosticsSnapshot(
                    false, "", "", "", null, false, false, -1L, -1L, "");
        } else {
            Path modelFile = config.offlineDirectory.resolve(config.offlineModel.modelFile());
            snapshot = new TranslationDiagnosticsSnapshot(
                    config.enabled,
                    config.provider,
                    provider == null ? "" : provider.id(),
                    config.targetLanguage,
                    config.offlineModel,
                    config.offlineAutoDownload,
                    config.diskCache,
                    fileSize(modelFile),
                    fileSize(config.cacheFile),
                    status());
        }
        diagnosticsSnapshot = snapshot;
        diagnosticsSnapshotAt = now;
        return snapshot;
    }

    private static long fileSize(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : -1L;
        } catch (IOException ignored) {
            return -1L;
        }
    }

    static List<String> translateLinesForRender(List<String> originals, TextKind kind) {
        RenderTranslationSession active = session;
        ForgeConfig config = activeConfig;
        Minecraft client = Minecraft.getInstance();
        if (active == null || config == null || !config.allows(kind)
                || TranslationRenderContext.isTextInput()) {
            return originals;
        }
        return active.lookupLines(originals, kind);
    }

    static TranslationTextColor translatedTextColor() {
        ForgeConfig config = activeConfig;
        return config == null ? TranslationTextColor.ORIGINAL : config.translatedTextColor;
    }

    /**
     * True when a render-time lookup can produce a translation at all right now.
     *
     * <p>A coarse, allocation-free answer for the render hooks, which run for every drawn line
     * whether translation is enabled or not: flattening a line into a string costs several
     * allocations per call, and with translation switched off the only correct answer is the line
     * itself. This is a fast path only; the per-kind and per-screen decisions stay in
     * {@link #translateForRender}.
     */
    static boolean renderTranslationEnabled() {
        ForgeConfig config = activeConfig;
        return session != null && config != null && config.enabled;
    }

    static void protectOutgoingMessage(String message) {
        RECENT_USER_TEXT.remember(message);
    }

    static boolean shouldTranslateOutgoing(String message) {
        ForgeConfig config = activeConfig;
        return session != null && config != null && config.enabled && config.translateOutgoing
                && message != null && !message.trim().isEmpty() && !message.startsWith("/");
    }

    /** Serializes outgoing requests so rapidly sent chat lines keep their original order. */
    static CompletableFuture<TranslationResult> translateOutgoing(String message) {
        final RenderTranslationSession active = session;
        final ForgeConfig config = activeConfig;
        if (active == null || config == null || !shouldTranslateOutgoing(message)) {
            return CompletableFuture.completedFuture(TranslationResult.unchanged(message));
        }
        RECENT_USER_TEXT.remember(message);
        // Capture the tab-list/server literals on Minecraft's calling thread. The translation
        // itself may finish on a worker, but must not inspect client network state there.
        CompletableFuture<TranslationResult> translated = active.translateInteractive(
                message, TextKind.CHAT, config.outgoingTargetLanguage, false,
                config.streamOutgoing);
        synchronized (OUTGOING_LOCK) {
            CompletableFuture<TranslationResult> next = outgoingTail
                    .handle((ignored, failure) -> null)
                    .thenCompose(ignored -> translated);
            outgoingTail = next.handle((ignored, failure) -> null);
            return next;
        }
    }

    /**
     * Settings snapshot for the render hooks, taken without entering the class monitor.
     *
     * <p>The HUD reads this five times per frame, so it must not be {@code synchronized}: the build
     * that publishes it ({@link #initialize}) holds that monitor for the whole provider
     * construction. The fallback only runs before the first {@code initialize}, when no HUD exists
     * yet, and reads the same {@code activeConfig} the previous synchronized body read.
     */
    public static HomeQuickSettingsState homeSettings() {
        HomeQuickSettingsState snapshot = cachedHome;
        return snapshot == null ? homeSettingsState(activeConfig) : snapshot;
    }

    public static synchronized HomeQuickSettingsState toggleHomeEnabled() throws Exception {
        ForgeConfig current = requireActiveConfig();
        return applyHomeSettings(current, current.withHomeSettings(
                !current.enabled, current.translateVanilla, current.targetLanguage));
    }

    public static synchronized HomeQuickSettingsState toggleHomeVanilla() throws Exception {
        ForgeConfig current = requireActiveConfig();
        return applyHomeSettings(current, current.withHomeSettings(
                current.enabled, !current.translateVanilla, current.targetLanguage));
    }

    public static synchronized HomeQuickSettingsState cycleHomeTargetLanguage() throws Exception {
        ForgeConfig current = requireActiveConfig();
        return applyHomeSettings(current, current.withHomeSettings(
                current.enabled, current.translateVanilla,
                TargetLanguage.nextPreset(current.targetLanguage)));
    }

    /**
     * The active configuration, or {@code null} before the first load.
     *
     * <p>The diagnostics screen edits the prompt, debug-log and statistics settings, so it needs the
     * same config object the rest of the runtime already holds. Returning it here keeps the
     * runtime's own API unchanged for every other caller.
     */
    static ForgeConfig diagnosticsConfig() {
        return activeConfig;
    }

    private static ForgeConfig requireActiveConfig() {
        ForgeConfig current = activeConfig;
        if (current == null) {
            throw new IllegalStateException("Translation settings are not loaded");
        }
        return current;
    }

    private static HomeQuickSettingsState applyHomeSettings(
            ForgeConfig previous,
            ForgeConfig updated
    ) throws Exception {
        boolean runtimeChanged = false;
        try {
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            runtimeChanged = true;
            initialize(updated);
            // Queued off the caller's thread: the home buttons and F8 arrive on the client tick, and a
            // save is a temporary create, a write, an atomic move and two ownership changes.
            ForgeConfigWriter.saveOffTick(updated, null);
            return homeSettingsState(updated);
        } catch (Exception failure) {
            if (runtimeChanged) {
                try {
                    initialize(previous);
                } catch (Exception restoreFailure) {
                    failure.addSuppressed(restoreFailure);
                }
            }
            throw failure;
        }
    }

    private static HomeQuickSettingsState homeSettingsState(ForgeConfig config) {
        return config == null ? DISABLED_HOME : new HomeQuickSettingsState(
                config.enabled, config.translateVanilla, config.targetLanguage,
                config.hudIndicatorSettings(), config.provider);
    }
}
