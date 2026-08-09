package org.universaltranslator.forge.legacy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import org.universaltranslator.core.RenderTranslationSession;
import org.universaltranslator.core.PersistentTranslationCache;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.TranslationCache;
import org.universaltranslator.core.TranslationStore;
import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationProviderStatus;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.RecentUserText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LegacyTranslationRuntime {
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
            created.setProtectedLiteralsSupplier(LegacyTranslationRuntime::playerNameSnapshot);
            session = created;
        }
    }

    static String translate(String original, TextKind kind) {
        RenderTranslationSession active = session;
        LegacyConfig config = activeConfig;
        Minecraft minecraft = Minecraft.getMinecraft();
        if (active == null || config == null || !config.allows(kind)
                || minecraft.currentScreen instanceof LegacyConfigScreen
                || LegacyLocalTextGuard.isLocalChatInput(minecraft.currentScreen, original)
                || RECENT_USER_TEXT.shouldPreserve(original)
                || LegacyVersionAccess.connection(minecraft) == null) {
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
        Minecraft minecraft = Minecraft.getMinecraft();
        NetHandlerPlayClient connection = LegacyVersionAccess.connection(minecraft);
        if (connection == null) {
            protectedPlayerNames = Collections.emptyList();
        } else {
            List<String> names = new ArrayList<String>();
            addProtectedLiteral(names, LegacyVersionAccess.localPlayerName(minecraft));
            addProtectedLiteral(names, LegacyVersionAccess.serverAddress(minecraft));
            for (NetworkPlayerInfo player : connection.getPlayerInfoMap()) {
                if (names.size() >= MAX_PROTECTED_PLAYER_NAMES) {
                    break;
                }
                if (player.getGameProfile() != null && player.getGameProfile().getName() != null) {
                    addProtectedLiteral(names, player.getGameProfile().getName());
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
        TranslationProvider provider = activeProvider;
        return provider instanceof TranslationProviderStatus
                ? ((TranslationProviderStatus) provider).status() : "";
    }

    static TranslationTextColor translatedTextColor() {
        LegacyConfig config = activeConfig;
        return config == null ? TranslationTextColor.ORIGINAL : config.translatedTextColor;
    }

    static void protectOutgoingMessage(String message) {
        RECENT_USER_TEXT.remember(message);
    }
}
