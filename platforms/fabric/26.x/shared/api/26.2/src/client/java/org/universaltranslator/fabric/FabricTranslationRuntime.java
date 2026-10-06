package org.universaltranslator.fabric;

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
import org.universaltranslator.core.TranslationResult;
import org.universaltranslator.core.TranslationStore;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.RecentUserText;
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

public final class FabricTranslationRuntime {
    private static final long PLAYER_NAME_SNAPSHOT_MILLIS = 5_000L;
    // Match ProtectedText's bounded literal limit so large network lobbies do not silently
    // drop names after the first few tab-list pages.
    private static final int MAX_PROTECTED_PLAYER_NAMES = 1_000;

    private static volatile RenderTranslationSession session;
    private static volatile FabricConfig activeConfig;
    private static volatile TranslationProvider activeProvider;
    private static volatile List<String> protectedPlayerNames = Collections.emptyList();
    private static volatile long protectedPlayerNamesExpireAt;
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

    private FabricTranslationRuntime() {
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
    static synchronized void initialize(FabricConfig config) throws IOException {
        // Build the replacement without touching session/activeConfig/activeProvider: if anything
        // here throws, the caller still has the previous, fully working session.
        TranslationProvider createdProvider = null;
        RenderTranslationSession created = null;
        if (config.enabled) {
            TranslationProvider provider = config.createProvider();
            TranslationStore store = config.diskCache
                    ? new PersistentTranslationCache(config.cacheFile, 10_000)
                    : new TranslationCache(10_000);
            int workers = provider.id().contains("offline-llama:") ? 1 : 2;
            created = new RenderTranslationSession(
                    provider, "auto", config.targetLanguage, store, workers, config.displayMode,
                    config.translateEnglishOnly);
            created.setBlockedKeywords(config.blockedKeywords);
            created.setProtectedLiteralsSupplier(FabricTranslationRuntime::playerNameSnapshot);
            createdProvider = provider;
        }
        // Swap last, in one synchronized block. The replaced session is captured in a local
        // variable because the field no longer refers to it after the next three assignments.
        RenderTranslationSession replaced = session;
        activeConfig = config;
        activeProvider = createdProvider;
        session = created;
        protectedPlayerNames = Collections.emptyList();
        protectedPlayerNamesExpireAt = 0L;
        RECENT_USER_TEXT.clear();
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

    static String translateForRender(String original, TextKind kind) {
        RenderTranslationSession active = session;
        FabricConfig config = activeConfig;
        Minecraft client = Minecraft.getInstance();
        if (active == null || config == null || !config.allows(kind)
                || (!config.translateVanilla && kind == TextKind.OTHER
                && MinecraftContentScope.isVanillaScreen(FabricLocalTextGuard.currentScreen(client)))
                || client.gui.screen() instanceof UniversalTranslatorConfigScreen
                || client.gui.screen() instanceof UniversalTranslatorDiagnosticsScreen
                || client.gui.screen() instanceof UniversalTranslatorLlmConfigScreen
                || FabricLocalTextGuard.isLocalChatInput(client, original)
                || RECENT_USER_TEXT.shouldPreserve(original)) {
            return original;
        }
        return active.lookup(original, kind);
    }

    static synchronized void shutdown() {
        RenderTranslationSession active = session;
        session = null;
        activeProvider = null;
        protectedPlayerNames = Collections.emptyList();
        protectedPlayerNamesExpireAt = 0L;
        RECENT_USER_TEXT.clear();
        outgoingTail = CompletableFuture.completedFuture(null);
        if (active != null) {
            active.close();
        }
    }

    private static synchronized List<String> playerNameSnapshot() {
        long now = System.currentTimeMillis();
        if (now < protectedPlayerNamesExpireAt) {
            return protectedPlayerNames;
        }
        Minecraft client = Minecraft.getInstance();
        boolean protectPlayerNames = activeConfig == null || !activeConfig.translatePlayerNames;
        if (client.getConnection() == null) {
            protectedPlayerNames = Collections.emptyList();
        } else {
            // Hash-set dedup keeps this O(n); the old linear scan was O(n^2) on the
            // render thread with up to MAX_PROTECTED_PLAYER_NAMES tab-list entries.
            LinkedHashSet<String> names = new LinkedHashSet<String>();
            if (protectPlayerNames) {
                addProtectedLiteral(names, client.getUser().getName());
            }
            if (client.getCurrentServer() != null) {
                addProtectedLiteral(names, client.getCurrentServer().ip);
            }
            if (protectPlayerNames) {
                client.getConnection().getOnlinePlayers().forEach(entry -> {
                    if (names.size() >= MAX_PROTECTED_PLAYER_NAMES) {
                        return;
                    }
                    String name = entry.getProfile().name();
                    addProtectedLiteral(names, name);
                });
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

    static String status() {
        RenderTranslationSession active = session;
        TranslationProvider provider = activeProvider;
        String providerStatus = provider instanceof TranslationProviderStatus
                ? ((TranslationProviderStatus) provider).status() : "";
        if (providerStatus.startsWith("离线翻译失败")) {
            return providerStatus;
        }
        if (active != null && !active.lastFailureStatus().isEmpty()) {
            return active.lastFailureStatus();
        }
        return providerStatus;
    }

    static TranslationDiagnosticsSnapshot diagnostics() {
        FabricConfig config = activeConfig;
        TranslationProvider provider = activeProvider;
        if (config == null) {
            return new TranslationDiagnosticsSnapshot(
                    false, "", "", "", null, false, false, -1L, -1L, "尚未载入设置");
        }
        Path modelFile = config.offlineDirectory.resolve(config.offlineModel.modelFile());
        return new TranslationDiagnosticsSnapshot(
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

    static Path exportDiagnostics(List<String> localizedLines) throws IOException {
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

    static List<String> translateLinesForRender(List<String> originals, TextKind kind) {
        RenderTranslationSession active = session;
        FabricConfig config = activeConfig;
        Minecraft client = Minecraft.getInstance();
        if (active == null || config == null || !config.allows(kind)
                || client.gui.screen() instanceof UniversalTranslatorConfigScreen
                || client.gui.screen() instanceof UniversalTranslatorDiagnosticsScreen
                || client.gui.screen() instanceof UniversalTranslatorLlmConfigScreen
                || TranslationRenderContext.isTextInput()) {
            return originals;
        }
        return active.lookupLines(originals, kind);
    }

    static TranslationTextColor translatedTextColor() {
        FabricConfig config = activeConfig;
        return config == null ? TranslationTextColor.ORIGINAL : config.translatedTextColor;
    }

    static void protectOutgoingMessage(String message) {
        RECENT_USER_TEXT.remember(message);
    }

    static boolean shouldTranslateOutgoing(String message) {
        FabricConfig config = activeConfig;
        return session != null && config != null && config.enabled && config.translateOutgoing
                && message != null && !message.trim().isEmpty() && !message.startsWith("/");
    }

    /** Serializes outgoing requests so rapidly sent chat lines keep their original order. */
    static synchronized CompletableFuture<TranslationResult> translateOutgoing(String message) {
        final RenderTranslationSession active = session;
        final FabricConfig config = activeConfig;
        if (active == null || config == null || !shouldTranslateOutgoing(message)) {
            return CompletableFuture.completedFuture(TranslationResult.unchanged(message));
        }
        RECENT_USER_TEXT.remember(message);
        CompletableFuture<TranslationResult> translated = active.translateInteractive(
                message, TextKind.CHAT, config.outgoingTargetLanguage, false);
        CompletableFuture<TranslationResult> next = outgoingTail
                .handle((ignored, failure) -> null)
                .thenCompose(ignored -> translated);
        outgoingTail = next.handle((ignored, failure) -> null);
        return next;
    }

    public static synchronized HomeQuickSettingsState homeSettings() {
        return homeSettingsState(activeConfig);
    }

    public static synchronized HomeQuickSettingsState toggleHomeEnabled() throws Exception {
        FabricConfig current = requireActiveConfig();
        return applyHomeSettings(current, current.withHomeSettings(
                !current.enabled, current.translateVanilla, current.targetLanguage));
    }

    public static synchronized HomeQuickSettingsState toggleHomeVanilla() throws Exception {
        FabricConfig current = requireActiveConfig();
        return applyHomeSettings(current, current.withHomeSettings(
                current.enabled, !current.translateVanilla, current.targetLanguage));
    }

    public static synchronized HomeQuickSettingsState cycleHomeTargetLanguage() throws Exception {
        FabricConfig current = requireActiveConfig();
        return applyHomeSettings(current, current.withHomeSettings(
                current.enabled, current.translateVanilla,
                TargetLanguage.nextPreset(current.targetLanguage)));
    }

    private static FabricConfig requireActiveConfig() {
        FabricConfig current = activeConfig;
        if (current == null) {
            throw new IllegalStateException("Translation settings are not loaded");
        }
        return current;
    }

    private static HomeQuickSettingsState applyHomeSettings(
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
                        config.enabled, config.translateVanilla, config.targetLanguage);
    }
}
