package org.universaltranslator.fabric;

import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HudIndicatorColor;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorSettings;
import org.universaltranslator.core.HudIndicatorVisibility;
import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationDisplayMode;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationQuality;
import org.universaltranslator.core.TranslationStats;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.HotkeyKeys;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.LocalConfigSecurity;
import org.universaltranslator.core.OfflineModel;
import org.universaltranslator.core.TranslationBlocklist;
import org.universaltranslator.core.provider.FallbackTranslationProvider;
import org.universaltranslator.core.provider.LlamaCppOfflineProvider;
import org.universaltranslator.core.provider.OnlineProviderConfig;
import org.universaltranslator.core.TargetLanguage;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

final class FabricConfig {
    private static final String FILE_NAME = "universal-translator.properties";
    /** Hotkey used when the configured name is missing or blank; matches {@code HotkeyKeys}. */
    private static final String DEFAULT_SETTINGS_KEY = "U";
    private static final String DEFAULT_TOGGLE_KEY = "F8";
    /** Suffix pattern of the copy a damaged file is preserved as; one write per start at most. */
    private static final DateTimeFormatter CORRUPT_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /**
     * Name of the file the last {@link #load(Path)} quarantined, or {@code null} when it read a
     * healthy file; see the modern copy of this class for why the bootstrap is told about it.
     */
    private static volatile String corruptionBackup;
    /**
     * Configuration files whose damaged contents could not be moved aside; see the modern copy of
     * this class. Until a load can read the file again, no save may replace the only copy of the
     * player's settings with the defaults that load fell back to.
     */
    private static final Set<Path> UNQUARANTINED =
            Collections.synchronizedSet(new java.util.HashSet<Path>());

    final boolean enabled;
    final boolean translateChat;
    final boolean translateOther;
    final boolean translateVanilla;
    final boolean translateOutgoing;
    final boolean streamOutgoing;
    final boolean translatePlayerNames;
    final boolean animatedUi;
    final String blockedKeywords;
    final String targetLanguage;
    final String outgoingTargetLanguage;
    final TranslationDisplayMode displayMode;
    final boolean translateEnglishOnly;
    final TranslationTextColor translatedTextColor;
    final String provider;
    final String endpoint;
    final String apiKey;
    final String tencentSecretId;
    final String tencentSecretKey;
    final String tencentModel;
    final String llmEndpoint;
    final String llmApiKey;
    final String llmModel;
    final boolean offlineAutoDownload;
    final OfflineModel offlineModel;
    final boolean apiFallback;
    final String apiFallbackProvider;
    final Path offlineDirectory;
    final boolean diskCache;
    final Path cacheFile;
    final boolean hudIndicator;
    final HudIndicatorCorner hudIndicatorCorner;
    final int hudIndicatorSize;
    final int hudIndicatorMargin;
    final HudIndicatorColor hudIndicatorColor;
    final HudIndicatorContent hudIndicatorContent;
    final HudIndicatorVisibility hudIndicatorVisibility;
    final int hudIndicatorOffsetX;
    final int hudIndicatorOffsetY;
    /**
     * Configured hotkey names, kept as the raw property values.
     *
     * <p>They live in the snapshot rather than only in the static {@link HotkeyKeys} because every
     * {@code withXxx} method rebuilds from {@link #toProperties()}, and that rebuild used to reach
     * the global holder for these two values. The keys were not part of {@code toProperties()} at
     * all, so the rebuilt object's constructor installed the defaults U/F8 over the player's
     * choice, and the next {@code save()} wrote that default snapshot back — deleting the two
     * lines from the file. Owning the values here keeps a rebuild a pure function of the file.
     */
    final String settingsKey;
    final String toggleKey;
    private final OnlineProviderConfig onlineProviderConfig;
    private final Path configFile;
    /**
     * The advanced-panel keys ({@code translation-quality}, {@code custom-system-prompt},
     * {@code debug-log}) this snapshot changed itself, and must therefore keep.
     *
     * <p>{@code toProperties()} re-reads those keys from disk so a settings screen holding an older
     * snapshot cannot undo a change the diagnostics panel made meanwhile. A snapshot that wrote one
     * of them on purpose claims exactly that key here, which is what stops the re-read from restoring
     * the value it just replaced. The claim is carried through the {@code withXxx} chain, so a later
     * rebuild — including the one {@code save()} performs — still writes the new value.
     */
    private final Set<String> ownedPromptKeys;

    private FabricConfig(Properties properties, Path configFile, Path cacheFile) {
        this(properties, configFile, cacheFile, Collections.<String>emptySet());
    }

    /**
     * @param ownedPromptKeys advanced-panel keys this snapshot wrote itself and must not re-read
     */
    private FabricConfig(
            Properties properties, Path configFile, Path cacheFile, Set<String> ownedPromptKeys) {
        this.ownedPromptKeys = Collections.unmodifiableSet(
                new LinkedHashSet<String>(ownedPromptKeys));
        this.enabled = Boolean.parseBoolean(properties.getProperty("enabled", "false"));
        this.translateChat = Boolean.parseBoolean(properties.getProperty("translate-chat", "true"));
        this.translateOther = Boolean.parseBoolean(properties.getProperty("translate-other", "true"));
        this.translateVanilla = Boolean.parseBoolean(
                properties.getProperty("translate-vanilla", "true"));
        this.translateOutgoing = Boolean.parseBoolean(
                properties.getProperty("translate-outgoing", "false"));
        this.streamOutgoing = Boolean.parseBoolean(
                properties.getProperty("stream-outgoing", "true"));
        this.translatePlayerNames = Boolean.parseBoolean(
                properties.getProperty("translate-player-names", "false"));
        this.animatedUi = Boolean.parseBoolean(properties.getProperty("animated-ui", "true"));
        this.blockedKeywords = boundedKeywords(properties.getProperty("blocked-keywords", ""));
        // Fabric 1.0-1.13.2 has no key-binding API (the 1.13 tree does not even depend on Fabric
        // API), so the hotkeys are read from this file instead of the vanilla controls screen.
        // Read into the snapshot only: publishing them to the global HotkeyKeys is load()'s job, so
        // a rebuild cannot republish (or overwrite) them as a side effect of being constructed.
        this.settingsKey = hotkey("settings-key",
                properties.getProperty("settings-key", DEFAULT_SETTINGS_KEY),
                DEFAULT_SETTINGS_KEY);
        this.toggleKey = hotkey("toggle-key",
                properties.getProperty("toggle-key", DEFAULT_TOGGLE_KEY),
                DEFAULT_TOGGLE_KEY);
        this.targetLanguage = properties.getProperty("target-language", TargetLanguage.SIMPLIFIED_CHINESE).trim();
        this.outgoingTargetLanguage = properties.getProperty(
                "outgoing-target-language", "en").trim();
        this.displayMode = parseField("display-mode",
                properties.getProperty("display-mode", "translated-only"),
                TranslationDisplayMode.TRANSLATED_ONLY, TranslationDisplayMode::fromConfig);
        this.translateEnglishOnly = Boolean.parseBoolean(
                properties.getProperty("translate-english-only", "true"));
        this.translatedTextColor = parseField("translated-text-color",
                properties.getProperty("translated-text-color", "original"),
                TranslationTextColor.ORIGINAL, TranslationTextColor::fromConfig);
        this.provider = properties.getProperty("provider", "offline").trim();
        this.endpoint = properties.getProperty(
                "libretranslate-endpoint", "http://127.0.0.1:5000/translate").trim();
        this.apiKey = properties.getProperty("api-key", "").trim();
        this.tencentSecretId = properties.getProperty("tencent-secret-id", "").trim();
        this.tencentSecretKey = properties.getProperty("tencent-secret-key", "").trim();
        this.tencentModel = properties.getProperty(
                "tencent-model", "hunyuan-translation-lite").trim();
        this.llmEndpoint = properties.getProperty(
                "llm-api-endpoint", "http://127.0.0.1:8080/v1/chat/completions").trim();
        this.llmApiKey = properties.getProperty("llm-api-key", "").trim();
        this.llmModel = properties.getProperty("llm-api-model", "local-model").trim();
        this.offlineAutoDownload = Boolean.parseBoolean(
                properties.getProperty("offline-auto-download", "true"));
        this.offlineModel = parseField("offline-model",
                properties.getProperty("offline-model", "lite"),
                OfflineModel.LITE, OfflineModel::fromConfig);
        this.apiFallback = Boolean.parseBoolean(properties.getProperty("api-fallback", "false"));
        this.apiFallbackProvider = properties.getProperty(
                "api-fallback-provider", "libretranslate").trim();
        this.diskCache = Boolean.parseBoolean(properties.getProperty("disk-cache", "true"));
        this.hudIndicator = Boolean.parseBoolean(properties.getProperty("hud-indicator", "true"));
        this.hudIndicatorCorner = parseField("hud-indicator-corner",
                properties.getProperty("hud-indicator-corner", "top-left"),
                HudIndicatorCorner.TOP_LEFT, HudIndicatorCorner::fromConfig);
        this.hudIndicatorSize = parseBoundedInt(
                properties.getProperty("hud-indicator-size", "6"), 6,
                HudIndicatorSettings.MIN_SIZE, HudIndicatorSettings.MAX_SIZE);
        this.hudIndicatorMargin = parseBoundedInt(
                properties.getProperty("hud-indicator-margin", "4"), 4,
                HudIndicatorSettings.MIN_MARGIN, HudIndicatorSettings.MAX_MARGIN);
        this.hudIndicatorColor = parseField("hud-indicator-color",
                properties.getProperty("hud-indicator-color", "green"),
                HudIndicatorColor.GREEN, HudIndicatorColor::fromConfig);
        this.hudIndicatorContent = parseField("hud-indicator-content",
                properties.getProperty("hud-indicator-content", "dot"),
                HudIndicatorContent.DOT, HudIndicatorContent::fromConfig);
        this.hudIndicatorVisibility = parseField("hud-indicator-visibility",
                properties.getProperty("hud-indicator-visibility", "always"),
                HudIndicatorVisibility.ALWAYS, HudIndicatorVisibility::fromConfig);
        this.hudIndicatorOffsetX = parseBoundedInt(
                properties.getProperty("hud-indicator-offset-x", "0"),
                0, HudIndicatorSettings.MIN_OFFSET, HudIndicatorSettings.MAX_OFFSET);
        this.hudIndicatorOffsetY = parseBoundedInt(
                properties.getProperty("hud-indicator-offset-y", "0"),
                0, HudIndicatorSettings.MIN_OFFSET, HudIndicatorSettings.MAX_OFFSET);
        this.onlineProviderConfig = OnlineProviderConfig.from(properties);
        this.configFile = configFile;
        this.cacheFile = cacheFile;
        this.offlineDirectory = configFile.getParent().resolve("universal-translator-offline");
    }

    HudIndicatorSettings hudIndicatorSettings() {
        return new HudIndicatorSettings(
                hudIndicator, hudIndicatorCorner, hudIndicatorSize, hudIndicatorMargin, hudIndicatorColor,
                hudIndicatorContent, hudIndicatorVisibility,
                hudIndicatorOffsetX, hudIndicatorOffsetY);
    }

    /**
     * The keys this snapshot already claimed plus {@code keys}, as an immutable set.
     *
     * <p>Each {@code withXxx} claims exactly what it writes and inherits the rest, so
     * {@code toProperties()} keeps the protection for every advanced key the caller did not touch.
     */
    private Set<String> claimedKeys(String... keys) {
        Set<String> claimed = new LinkedHashSet<String>(ownedPromptKeys);
        Collections.addAll(claimed, keys);
        return Collections.unmodifiableSet(claimed);
    }

    static FabricConfig load(Path configDirectory) throws IOException {
        corruptionBackup = null;
        Files.createDirectories(configDirectory);
        Path file = configDirectory.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            Properties defaults = defaults();
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                defaults.store(writer,
                        "MC Auto Translation Tool - online translation may send selected game, mod, and modpack text to this endpoint");
            }
        }

        Properties stored = new Properties();
        boolean recovered = false;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            stored.load(reader);
            // Readable again: whatever blocked the earlier quarantine is gone, so saves are allowed.
            UNQUARANTINED.remove(file);
        } catch (IOException | RuntimeException malformed) {
            // See the modern copy of this class: one damaged byte used to shut translation down
            // for good, because every later read — the settings screen included — failed the same
            // way and nothing ever rewrote the file. Quarantine, then continue on the defaults.
            recovered = true;
            stored = new Properties();
            corruptionBackup = quarantine(file);
            if (corruptionBackup == null) {
                // The damaged bytes are still in place and are the only copy of the player's
                // settings: refuse to write over them until a load can read the file again.
                UNQUARANTINED.add(file);
            }
            DebugLog.global().log("config-corrupt", "cause", malformed.getClass().getSimpleName(),
                    "backup", corruptionBackup == null ? "" : corruptionBackup);
        }
        Properties properties = defaults();
        properties.putAll(stored);
        boolean legacyMigration = !stored.containsKey("config-version");
        boolean migrated = configVersion(stored) < 6;
        if (legacyMigration) {
            // Per key, as in the modern copy: only a key the file does not have is defaulted.
            if (!stored.containsKey("display-mode")) {
                properties.setProperty("display-mode", "translated-only");
            }
            if (!stored.containsKey("translate-english-only")) {
                properties.setProperty("translate-english-only", "true");
            }
            if (!stored.containsKey("translated-text-color")) {
                properties.setProperty("translated-text-color", "original");
            }
        }
        properties.setProperty("config-version", "6");
        LocalConfigSecurity.restrictToOwner(file);
        FabricConfig loaded = new FabricConfig(
                properties, file, configDirectory.resolve("universal-translator-cache.properties"));
        // The one place the hotkeys reach their global holder, and only once per load: the codes
        // the 1.0-1.13 keyboard mixins read must match the file, not whatever the last rebuild
        // happened to carry.
        HotkeyKeys.install(loaded.settingsKey, loaded.toggleKey);
        // See the modern copy: rewriting the file is what makes a quarantined configuration
        // self-healing, but only when the damaged bytes really were preserved somewhere else.
        if (migrated && (!recovered || corruptionBackup != null)) {
            loaded.save();
        }
        // The statistics file sits next to the configuration. Reading it here is the one place every
        // platform passes through at startup, so no runtime has to know about statistics.
        TranslationStats.global().attach(file.resolveSibling(TranslationStats.FILE_NAME));
        DebugLog.global().configure(file.resolveSibling(DebugLog.FILE_NAME), loaded.debugLog());
        return loaded;
    }

    /** Name of the file the last {@link #load(Path)} quarantined, or {@code null} when it was healthy. */
    static String lastCorruptionBackup() {
        return corruptionBackup;
    }

    /** Moves a damaged configuration aside and names the copy it became, or {@code null} on failure. */
    private static String quarantine(Path file) {
        Path target = file.resolveSibling(
                file.getFileName().toString() + ".corrupt-" + LocalDateTime.now().format(CORRUPT_STAMP));
        try {
            Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
            return target.getFileName().toString();
        } catch (IOException | RuntimeException moveFailure) {
            return null;
        }
    }

    /** A hotkey name, validated and normalized to the spelling {@link HotkeyKeys} reports back. */
    private static String hotkey(String key, String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        int code = HotkeyKeys.codeOf(trimmed, -1);
        if (code < 0) {
            // A name this build does not know used to be written back verbatim while the game
            // silently listened for the default, so the file said "Ctrl" and the hotkey was U.
            DebugLog.global().log("config-fallback", "setting", key, "value", trimmed,
                    "used", fallback);
            return fallback;
        }
        // One representation: the snapshot holds the same spelling HotkeyKeys.settingsName() will
        // report, so the file, the diagnostics page and the key that actually fires cannot disagree.
        return HotkeyKeys.nameOf(code);
    }

    FabricConfig withSettings(
            boolean enabled,
            boolean translateChat,
            boolean translateOther,
            boolean translateVanilla,
            boolean translateOutgoing,
            boolean translatePlayerNames,
            String blockedKeywords,
            String targetLanguage,
            String outgoingTargetLanguage,
            TranslationDisplayMode displayMode,
            boolean translateEnglishOnly,
            TranslationTextColor translatedTextColor,
            String provider,
            String endpoint,
            String llmEndpoint,
            String llmApiKey,
            String llmModel,
            boolean offlineAutoDownload,
            OfflineModel offlineModel,
            boolean apiFallback,
            boolean diskCache,
            boolean animatedUi,
            HudIndicatorSettings hudIndicator
    ) {
        Properties properties = toProperties();
        applyFormSettings(properties, enabled, translateChat, translateOther, translateVanilla,
                translateOutgoing, translatePlayerNames, blockedKeywords, targetLanguage,
                outgoingTargetLanguage, displayMode, translateEnglishOnly, translatedTextColor,
                provider, endpoint, llmEndpoint, llmApiKey, llmModel, offlineAutoDownload,
                offlineModel, apiFallback, diskCache, animatedUi, hudIndicator);
        // This save does not edit the advanced keys, so it passes the claim along unchanged: whichever
        // of them an earlier step wrote stays written, and the rest are still re-read from disk.
        return new FabricConfig(properties, configFile, cacheFile, ownedPromptKeys);
    }

    /**
     * The settings screen's whole Save, built from one read of the file instead of four.
     *
     * <p>Same reason as the modern copy of this class: each of {@code withSettings},
     * {@code withPromptSettings} and {@code withTranslationTuning} rebuilt from
     * {@code toProperties()}, and {@code save()} rebuilt a fourth time — four file reads for one
     * button press, all of them on the client thread.
     */
    FabricConfig withAllSettings(
            boolean enabled,
            boolean translateChat,
            boolean translateOther,
            boolean translateVanilla,
            boolean translateOutgoing,
            boolean translatePlayerNames,
            String blockedKeywords,
            String targetLanguage,
            String outgoingTargetLanguage,
            TranslationDisplayMode displayMode,
            boolean translateEnglishOnly,
            TranslationTextColor translatedTextColor,
            String provider,
            String endpoint,
            String llmEndpoint,
            String llmApiKey,
            String llmModel,
            boolean offlineAutoDownload,
            OfflineModel offlineModel,
            boolean apiFallback,
            boolean diskCache,
            boolean animatedUi,
            HudIndicatorSettings hudIndicator,
            TranslationQuality quality,
            String customSystemPrompt,
            int workers,
            int batchSize
    ) {
        Properties properties = toProperties();
        applyFormSettings(properties, enabled, translateChat, translateOther, translateVanilla,
                translateOutgoing, translatePlayerNames, blockedKeywords, targetLanguage,
                outgoingTargetLanguage, displayMode, translateEnglishOnly, translatedTextColor,
                provider, endpoint, llmEndpoint, llmApiKey, llmModel, offlineAutoDownload,
                offlineModel, apiFallback, diskCache, animatedUi, hudIndicator);
        OnlineProviderConfig.applyPromptSettings(properties, quality, customSystemPrompt);
        OnlineProviderConfig.applyTranslationTuning(properties, workers, batchSize);
        // Same claim {@link #withPromptSettings} makes: this call wrote the two prompt keys itself.
        return new FabricConfig(properties, configFile, cacheFile,
                claimedKeys(OnlineProviderConfig.QUALITY_KEY,
                        OnlineProviderConfig.CUSTOM_PROMPT_KEY));
    }

    /** Writes the general form onto {@code properties}; the half every save path shares. */
    private void applyFormSettings(Properties properties,
            boolean enabled,
            boolean translateChat,
            boolean translateOther,
            boolean translateVanilla,
            boolean translateOutgoing,
            boolean translatePlayerNames,
            String blockedKeywords,
            String targetLanguage,
            String outgoingTargetLanguage,
            TranslationDisplayMode displayMode,
            boolean translateEnglishOnly,
            TranslationTextColor translatedTextColor,
            String provider,
            String endpoint,
            String llmEndpoint,
            String llmApiKey,
            String llmModel,
            boolean offlineAutoDownload,
            OfflineModel offlineModel,
            boolean apiFallback,
            boolean diskCache,
            boolean animatedUi,
            HudIndicatorSettings hudIndicator) {
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("translate-chat", Boolean.toString(translateChat));
        properties.setProperty("translate-other", Boolean.toString(translateOther));
        properties.setProperty("translate-vanilla", Boolean.toString(translateVanilla));
        properties.setProperty("translate-outgoing", Boolean.toString(translateOutgoing));
        properties.setProperty("stream-outgoing", Boolean.toString(streamOutgoing));
        properties.setProperty("translate-player-names", Boolean.toString(translatePlayerNames));
        properties.setProperty("blocked-keywords", boundedKeywords(blockedKeywords));
        // The hotkey names are not written here: the snapshot already carries them from
        // toProperties(), which writes the raw property values this object was built from.
        properties.setProperty("target-language", targetLanguage.trim());
        properties.setProperty("outgoing-target-language", outgoingTargetLanguage.trim());
        properties.setProperty("display-mode", displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                ? "bilingual" : "translated-only");
        properties.setProperty("translate-english-only", Boolean.toString(translateEnglishOnly));
        properties.setProperty("translated-text-color", translatedTextColor.configName());
        properties.setProperty("provider", provider.trim());
        properties.setProperty("libretranslate-endpoint", endpoint.trim());
        OnlineProviderConfig.applyLlmEditorSettings(
                properties, provider, llmEndpoint, llmApiKey, llmModel);
        properties.setProperty("offline-auto-download", Boolean.toString(offlineAutoDownload));
        properties.setProperty("offline-model",
                (offlineModel == null ? OfflineModel.LITE : offlineModel).configName());
        properties.setProperty("api-fallback", Boolean.toString(apiFallback));
        properties.setProperty("disk-cache", Boolean.toString(diskCache));
        properties.setProperty("animated-ui", Boolean.toString(animatedUi));
        properties.setProperty("hud-indicator", Boolean.toString(hudIndicator.isIndicator()));
        properties.setProperty("hud-indicator-corner", hudIndicator.getCorner().configName());
        properties.setProperty("hud-indicator-size", Integer.toString(hudIndicator.getSize()));
        properties.setProperty("hud-indicator-margin", Integer.toString(hudIndicator.getMargin()));
        properties.setProperty("hud-indicator-color", hudIndicator.getColor().configName());
        properties.setProperty("hud-indicator-content", hudIndicator.getContent().configName());
        properties.setProperty("hud-indicator-visibility", hudIndicator.getVisibility().configName());
        properties.setProperty("hud-indicator-offset-x", Integer.toString(hudIndicator.getOffsetX()));
        properties.setProperty("hud-indicator-offset-y", Integer.toString(hudIndicator.getOffsetY()));
    }

    FabricConfig withEnabled(boolean enabled) {
        Properties properties = toProperties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        return new FabricConfig(properties, configFile, cacheFile, ownedPromptKeys);
    }

    /**
     * Same configuration with a different provider name, without saving anything.
     *
     * <p>The engine tab's "check settings" button must answer for the provider the screen shows,
     * but that value only reaches a config object when the form is saved, so the snapshot the
     * screen holds still carries the provider it was opened with. Building the snapshot here lets
     * the button ask the real question, with no write to disk.
     */
    FabricConfig withProvider(String newProvider) {
        Properties properties = toProperties();
        properties.setProperty("provider", newProvider == null ? "" : newProvider.trim());
        return new FabricConfig(properties, configFile, cacheFile, ownedPromptKeys);
    }

    FabricConfig withHomeSettings(
            boolean enabled,
            boolean translateVanilla,
            String targetLanguage
    ) {
        Properties properties = toProperties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("translate-vanilla", Boolean.toString(translateVanilla));
        properties.setProperty("target-language", targetLanguage.trim());
        return new FabricConfig(properties, configFile, cacheFile, ownedPromptKeys);
    }

    /** Selected translation quality mode; the default reproduces the historical prompt. */
    TranslationQuality translationQuality() {
        return onlineProviderConfig.translationQuality();
    }

    /** Custom system prompt, or an empty string to use the built-in prompt of the active mode. */
    String customSystemPrompt() {
        return onlineProviderConfig.customSystemPrompt();
    }

    /** Configured concurrent translation threads; clamped by {@link OnlineProviderConfig}. */
    int translationWorkers() {
        return onlineProviderConfig.translationWorkers();
    }

    /** Configured texts per request; 1 disables batching. Clamped by {@link OnlineProviderConfig}. */
    int batchSize() {
        return onlineProviderConfig.batchSize();
    }

    /**
     * Returns a copy with only the prompt settings replaced.
     *
     * <p>Kept separate from {@link #withSettings}: the prompt keys are carried by {@link
     * OnlineProviderConfig} and therefore round-trip through {@code toProperties()} without
     * widening the positional {@code withSettings} signature every settings screen calls.
     */
    FabricConfig withPromptSettings(TranslationQuality quality, String customSystemPrompt) {
        Properties properties = toProperties();
        OnlineProviderConfig.applyPromptSettings(properties, quality, customSystemPrompt);
        // Claim exactly the two keys just overwritten, so the next toProperties() keeps them instead
        // of restoring the older value from disk. debug-log is deliberately not claimed: this call
        // does not write it.
        return new FabricConfig(properties, configFile, cacheFile,
                claimedKeys(OnlineProviderConfig.QUALITY_KEY,
                        OnlineProviderConfig.CUSTOM_PROMPT_KEY));
    }

    /**
     * Returns a copy with only the translation worker count and batch size replaced.
     *
     * <p>Kept separate from {@link #withSettings} for the same reason as {@link
     * #withPromptSettings}: the keys live in {@link OnlineProviderConfig} and round-trip through
     * {@code toProperties()} on their own.
     */
    FabricConfig withTranslationTuning(int workers, int batchSize) {
        Properties properties = toProperties();
        OnlineProviderConfig.applyTranslationTuning(properties, workers, batchSize);
        // Only throughput keys are written here, so the claim is passed along unchanged rather than
        // widened: claiming the prompt keys would stop toProperties() from picking up a change the
        // diagnostics panel made after this snapshot was taken.
        return new FabricConfig(properties, configFile, cacheFile, ownedPromptKeys);
    }

    /** Whether the opt-in request trace is written; off by default. */
    boolean debugLog() {
        return onlineProviderConfig.debugLog();
    }

    /**
     * Returns a copy with only the debug-mode switch replaced.
     *
     * <p>Kept separate from {@link #withSettings} for the same reason as {@link
     * #withPromptSettings}: the switch lives in {@link OnlineProviderConfig} and round-trips through
     * {@code toProperties()} on its own.
     */
    FabricConfig withDebugLog(boolean debugEnabled) {
        Properties properties = toProperties();
        OnlineProviderConfig.applyDebugLog(properties, debugEnabled);
        // The diagnostics screen saves this copy straight away, so the switch it just wrote has to
        // survive the rebuild; the two prompt keys stay unclaimed and are still re-read from disk.
        return new FabricConfig(properties, configFile, cacheFile,
                claimedKeys(OnlineProviderConfig.DEBUG_LOG_KEY));
    }

    /** The configuration file this instance was loaded from, for the diagnostics export. */
    Path configFile() {
        return configFile;
    }

    void save() throws IOException {
        if (UNQUARANTINED.contains(configFile)) {
            throw new IOException("The damaged configuration could not be moved aside; it is kept"
                    + " untouched at " + configFile.getFileName());
        }
        // Unique per attempt; see the modern copy of this class. A shared "<file>.tmp" let a second
        // writer delete the file the first one was writing into.
        Path temporary = configFile.resolveSibling(
                configFile.getFileName().toString() + "." + java.util.UUID.randomUUID() + ".tmp");
        try {
            Files.deleteIfExists(temporary);
            Files.createFile(temporary);
            LocalConfigSecurity.restrictToOwner(temporary);
            try (Writer writer = Files.newBufferedWriter(
                    temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                toProperties().store(writer,
                        "MC Auto Translation Tool - online translation may send selected game, mod, and modpack text to this endpoint");
            }
            try {
                Files.move(temporary, configFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the original save failure, if any.
            }
        }
        LocalConfigSecurity.restrictToOwner(configFile);
    }

    /**
     * Checks that the configured provider can actually be built, before the settings are applied.
     *
     * <p>The offline engine used to pass this without checking anything: {@code createProvider()}
     * only calls {@code LlamaCppOfflineProvider.forModel(...)}, which starts no server and opens no
     * model file, so every offline configuration "validated" and the real failure surfaced a step
     * later on the startup path. Check the one thing that can be checked without starting anything.
     */
    void validateProviderConfiguration() throws Exception {
        if ("offline".equalsIgnoreCase(provider)) {
            if (!offlineAutoDownload && offlineDirectory != null) {
                Path model = offlineDirectory.resolve(offlineModel.modelFile());
                if (!Files.isRegularFile(model)) {
                    throw new IOException("Offline model is missing: " + model.getFileName());
                }
            }
            // Server start and model load belong to initialize, which may download first.
            return;
        }
        TranslationProvider candidate = createProvider();
        if (candidate instanceof AutoCloseable) {
            ((AutoCloseable) candidate).close();
        }
    }

    String editorEndpoint(String selectedProvider) {
        return onlineProviderConfig.llmEditorSettings(selectedProvider).endpoint();
    }

    String editorApiKey(String selectedProvider) {
        return onlineProviderConfig.llmEditorSettings(selectedProvider).apiKey();
    }

    String editorModel(String selectedProvider) {
        return onlineProviderConfig.llmEditorSettings(selectedProvider).model();
    }

    boolean allows(TextKind kind) {
        if (!enabled) {
            return false;
        }
        return kind == TextKind.CHAT || kind == TextKind.SYSTEM_MESSAGE
                ? translateChat
                : translateOther;
    }

    TranslationProvider createProvider() {
        if ("offline".equalsIgnoreCase(provider)) {
            TranslationProvider local = LlamaCppOfflineProvider.forModel(
                    offlineDirectory, offlineAutoDownload, offlineModel);
            if (!apiFallback) {
                return local;
            }
            // Same rule as the modern copy: an optional fallback that cannot be built — usually a
            // provider whose credential the player has not filled in — must not take the offline
            // engine down with it.
            try {
                return new FallbackTranslationProvider(local, createApiProvider(apiFallbackProvider));
            } catch (RuntimeException fallbackFailure) {
                DebugLog.global().log("config-provider", "fallback", "unavailable",
                        "provider", apiFallbackProvider,
                        "cause", fallbackFailure.getClass().getSimpleName());
                return local;
            }
        }
        return createApiProvider(provider);
    }

    private TranslationProvider createApiProvider(String selectedProvider) {
        return onlineProviderConfig.create(selectedProvider);
    }

    private static Properties defaults() {
        Properties properties = new Properties();
        properties.setProperty("config-version", "6");
        properties.setProperty("enabled", "false");
        properties.setProperty("translate-chat", "true");
        properties.setProperty("translate-other", "true");
        properties.setProperty("translate-vanilla", "true");
        properties.setProperty("translate-outgoing", "false");
        properties.setProperty("stream-outgoing", "true");
        properties.setProperty("translate-player-names", "false");
        properties.setProperty("animated-ui", "true");
        properties.setProperty("blocked-keywords", "");
        properties.setProperty("settings-key", DEFAULT_SETTINGS_KEY);
        properties.setProperty("toggle-key", DEFAULT_TOGGLE_KEY);
        properties.setProperty("target-language", TargetLanguage.SIMPLIFIED_CHINESE);
        properties.setProperty("outgoing-target-language", "en");
        properties.setProperty("display-mode", "translated-only");
        properties.setProperty("translate-english-only", "true");
        properties.setProperty("translated-text-color", "original");
        properties.setProperty("provider", "offline");
        properties.setProperty("libretranslate-endpoint", "http://127.0.0.1:5000/translate");
        properties.setProperty("api-key", "");
        properties.setProperty("tencent-secret-id", "");
        properties.setProperty("tencent-secret-key", "");
        properties.setProperty("tencent-model", "hunyuan-translation-lite");
        properties.setProperty("llm-api-endpoint", "http://127.0.0.1:8080/v1/chat/completions");
        properties.setProperty("llm-api-key", "");
        properties.setProperty("llm-api-model", "local-model");
        properties.setProperty("offline-auto-download", "true");
        properties.setProperty("offline-model", "lite");
        properties.setProperty("api-fallback", "false");
        properties.setProperty("api-fallback-provider", "libretranslate");
        properties.setProperty("disk-cache", "true");
        properties.setProperty("hud-indicator", "true");
        properties.setProperty("hud-indicator-corner", "top-left");
        properties.setProperty("hud-indicator-size", "6");
        properties.setProperty("hud-indicator-margin", "4");
        properties.setProperty("hud-indicator-color", "green");
        properties.setProperty("hud-indicator-content", "dot");
        properties.setProperty("hud-indicator-visibility", "always");
        properties.setProperty("hud-indicator-offset-x", "0");
        properties.setProperty("hud-indicator-offset-y", "0");
        OnlineProviderConfig.applyDefaults(properties);
        return properties;
    }

    private Properties toProperties() {
        Properties properties = new Properties();
        onlineProviderConfig.writeTo(properties);
        // Keep the advanced settings the diagnostics panel may have changed after this snapshot,
        // except for the ones this snapshot wrote itself (see ownedPromptKeys).
        OnlineProviderConfig.preservePromptSettings(configFile, properties, ownedPromptKeys);
        properties.setProperty("config-version", "6");
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("translate-chat", Boolean.toString(translateChat));
        properties.setProperty("translate-other", Boolean.toString(translateOther));
        properties.setProperty("translate-vanilla", Boolean.toString(translateVanilla));
        properties.setProperty("translate-outgoing", Boolean.toString(translateOutgoing));
        properties.setProperty("stream-outgoing", Boolean.toString(streamOutgoing));
        properties.setProperty("translate-player-names", Boolean.toString(translatePlayerNames));
        properties.setProperty("animated-ui", Boolean.toString(animatedUi));
        properties.setProperty("blocked-keywords", blockedKeywords);
        // Written from the snapshot, never from the global HotkeyKeys: a rebuild that reached the
        // global holder would round-trip through nameOf(), which only eight of the named codes
        // survive, and would write the default over the player's value whenever this object was
        // built from properties that did not carry the key.
        properties.setProperty("settings-key", settingsKey);
        properties.setProperty("toggle-key", toggleKey);
        properties.setProperty("target-language", targetLanguage);
        properties.setProperty("outgoing-target-language", outgoingTargetLanguage);
        properties.setProperty("display-mode", displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                ? "bilingual" : "translated-only");
        properties.setProperty("translate-english-only", Boolean.toString(translateEnglishOnly));
        properties.setProperty("translated-text-color", translatedTextColor.configName());
        properties.setProperty("provider", provider);
        properties.setProperty("libretranslate-endpoint", endpoint);
        properties.setProperty("api-key", apiKey);
        properties.setProperty("tencent-secret-id", tencentSecretId);
        properties.setProperty("tencent-secret-key", tencentSecretKey);
        properties.setProperty("tencent-model", tencentModel);
        properties.setProperty("llm-api-endpoint", llmEndpoint);
        properties.setProperty("llm-api-key", llmApiKey);
        properties.setProperty("llm-api-model", llmModel);
        properties.setProperty("offline-auto-download", Boolean.toString(offlineAutoDownload));
        properties.setProperty("offline-model", offlineModel.configName());
        properties.setProperty("api-fallback", Boolean.toString(apiFallback));
        properties.setProperty("api-fallback-provider", apiFallbackProvider);
        properties.setProperty("disk-cache", Boolean.toString(diskCache));
        properties.setProperty("hud-indicator", Boolean.toString(hudIndicator));
        properties.setProperty("hud-indicator-corner", hudIndicatorCorner.configName());
        properties.setProperty("hud-indicator-size", Integer.toString(hudIndicatorSize));
        properties.setProperty("hud-indicator-margin", Integer.toString(hudIndicatorMargin));
        properties.setProperty("hud-indicator-color", hudIndicatorColor.configName());
        properties.setProperty("hud-indicator-content", hudIndicatorContent.configName());
        properties.setProperty("hud-indicator-visibility", hudIndicatorVisibility.configName());
        properties.setProperty("hud-indicator-offset-x", Integer.toString(hudIndicatorOffsetX));
        properties.setProperty("hud-indicator-offset-y", Integer.toString(hudIndicatorOffsetY));
        return properties;
    }

    private static int configVersion(Properties properties) {
        try {
            return Integer.parseInt(properties.getProperty("config-version", "1").trim());
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private static int parseBoundedInt(String raw, int fallback, int min, int max) {
        try {
            if (raw == null) {
                return fallback;
            }
            int parsed = Integer.parseInt(raw.trim());
            // Clamped rather than discarded; see the modern copy of this class.
            return Math.max(min, Math.min(max, parsed));
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }

    /**
     * Runs one field's parser and substitutes {@code fallback} when it throws.
     *
     * <p>Every enum helper these configs call already falls back on its own, but this wrapper keeps
     * that guarantee when one of them forgets: a throw here escapes the constructor, and therefore
     * {@code load()}; the platform bootstrap answers that by shutting the client down, and the
     * settings screen is built after it — so nothing ever saves and the value that caused the
     * exception stays in the file for every following start. One cosmetic key must never do that.
     *
     * @param key configuration key, only used to make the ignored value visible in the debug log
     * @param raw value as stored, possibly {@code null}
     * @param fallback value used when the parser throws
     * @param parser reader for the non-null case
     * @param <T> parsed type
     * @return the parsed value, or {@code fallback}
     */
    private static <T> T parseField(String key, String raw, T fallback, FieldParser<T> parser) {
        try {
            return parser.parse(raw);
        } catch (RuntimeException malformed) {
            // The debug log is off while this runs, so the message is normally discarded; it is
            // here for the case where a previous line of the same session already enabled it.
            DebugLog.global().log("config-fallback", "setting", key, "value", raw,
                    "used", String.valueOf(fallback));
            return fallback;
        }
    }

    /** A single configuration value reader; the throwing case is handled by {@link #parseField}. */
    private interface FieldParser<T> {
        T parse(String raw);
    }

    private static String boundedKeywords(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= TranslationBlocklist.MAX_CONFIG_LENGTH
                ? normalized : normalized.substring(0, TranslationBlocklist.MAX_CONFIG_LENGTH);
    }
}
