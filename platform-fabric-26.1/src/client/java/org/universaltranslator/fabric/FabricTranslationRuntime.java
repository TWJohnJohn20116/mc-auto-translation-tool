package org.universaltranslator.fabric;

import net.minecraft.client.Minecraft;
import org.universaltranslator.core.RenderTranslationSession;
import org.universaltranslator.core.PersistentTranslationCache;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.TranslationCache;
import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationProviderStatus;
import org.universaltranslator.core.TranslationStore;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.RecentUserText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class FabricTranslationRuntime {
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
        created.setProtectedLiteralsSupplier(FabricTranslationRuntime::playerNameSnapshot);
        session = created;
    }

    static String translateForRender(String original, TextKind kind) {
        RenderTranslationSession active = session;
        FabricConfig config = activeConfig;
        Minecraft client = Minecraft.getInstance();
        if (active == null || config == null || !config.allows(kind)
                || client.screen instanceof UniversalTranslatorConfigScreen
                || FabricLocalTextGuard.isLocalChatInput(client, original)
                || RECENT_USER_TEXT.shouldPreserve(original)
                || client.level == null || client.getConnection() == null) {
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
        if (client.getConnection() == null) {
            protectedPlayerNames = Collections.emptyList();
        } else {
            List<String> names = new ArrayList<String>();
            addProtectedLiteral(names, client.getUser().getName());
            if (client.getCurrentServer() != null) {
                addProtectedLiteral(names, client.getCurrentServer().ip);
            }
            client.getConnection().getOnlinePlayers().forEach(entry -> {
                if (names.size() >= MAX_PROTECTED_PLAYER_NAMES) {
                    return;
                }
                String name = entry.getProfile().name();
                addProtectedLiteral(names, name);
            });
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
        if (active != null && !active.lastFailureStatus().isEmpty()) {
            return active.lastFailureStatus();
        }
        TranslationProvider provider = activeProvider;
        return provider instanceof TranslationProviderStatus
                ? ((TranslationProviderStatus) provider).status() : "";
    }

    static List<String> translateLinesForRender(List<String> originals, TextKind kind) {
        RenderTranslationSession active = session;
        FabricConfig config = activeConfig;
        Minecraft client = Minecraft.getInstance();
        if (active == null || config == null || !config.allows(kind)
                || client.screen instanceof UniversalTranslatorConfigScreen
                || TranslationRenderContext.isTextInput()
                || client.level == null || client.getConnection() == null) {
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
}
