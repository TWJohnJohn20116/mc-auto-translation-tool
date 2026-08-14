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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class LegacyTranslationRuntime {
    private static final long PLAYER_NAME_SNAPSHOT_MILLIS = 5_000L;
    // Match ProtectedText's bounded literal limit so large network lobbies do not silently
    // drop names after the first few tab-list pages.
    private static final int MAX_PROTECTED_PLAYER_NAMES = 1_000;

    private static volatile RenderTranslationSession session;
    private static volatile LegacyConfig activeConfig;
    private static volatile TranslationProvider activeProvider;
    private static volatile List<String> protectedPlayerNames = Collections.emptyList();
    private static volatile long protectedPlayerNamesExpireAt;
    private static final RecentUserText RECENT_USER_TEXT = new RecentUserText();
    private static CompletableFuture<Void> outgoingTail = CompletableFuture.completedFuture(null);

    private LegacyTranslationRuntime() {
    }

    static synchronized void initialize(LegacyConfig config) throws IOException {
        shutdown();
        activeConfig = config;
        if (config.enabled) {
            TranslationStore store = config.diskCache
                    ? new PersistentTranslationCache(config.cacheFile.toPath(), 10_000)
                    : new TranslationCache(10_000);
            TranslationProvider provider = config.createProvider();
            activeProvider = provider;
            int workers = provider.id().contains("offline-llama:") ? 1 : 2;
            RenderTranslationSession created = new RenderTranslationSession(
                    provider, "auto", config.targetLanguage, store, workers, config.displayMode,
                    config.translateEnglishOnly);
            created.setBlockedKeywords(config.blockedKeywords);
            created.setProtectedLiteralsSupplier(LegacyTranslationRuntime::playerNameSnapshot);
            session = created;
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
                || LegacyLocalTextGuard.isLocalChatInput(minecraft.currentScreen, original)
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
        Minecraft minecraft = Minecraft.getMinecraft();
        boolean protectPlayerNames = activeConfig == null || !activeConfig.translatePlayerNames;
        NetHandlerPlayClient connection = LegacyVersionAccess.connection(minecraft);
        if (connection == null) {
            protectedPlayerNames = Collections.emptyList();
        } else {
            List<String> names = new ArrayList<String>();
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
        // Preserve the offline provider's concrete process error instead of alternating it with
        // the session's generic "translation failed" wrapper on successive client ticks.
        if (providerStatus.startsWith("离线翻译失败")) {
            return providerStatus;
        }
        if (active != null && !active.lastFailureStatus().isEmpty()) {
            return active.lastFailureStatus();
        }
        return providerStatus;
    }

    static TranslationDiagnosticsSnapshot diagnostics() {
        LegacyConfig config = activeConfig;
        TranslationProvider provider = activeProvider;
        if (config == null) {
            return new TranslationDiagnosticsSnapshot(
                    false, "", "", "", null, false, false, -1L, -1L, "尚未载入设置");
        }
        Path modelFile = config.offlineDirectory.toPath().resolve(config.offlineModel.modelFile());
        return new TranslationDiagnosticsSnapshot(
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
                || LegacyRenderContext.isTextInput()) {
            return originals;
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

    private static HomeQuickSettingsState homeSettingsState(LegacyConfig config) {
        return config == null
                ? new HomeQuickSettingsState(false, true, TargetLanguage.SIMPLIFIED_CHINESE)
                : new HomeQuickSettingsState(
                        config.enabled, config.translateVanilla, config.targetLanguage);
    }
}
