package org.universaltranslator.forge.legacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import org.universaltranslator.core.RenderTranslationSession;
import org.universaltranslator.core.MinecraftContentScope;
import org.universaltranslator.core.HomeQuickSettingsState;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.core.PersistentTranslationCache;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.TranslationCache;
import org.universaltranslator.core.TranslationStore;
import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationProviderStatus;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.RecentUserText;
import org.universaltranslator.core.TranslationResult;
import org.universaltranslator.core.TranslationStatusLocalizer;
import org.universaltranslator.core.DiagnosticsLogExporter;
import org.universaltranslator.core.DebugLog;

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

public final class LegacyTranslationRuntime {
    private static final long PLAYER_NAME_SNAPSHOT_MILLIS = 5_000L;
    // Short-lived diagnostics snapshot: see diagnostics().
    private static final long DIAGNOSTICS_CACHE_MILLIS = 250L;
    private static volatile TranslationDiagnosticsSnapshot diagnosticsSnapshot;
    private static volatile long diagnosticsSnapshotAt;
    // Match ProtectedText's bounded literal limit so large network lobbies do not silently
    // drop names after the first few tab-list pages.
    private static final int MAX_PROTECTED_PLAYER_NAMES = 1_000;

    private static volatile RenderTranslationSession session;
    private static volatile LegacyConfig activeConfig;
    /**
     * The snapshot the HUD draws when no configuration is loaded.
     *
     * <p>A shared instance, not a fresh object: shutdown() and a failed initialize() both leave
     * {@code cachedHome} null, and the HUD then reads this several times per frame for as long as
     * the state lasts.
     */
    private static final HomeQuickSettingsState DISABLED_SETTINGS =
            new HomeQuickSettingsState(false, true, TargetLanguage.SIMPLIFIED_CHINESE);
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
    private static volatile List<String> protectedPlayerNames = Collections.emptyList();
    private static volatile long protectedPlayerNamesExpireAt;

    /** Guards the player-name refresh so a burst of render-thread lookups rebuilds once per window. */
    private static final AtomicBoolean playerNameRefresh = new AtomicBoolean();
    private static final RecentUserText RECENT_USER_TEXT = new RecentUserText();
    private static CompletableFuture<Void> outgoingTail = CompletableFuture.completedFuture(null);
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

    private LegacyTranslationRuntime() {
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
    static synchronized void initialize(LegacyConfig config) throws IOException {
        // Build the replacement without touching session/activeConfig/activeProvider: if anything
        // here throws, the caller still has the previous, fully working session.
        TranslationProvider createdProvider = null;
        RenderTranslationSession created = null;
        if (config.enabled) {
            // Built in the modern runtime's order: the provider first, then recorded, then the store
            // and the session. Building the cache first meant a provider construction failure (a
            // rejected llama.cpp path, an incomplete API fallback) left a 10 000-entry store behind
            // with no owner to close it, and every retry added another one.
            TranslationProvider provider = config.createProvider();
            TranslationStore store = null;
            try {
                // Record the provider before the store and the session are built around it: a
                // failure in either must not orphan a provider that may already hold resources.
                createdProvider = provider;
                // One store per cache file: a settings change must not make the next session's first
                // lookup parse the whole file again.
                store = config.diskCache
                        ? PersistentTranslationCache.shared(config.cacheFile.toPath(), 10_000)
                        : new TranslationCache(10_000);
                // The offline llama.cpp server is single threaded, so it always gets one worker no
                // matter what the configuration asks for.
                int workers = provider.id().contains("offline-llama:") ? 1 : config.translationWorkers();
                created = new RenderTranslationSession(
                        provider, TargetLanguage.AUTO, config.targetLanguage, store, workers, config.displayMode,
                        config.translateEnglishOnly);
                created.setBlockedKeywords(config.blockedKeywords);
                created.setProtectedLiteralsSupplier(LegacyTranslationRuntime::playerNameSnapshot);
            } catch (Throwable buildFailure) {
                // The session was never handed to anyone and its own close() needs a live provider,
                // so the provider is what has to be released here. TranslationProvider does not
                // declare close(); the providers that own resources expose it via AutoCloseable.
                releaseQuietly(createdProvider, buildFailure);
                // Same for a store built before the session failed: the persistent cache owns a file
                // handle once it is loaded, and nothing else refers to this one.
                releaseQuietly(store, buildFailure);
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
        RECENT_USER_TEXT.clear();
        outgoingTail = CompletableFuture.completedFuture(null);
        closeInBackground(replaced);
    }

    /**
     * Closes {@code resource} if it owns anything, keeping the caller's failure as the reported one.
     *
     * <p>{@code TranslationProvider} and {@code TranslationStore} do not declare {@code close()};
     * the implementations that hold a socket or a file handle expose it through {@link AutoCloseable}.
     * A teardown failure is recorded as suppressed information instead of replacing the build failure,
     * which is the one F8 and {@code applyHomeSettings} know how to recover from.
     */
    private static void releaseQuietly(Object resource, Throwable buildFailure) {
        if (!(resource instanceof AutoCloseable)) {
            return;
        }
        try {
            ((AutoCloseable) resource).close();
        } catch (Throwable closeFailure) {
            buildFailure.addSuppressed(closeFailure);
        }
    }

    /** Teardowns queued but not finished, reported by {@link #closeInBackground}. */
    private static final AtomicInteger PENDING_TEARDOWNS = new AtomicInteger();
    /** Backlog depth worth one debug-log line; the queue itself is never truncated. */
    private static final int MAX_PENDING_TEARDOWNS = 2;

    /**
     * Hands a replaced session to the shared reaper so its teardown never runs on the caller's
     * thread. {@link #shutdown()} stays synchronous on purpose: it runs while the game is closing,
     * where blocking is free, and it closes whichever session is current at that moment, which is
     * never one that was already handed to the reaper.
     *
     * <p>The reaper is a single unbounded queue, so a burst of switches — holding F8 down, or a
     * HUD drag that re-initializes several times a second — queues one live session with its thread
     * pool and caches per press, each worth up to five seconds of teardown. That backlog is never
     * handed back to the caller: {@link #PENDING_TEARDOWNS} reports its depth, and past
     * {@link #MAX_PENDING_TEARDOWNS} the depth is recorded in the debug log, because performing the
     * teardown here would block the client tick for up to five seconds per press — exactly the
     * freeze this reaper exists to prevent.
     */
    private static void closeInBackground(final RenderTranslationSession replaced) {
        if (replaced == null) {
            return;
        }
        if (PENDING_TEARDOWNS.incrementAndGet() == MAX_PENDING_TEARDOWNS + 1) {
            DebugLog.global().log("runtime", "teardown", "backlog",
                    "pending", Integer.toString(MAX_PENDING_TEARDOWNS + 1));
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

    static String translate(String original, TextKind kind) {
        RenderTranslationSession active = session;
        LegacyConfig config = activeConfig;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (active == null || config == null || !config.allows(kind)
                || (!config.translateVanilla && kind == TextKind.OTHER
                && MinecraftContentScope.isVanillaScreen(minecraft.currentScreen))
                || minecraft.currentScreen instanceof LegacyConfigScreen
                || minecraft.currentScreen instanceof LegacyDiagnosticsScreen
                || minecraft.currentScreen instanceof LegacyLlmConfigScreen
                || LegacyLocalTextGuard.isLocalChatInput(minecraft.currentScreen, original)
                || RECENT_USER_TEXT.shouldPreserve(original)) {
            return original;
        }
        return active.lookup(original, kind);
    }

    static void shutdown() {
        RenderTranslationSession active;
        synchronized (LegacyTranslationRuntime.class) {
            active = session;
            session = null;
            // Clear the published settings too, not just the session. Leaving them behind made a failed
            // initialize() permanent: session == null meant "nothing translates", while the HUD read
            // cachedHome.enabled == true and kept painting the "translation is on" indicator,
            // diagnostics() reported a provider that no longer exists, and the Home toggles handed the
            // broken configuration back and then saved it over the file.
            activeConfig = null;
            activeProvider = null;
            cachedHome = null;
            diagnosticsSnapshot = null;
            diagnosticsSnapshotAt = 0L;
            protectedPlayerNames = Collections.emptyList();
            protectedPlayerNamesExpireAt = 0L;
            RECENT_USER_TEXT.clear();
            outgoingTail = CompletableFuture.completedFuture(null);
            // The on-disk copy of the advanced keys outlives a session on purpose (every snapshot of the
            // same file shares it), so releasing the configuration is where it is dropped.
            LegacyConfig.clearCaches();
        }
        // The waits below are bounded but long -- two seconds for the write queue, up to five for a
        // session with a worker parked in a provider read -- so they run outside the class monitor:
        // initialize() takes that same monitor from the render thread on a settings change, and holding
        // it here froze one frame meanwhile.
        // The queued writes are the ones this runtime accepted; let them land before the state they
        // were derived from is dropped.
        LegacyConfigWriter.shutdown();
        // The screens' shared pool goes with the client so a closed game keeps no daemon threads.
        LegacyScreenWorkers.shutdown();
        if (active != null) {
            active.close();
        }
    }

    /**
     * Refreshes the protected-name snapshot from the client tick instead of from a draw call.
     *
     * <p>The supplier below is asked for this on the render thread for every submitted string, so
     * the refresh ran inside a frame: on a full server that walked the whole tab list between two
     * draw calls.
     */
    static void refreshProtectedNames() {
        playerNameSnapshot();
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
        try {
            return refreshPlayerNames(now);
        } finally {
            playerNameRefresh.set(false);
        }
    }

    private static List<String> refreshPlayerNames(long now) {
        Minecraft minecraft = Minecraft.getMinecraft();
        boolean protectPlayerNames = activeConfig == null || !activeConfig.translatePlayerNames;
        NetHandlerPlayClient connection = LegacyVersionAccess.connection(minecraft);
        if (connection == null) {
            protectedPlayerNames = Collections.emptyList();
        } else {
            // LinkedHashSet removes duplicates in O(1) per name and keeps insertion order, so the
            // snapshot no longer rescans the whole list for every player on the render thread.
            LinkedHashSet<String> names = new LinkedHashSet<String>();
            if (protectPlayerNames) {
                addProtectedLiteral(names, LegacyVersionAccess.localPlayerName(minecraft));
            }
            addProtectedLiteral(names, LegacyVersionAccess.serverAddress(minecraft));
            if (protectPlayerNames) {
                for (NetworkPlayerInfo player : connection.getPlayerInfoMap()) {
                    if (names.size() >= MAX_PROTECTED_PLAYER_NAMES) {
                        break;
                    }
                    if (player.getGameProfile() != null && player.getGameProfile().getName() != null) {
                        addProtectedLiteral(names, player.getGameProfile().getName());
                    }
                }
            }
            protectedPlayerNames = Collections.unmodifiableList(new ArrayList<String>(names));
        }
        protectedPlayerNamesExpireAt = now + PLAYER_NAME_SNAPSHOT_MILLIS;
        return protectedPlayerNames;
    }

    private static void addProtectedLiteral(LinkedHashSet<String> values, String value) {
        if (value == null) {
            return;
        }
        String normalized = value.trim();
        if (!normalized.isEmpty() && normalized.length() <= 255
                && !values.contains(normalized)) {
            values.add(normalized);
        }
    }

    static String status() {
        RenderTranslationSession active = session;
        TranslationProvider provider = activeProvider;
        String providerStatus = provider instanceof TranslationProviderStatus
                ? ((TranslationProviderStatus) provider).status() : "";
        // Preserve the offline provider's concrete process error instead of alternating it with
        // the session's generic "translation failed" wrapper on successive client ticks. Decided
        // through the shared classifier instead of a copy of the provider's Chinese message prefix,
        // which stopped matching as soon as that wording changed.
        if (TranslationStatusLocalizer.isFailure(providerStatus)) {
            return providerStatus;
        }
        if (active != null && !active.lastFailureStatus().isEmpty()) {
            return active.lastFailureStatus();
        }
        return providerStatus;
    }

    static TranslationDiagnosticsSnapshot diagnostics() {
        // Same short-lived reuse as the modern Forge runtimes: the diagnostics screen asks once per
        // frame and every answer costs two filesystem queries plus a full page rebuild.
        TranslationDiagnosticsSnapshot cached = diagnosticsSnapshot;
        long now = System.currentTimeMillis();
        if (cached != null && now - diagnosticsSnapshotAt < DIAGNOSTICS_CACHE_MILLIS) {
            return cached;
        }
        LegacyConfig config = activeConfig;
        TranslationProvider provider = activeProvider;
        TranslationDiagnosticsSnapshot snapshot;
        if (config == null) {
            // Empty rather than a Chinese placeholder: the status is handed to
            // TranslationStatusLocalizer, whose table has no entry for a made-up literal, so the
            // literal reached English and Japanese players untranslated. The screens render their
            // own localized "settings are not loaded" line for this case.
            snapshot = new TranslationDiagnosticsSnapshot(
                    false, "", "", "", null, false, false, -1L, -1L, "");
        } else {
            Path modelFile = config.offlineDirectory.toPath().resolve(config.offlineModel.modelFile());
            snapshot = new TranslationDiagnosticsSnapshot(
                    config.enabled,
                    config.provider,
                    provider == null ? "" : provider.id(),
                    config.targetLanguage,
                    config.offlineModel,
                    config.offlineAutoDownload,
                    config.diskCache,
                    fileSize(modelFile),
                    fileSize(config.cacheFile.toPath()),
                    status());
        }
        diagnosticsSnapshot = snapshot;
        diagnosticsSnapshotAt = now;
        return snapshot;
    }

    static Path exportDiagnostics(List<String> localizedLines) throws IOException {
        LegacyConfig config = activeConfig;
        if (config == null) {
            throw new IOException("Settings have not been loaded");
        }
        return DiagnosticsLogExporter.export(
                config.offlineDirectory.toPath().getParent()
                        .resolve("universal-translator-diagnostics"),
                localizedLines);
    }

    private static long fileSize(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : -1L;
        } catch (IOException ignored) {
            return -1L;
        }
    }

    static List<String> translateLines(List<String> originals, TextKind kind) {
        RenderTranslationSession active = session;
        LegacyConfig config = activeConfig;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (active == null || config == null || !config.allows(kind)
                || minecraft.currentScreen instanceof LegacyConfigScreen
                || minecraft.currentScreen instanceof LegacyDiagnosticsScreen
                || minecraft.currentScreen instanceof LegacyLlmConfigScreen
                || LegacyRenderContext.isTextInput()) {
            // The same read-only answer the core hands back. A bridge that renders with translation on
            // and then off must not see the list become editable: the first set/sort/add that works in
            // one state throws UnsupportedOperationException in the other, once per frame.
            return originals == null ? null : Collections.unmodifiableList(originals);
        }
        return active.lookupLines(originals, kind);
    }

    static TranslationTextColor translatedTextColor() {
        LegacyConfig config = activeConfig;
        return config == null ? TranslationTextColor.ORIGINAL : config.translatedTextColor;
    }

    static void protectOutgoingMessage(String message) {
        RECENT_USER_TEXT.remember(message);
    }

    static boolean shouldTranslateOutgoing(String message) {
        LegacyConfig config = activeConfig;
        return session != null && config != null && config.enabled && config.translateOutgoing
                && message != null && !message.trim().isEmpty() && !message.startsWith("/");
    }

    /** Serializes outgoing requests so rapidly sent chat lines keep their original order. */
    static synchronized CompletableFuture<TranslationResult> translateOutgoing(String message) {
        final RenderTranslationSession active = session;
        final LegacyConfig config = activeConfig;
        if (active == null || config == null || !shouldTranslateOutgoing(message)) {
            return CompletableFuture.completedFuture(TranslationResult.unchanged(message));
        }
        RECENT_USER_TEXT.remember(message);
        // Capture the tab-list/server literals on Minecraft's calling thread. The translation
        // itself may finish on a worker, but must not inspect client network state there.
        CompletableFuture<TranslationResult> translated = active.translateInteractive(
                message, TextKind.CHAT, config.outgoingTargetLanguage, false,
                config.streamOutgoing);
        CompletableFuture<TranslationResult> next = outgoingTail
                .handle((ignored, failure) -> null)
                .thenCompose(ignored -> translated);
        outgoingTail = next.handle((ignored, failure) -> null);
        return next;
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
        LegacyConfig current = requireActiveConfig();
        return applyHomeSettings(current, current.withHomeSettings(
                !current.enabled, current.translateVanilla, current.targetLanguage));
    }

    public static synchronized HomeQuickSettingsState toggleHomeVanilla() throws Exception {
        LegacyConfig current = requireActiveConfig();
        return applyHomeSettings(current, current.withHomeSettings(
                current.enabled, !current.translateVanilla, current.targetLanguage));
    }

    public static synchronized HomeQuickSettingsState cycleHomeTargetLanguage() throws Exception {
        LegacyConfig current = requireActiveConfig();
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
    static LegacyConfig diagnosticsConfig() {
        return activeConfig;
    }

    private static LegacyConfig requireActiveConfig() {
        LegacyConfig current = activeConfig;
        if (current == null) {
            throw new IllegalStateException("Translation settings are not loaded");
        }
        return current;
    }

    private static HomeQuickSettingsState applyHomeSettings(
            LegacyConfig previous,
            LegacyConfig updated
    ) throws Exception {
        boolean runtimeChanged = false;
        try {
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            runtimeChanged = true;
            initialize(updated);
            LegacyConfigWriter.saveOffTick(updated, null);
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

    private static HomeQuickSettingsState homeSettingsState(LegacyConfig config) {
        return config == null
                ? DISABLED_SETTINGS
                : new HomeQuickSettingsState(
                        config.enabled, config.translateVanilla, config.targetLanguage,
                        config.hudIndicatorSettings(), config.provider);
    }
}

