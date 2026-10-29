package org.universaltranslator.fabric;

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
import org.universaltranslator.core.TranslationStore;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.RecentUserText;
import org.universaltranslator.core.TranslationResult;
import org.universaltranslator.core.DiagnosticsLogExporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The translation runtime every Fabric tree shares.
 *
 * <p>This class used to exist seven times, once per Minecraft generation. The copies differed in
 * exactly six lines: how the client is found, how the current screen / connection / session name /
 * server address are read, and how the tab list is walked. Everything else — the provider build
 * ordering, the background reaper, the player-name snapshot, the outgoing queue — was byte
 * identical. Each generation now extends this class and answers those six questions; its
 * {@code FabricTranslationRuntime} keeps the same static entry points, so no call site changed.
 *
 * <p>The seam methods take and return {@code Object}/{@code String} on purpose: this file may not
 * mention {@code MinecraftClient}, {@code Minecraft}, {@code Screen} or any other mapped type, or
 * it could not be compiled for all seven trees at once.
 */
public abstract class FabricTranslationRuntimeBase {
    private static final long PLAYER_NAME_SNAPSHOT_MILLIS = 5_000L;
    // Match ProtectedText's bounded literal limit so large network lobbies do not silently
    // drop names after the first few tab-list pages.
    private static final int MAX_PROTECTED_PLAYER_NAMES = 1_000;

    private volatile RenderTranslationSession session;
    private volatile FabricConfig activeConfig;
    /**
     * Settings snapshot the render hooks read.
     *
     * <p>Published by {@link #initialize} and read from the render thread, so it is a volatile field
     * instead of a value rebuilt under the instance monitor: {@code initialize} holds that monitor
     * for the whole provider build, and a render hook waiting behind it is a visible frame hitch on
     * every settings save and F8 toggle.
     */
    private volatile HomeQuickSettingsState cachedHome;
    private volatile TranslationProvider activeProvider;
    private volatile List<String> protectedPlayerNames = Collections.emptyList();
    private volatile long protectedPlayerNamesExpireAt;

    /** Guards the player-name refresh so a burst of render-thread lookups rebuilds once per window. */
    private final AtomicBoolean playerNameRefresh = new AtomicBoolean();
    private final RecentUserText recentUserText = new RecentUserText();
    /**
     * True once something was remembered in {@link #recentUserText} and until the next reset.
     *
     * <p>{@code shouldPreserve} is synchronized and normalizes its argument, and the render path asks
     * it about every drawn string two to four times per frame. The queue is empty for almost every
     * frame of a normal session — it only fills after the player types a line, and then only for
     * thirty minutes — so the render path checks this flag first and never enters the monitor in the
     * common case. Written before the queue is touched and cleared with it, so a stale {@code true}
     * can only cost one lookup, never a missing match.
     */
    private volatile boolean recentUserTextPresent;
    /**
     * The instance that published the live session, for the static fast path the render bridge uses.
     *
     * <p>{@code RenderedTextBridge} is a static class that cannot reach whichever runtime the
     * generation-specific entry points built, and the bridge runs for every drawn line of every
     * frame — including the frames where translation is switched off. One process runs one client,
     * so a single published instance is enough; {@link #shutdown()} clears it.
     */
    private static volatile FabricTranslationRuntimeBase activeRuntime;
    private CompletableFuture<Void> outgoingTail = CompletableFuture.completedFuture(null);
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

    protected FabricTranslationRuntimeBase() {
    }

    /** The screen the client is showing, or {@code null}. */
    protected abstract Object currentScreen();

    /** True while the local player is typing in a chat field. */
    protected abstract boolean isLocalChatInput(String text);

    /** True while a server connection exists. */
    protected abstract boolean hasConnection();

    /** The local player's name, or {@code null} when there is no session yet. */
    protected abstract String localPlayerName();

    /** The address of the server being played on, or {@code null} in single player. */
    protected abstract String serverAddress();

    /** Every name in the tab list. Only called when player names must be protected. */
    protected abstract Iterable<String> onlinePlayerNames();

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
     * together under the instance monitor. The previous session keeps serving every lookup until
     * that instant, so switching costs no translation gap.
     *
     * <p>Close the replaced session in the background: {@code close()} interrupts the workers and
     * then waits up to five seconds for one parked in a provider read, and running that on the
     * client tick thread is what froze the game for hundreds of milliseconds to several seconds on
     * every F8 toggle and settings save.
     */
    synchronized void initialize(FabricConfig config) throws IOException {
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
                TranslationStore store = config.diskCache
                        ? new PersistentTranslationCache(config.cacheFile, 10_000)
                        : new TranslationCache(10_000);
                // The offline llama.cpp server is single threaded, so it always gets one worker no
                // matter what the configuration asks for.
                int workers = provider.id().contains("offline-llama:") ? 1 : config.translationWorkers();
                created = new RenderTranslationSession(
                        provider, TargetLanguage.AUTO, config.targetLanguage, store, workers, config.displayMode,
                        config.translateEnglishOnly);
                created.setBlockedKeywords(config.blockedKeywords);
                created.setProtectedLiteralsSupplier(this::playerNameSnapshot);
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
        recentUserText.clear();
        recentUserTextPresent = false;
        // A new configuration points at a different model and cache file, so the cached sizes are
        // meaningless for it: let the next diagnostics snapshot read them again.
        cachedBytesExpireAt = 0L;
        activeRuntime = this;
        outgoingTail = CompletableFuture.completedFuture(null);
        closeInBackground(replaced);
    }

    /**
     * Hands a replaced session to the shared reaper so its teardown never runs on the caller's
     * thread. {@link #shutdown()} stays synchronous on purpose: it runs while the game is closing,
     * where blocking is free, and it closes whichever session is current at that moment, which is
     * never one that was already handed to the reaper.
     */
    private static void closeInBackground(final RenderTranslationSession replaced) {
        if (replaced == null) {
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
                    }
                }
            });
        } catch (Throwable rejected) {
            // The executor only rejects once it has been shut down, which this class never does.
            // Leaving the replaced session unclosed still beats blocking the caller.
        }
    }

    /**
     * True when one of this mod's own screens is open.
     *
     * <p>Those screens must never translate their own labels, so every render path asks this. It
     * takes {@code Object} on purpose: the accessor is {@code screen}, {@code currentScreen} or
     * {@code gui.screen()} depending on the Minecraft version, and none of them needs a cast.
     */
    private static boolean isTranslatorScreen(Object screen) {
        return screen instanceof UniversalTranslatorConfigScreen
                || screen instanceof UniversalTranslatorDiagnosticsScreen
                || screen instanceof UniversalTranslatorLlmConfigScreen;
    }

    /**
     * True when a render-time lookup can produce a translation at all right now.
     *
     * <p>A coarse, allocation-free answer for the hooks that have to do real work before they can
     * ask {@link #translateForRender}: flattening an {@code OrderedText} into a string costs three
     * allocations per call, and the hooks run for every drawn line whether translation is enabled
     * or not. This is only ever a fast path — the kind-by-kind and per-screen decisions stay in
     * {@code translateForRender}.
     */
    public static boolean renderTranslationEnabled() {
        FabricTranslationRuntimeBase runtime = activeRuntime;
        if (runtime == null) {
            return false;
        }
        FabricConfig config = runtime.activeConfig;
        return runtime.session != null && config != null && config.enabled;
    }

    String translateForRender(String original, TextKind kind) {
        RenderTranslationSession active = session;
        FabricConfig config = activeConfig;
        if (active == null || config == null || !config.allows(kind)
                || (!config.translateVanilla && kind == TextKind.OTHER
                && MinecraftContentScope.isVanillaScreen(currentScreen()))
                || isTranslatorScreen(currentScreen())
                || isLocalChatInput(original)
                || (recentUserTextPresent && recentUserText.shouldPreserve(original))) {
            return original;
        }
        return active.lookup(original, kind);
    }

    synchronized void shutdown() {
        RenderTranslationSession active = session;
        session = null;
        activeProvider = null;
        protectedPlayerNames = Collections.emptyList();
        protectedPlayerNamesExpireAt = 0L;
        recentUserText.clear();
        recentUserTextPresent = false;
        if (activeRuntime == this) {
            activeRuntime = null;
        }
        outgoingTail = CompletableFuture.completedFuture(null);
        if (active != null) {
            active.close();
        }
    }

    private List<String> playerNameSnapshot() {
        long now = System.currentTimeMillis();
        List<String> snapshot = protectedPlayerNames;
        if (now < protectedPlayerNamesExpireAt) {
            return snapshot;
        }
        // Refreshed outside the instance monitor: the coordinator asks for this on every submitted
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

    private List<String> refreshPlayerNames(long now) {
        boolean protectPlayerNames = activeConfig == null || !activeConfig.translatePlayerNames;
        if (!hasConnection()) {
            protectedPlayerNames = Collections.emptyList();
        } else {
            // Hash-set dedup keeps this O(n); the old linear scan was O(n^2) on the
            // render thread with up to MAX_PROTECTED_PLAYER_NAMES tab-list entries.
            LinkedHashSet<String> names = new LinkedHashSet<String>();
            if (protectPlayerNames) {
                addProtectedLiteral(names, localPlayerName());
            }
            addProtectedLiteral(names, serverAddress());
            if (protectPlayerNames) {
                for (String name : onlinePlayerNames()) {
                    if (names.size() >= MAX_PROTECTED_PLAYER_NAMES) {
                        break;
                    }
                    addProtectedLiteral(names, name);
                }
            }
            protectedPlayerNames = Collections.unmodifiableList(new ArrayList<String>(names));
        }
        protectedPlayerNamesExpireAt = now + PLAYER_NAME_SNAPSHOT_MILLIS;
        return protectedPlayerNames;
    }

    private static void addProtectedLiteral(Set<String> values, String value) {
        if (value == null) {
            return;
        }
        String normalized = value.trim();
        if (!normalized.isEmpty() && normalized.length() <= 255
                && values.size() < MAX_PROTECTED_PLAYER_NAMES && !values.contains(normalized)) {
            values.add(normalized);
        }
    }

    String status() {
        RenderTranslationSession active = session;
        TranslationProvider provider = activeProvider;
        String providerStatus = provider instanceof TranslationProviderStatus
                ? ((TranslationProviderStatus) provider).status() : "";
        // The offline provider already has the specific startup diagnostic. Prefer it over the
        // session's generic wrapper so one failure cannot alternate between two chat messages.
        if (providerStatus.startsWith("离线翻译失败")) {
            return providerStatus;
        }
        if (active != null && !active.lastFailureStatus().isEmpty()) {
            return active.lastFailureStatus();
        }
        return providerStatus;
    }

    /**
     * File sizes the diagnostics snapshot reports, refreshed at most once a second.
     *
     * <p>Both values are two {@code stat} calls each ({@code isRegularFile} then {@code size}), and
     * the snapshot is built on whichever thread asked for it — which is the render thread while the
     * diagnostics screen is open. Cached because a two-line read-out does not need a live file
     * system: the numbers are display-only, and a second of staleness is invisible next to the cost
     * of blocking a frame on a network drive.
     */
    private static final long FILE_SIZE_CACHE_MILLIS = 1_000L;
    private volatile long cachedModelBytes = -1L;
    private volatile long cachedCacheBytes = -1L;
    private volatile long cachedBytesExpireAt;

    TranslationDiagnosticsSnapshot diagnostics() {
        FabricConfig config = activeConfig;
        TranslationProvider provider = activeProvider;
        if (config == null) {
            return new TranslationDiagnosticsSnapshot(
                    false, "", "", "", null, false, false, -1L, -1L, "尚未载入设置");
        }
        Path modelFile = config.offlineDirectory.resolve(config.offlineModel.modelFile());
        long now = System.currentTimeMillis();
        if (now >= cachedBytesExpireAt) {
            cachedModelBytes = fileSize(modelFile);
            cachedCacheBytes = fileSize(config.cacheFile);
            cachedBytesExpireAt = now + FILE_SIZE_CACHE_MILLIS;
        }
        return new TranslationDiagnosticsSnapshot(
                config.enabled,
                config.provider,
                provider == null ? "" : provider.id(),
                config.targetLanguage,
                config.offlineModel,
                config.offlineAutoDownload,
                config.diskCache,
                cachedModelBytes,
                cachedCacheBytes,
                status());
    }

    Path exportDiagnostics(List<String> localizedLines) throws IOException {
        FabricConfig config = activeConfig;
        if (config == null) {
            throw new IOException("Settings have not been loaded");
        }
        return DiagnosticsLogExporter.export(
                config.offlineDirectory.getParent().resolve("universal-translator-diagnostics"),
                localizedLines);
    }

    private static long fileSize(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.size(file) : -1L;
        } catch (IOException ignored) {
            return -1L;
        }
    }

    List<String> translateLinesForRender(List<String> originals, TextKind kind) {
        RenderTranslationSession active = session;
        FabricConfig config = activeConfig;
        if (active == null || config == null || !config.allows(kind)
                || isTranslatorScreen(currentScreen())
                || TranslationRenderContext.isTextInput()) {
            return originals;
        }
        return active.lookupLines(originals, kind);
    }

    TranslationTextColor translatedTextColor() {
        FabricConfig config = activeConfig;
        return config == null ? TranslationTextColor.ORIGINAL : config.translatedTextColor;
    }

    void protectOutgoingMessage(String message) {
        // Set before the queue: a render hook that reads the flag after this point either sees the
        // entry or pays one lookup, never the other way round.
        recentUserTextPresent = true;
        recentUserText.remember(message);
    }

    boolean shouldTranslateOutgoing(String message) {
        FabricConfig config = activeConfig;
        return session != null && config != null && config.enabled && config.translateOutgoing
                && message != null && !message.trim().isEmpty() && !message.startsWith("/");
    }

    /**
     * Starts an outgoing translation and chains it behind the previous one for completion order.
     *
     * <p>The request itself starts here, on the calling (client) thread: {@code translateInteractive}
     * captures the tab-list and server literals while it runs, and those must not be read from a
     * worker. {@code outgoingTail} therefore orders the resulting futures, not the requests — a burst
     * of chat lines does occupy several provider slots at once, and what the chain guarantees is that
     * the completions are delivered in the order the lines were typed. The previous comment claimed
     * the requests were serialized, which sent anyone debugging provider load in the wrong direction.
     */
    synchronized CompletableFuture<TranslationResult> translateOutgoing(String message) {
        final RenderTranslationSession active = session;
        final FabricConfig config = activeConfig;
        if (active == null || config == null || !shouldTranslateOutgoing(message)) {
            return CompletableFuture.completedFuture(TranslationResult.unchanged(message));
        }
        recentUserTextPresent = true;
        recentUserText.remember(message);
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
     * Settings snapshot for the render hooks, taken without entering the instance monitor.
     *
     * <p>The HUD reads this five times per frame, so it must not be {@code synchronized}: the build
     * that publishes it ({@link #initialize}) holds that monitor for the whole provider
     * construction. The fallback only runs before the first {@code initialize}, when no HUD exists
     * yet, and reads the same {@code activeConfig} the previous synchronized body read.
     */
    public HomeQuickSettingsState homeSettings() {
        HomeQuickSettingsState snapshot = cachedHome;
        return snapshot == null ? homeSettingsState(activeConfig) : snapshot;
    }

    public synchronized HomeQuickSettingsState toggleHomeEnabled() throws Exception {
        FabricConfig current = requireActiveConfig();
        return applyHomeSettings(current, current.withHomeSettings(
                !current.enabled, current.translateVanilla, current.targetLanguage));
    }

    public synchronized HomeQuickSettingsState toggleHomeVanilla() throws Exception {
        FabricConfig current = requireActiveConfig();
        return applyHomeSettings(current, current.withHomeSettings(
                current.enabled, !current.translateVanilla, current.targetLanguage));
    }

    public synchronized HomeQuickSettingsState cycleHomeTargetLanguage() throws Exception {
        FabricConfig current = requireActiveConfig();
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
    FabricConfig diagnosticsConfig() {
        return activeConfig;
    }

    private FabricConfig requireActiveConfig() {
        FabricConfig current = activeConfig;
        if (current == null) {
            throw new IllegalStateException("Translation settings are not loaded");
        }
        return current;
    }

    private HomeQuickSettingsState applyHomeSettings(
            FabricConfig previous,
            FabricConfig updated
    ) throws Exception {
        boolean runtimeChanged = false;
        try {
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            runtimeChanged = true;
            initialize(updated);
            updated.save();
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

    private static HomeQuickSettingsState homeSettingsState(FabricConfig config) {
        return config == null
                ? new HomeQuickSettingsState(false, true, TargetLanguage.SIMPLIFIED_CHINESE)
                : new HomeQuickSettingsState(
                        config.enabled, config.translateVanilla, config.targetLanguage,
                        config.hudIndicatorSettings(), config.provider);
    }
}
