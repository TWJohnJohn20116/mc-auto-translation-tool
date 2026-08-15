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
import java.util.List;
import java.util.concurrent.CompletableFuture;

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

    private FabricTranslationRuntime() {
    }

    static synchronized void initialize(FabricConfig config) throws IOException {
        shutdown();
        activeConfig = config;
        if (!config.enabled) {
            return;
        }
        TranslationProvider provider = config.createProvider();
        activeProvider = provider;
        TranslationStore store = config.diskCache
                ? new PersistentTranslationCache(config.cacheFile, 10_000)
                : new TranslationCache(10_000);
        int workers = provider.id().contains("offline-llama:") ? 1 : 2;
        RenderTranslationSession created = new RenderTranslationSession(
                provider, "auto", config.targetLanguage, store, workers, config.displayMode,
                config.translateEnglishOnly);
        created.setBlockedKeywords(config.blockedKeywords);
        created.setProtectedLiteralsSupplier(FabricTranslationRuntime::playerNameSnapshot);
        session = created;
    }

    static String translateForRender(String original, TextKind kind) {
        RenderTranslationSession active = session;
        FabricConfig config = activeConfig;
        Minecraft client = Minecraft.getInstance();
        if (active == null || config == null || !config.allows(kind)
                || (!config.translateVanilla && kind == TextKind.OTHER
                && MinecraftContentScope.isVanillaScreen(FabricLocalTextGuard.currentScreen(client)))
                || client.screen instanceof UniversalTranslatorConfigScreen
                || client.screen instanceof UniversalTranslatorDiagnosticsScreen
                || client.screen instanceof UniversalTranslatorLlmConfigScreen
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
        if (client.getNetworkHandler() == null) {
            protectedPlayerNames = Collections.emptyList();
        } else {
            List<String> names = new ArrayList<String>();
            if (protectPlayerNames) {
                addProtectedLiteral(names, OrnitheClientAccess.sessionUsername());
            }
            addProtectedLiteral(names, OrnitheClientAccess.currentServerAddress());
            if (protectPlayerNames) {
                for (Object raw : client.getNetworkHandler().onlinePlayers) {
                    if (names.size() >= MAX_PROTECTED_PLAYER_NAMES) {
                        break;
                    }
                    if (raw instanceof net.minecraft.client.network.PlayerInfo) {
                        net.minecraft.client.network.PlayerInfo entry =
                                (net.minecraft.client.network.PlayerInfo) raw;
                        addProtectedLiteral(names, entry.name);
                    }
                }
            }
            protectedPlayerNames = Collections.unmodifiableList(names);
        }
        protectedPlayerNamesExpireAt = now + PLAYER_NAME_SNAPSHOT_MILLIS;
        return protectedPlayerNames;
    }

    private static void addProtectedLiteral(List<String> values, String value) {
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
                || client.screen instanceof UniversalTranslatorConfigScreen
                || client.screen instanceof UniversalTranslatorDiagnosticsScreen
                || client.screen instanceof UniversalTranslatorLlmConfigScreen
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
        // Capture the tab-list/server literals on Minecraft's calling thread. The translation
        // itself may finish on a worker, but must not inspect client network state there.
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
