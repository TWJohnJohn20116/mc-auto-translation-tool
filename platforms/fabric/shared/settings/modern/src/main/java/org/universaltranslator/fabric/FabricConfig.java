package org.universaltranslator.fabric;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationDisplayMode;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HudIndicatorColor;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorSettings;
import org.universaltranslator.core.HudIndicatorVisibility;
import org.universaltranslator.core.TranslationQuality;
import org.universaltranslator.core.TranslationStats;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.PersistentTranslationCache;
import org.universaltranslator.core.TranslationTextColor;
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

/**
 * The Fabric configuration file, as an immutable snapshot.
 *
 * <p>Keep in sync with {@code shared/settings/legacy/…/FabricConfig.java}: the two classes are a
 * copy of each other, and the legacy tree is the one that drifted — it grew the two hotkey keys,
 * and the way it read them cost players their key bindings (C2-067). Every key added here and every
 * change to {@code load()}, {@code toProperties()} or {@code defaults()} belongs in both until they
 * collapse into one {@code settings-common} layer with a hotkey seam (audit2 S2-078, which needs the
 * per-version {@code srcDir} wiring this file cannot change).
 */
final class FabricConfig {
    private static final String FILE_NAME = "universal-translator.properties";
    /** Suffix pattern of the copy a damaged file is preserved as; one write per start at most. */
    private static final DateTimeFormatter CORRUPT_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /**
     * Name of the file the last {@link #load(Path)} quarantined, or {@code null} when it read a
     * healthy file.
     *
     * <p>The bootstrap reads this right after loading to say one line about where the damaged file
     * went. Without it the only trace was a debug-log entry, and the debug log is off by default —
     * so a player whose settings had just been reset to defaults would find nothing that explained
     * why, and their old file under a name nothing pointed at.
     */
    private static volatile String corruptionBackup;
    /**
     * Configuration files whose damaged contents could not be moved aside.
     *
     * <p>{@link #load(Path)} keeps the defaults in memory after a quarantine failure, but the
     * damaged bytes are still the only copy of the player's settings. Every later save — F8, the home
     * quick settings, the settings screen — would have replaced that file with the default snapshot;
     * the load itself was the only save that skipped the write. Marking the path makes the refusal
     * last as long as the condition does: the marker is dropped as soon as a load reads the file.
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
    final boolean hudIndicator;
    final HudIndicatorCorner hudIndicatorCorner;
    final int hudIndicatorSize;
    final int hudIndicatorMargin;
    final HudIndicatorColor hudIndicatorColor;
    final HudIndicatorContent hudIndicatorContent;
    final HudIndicatorVisibility hudIndicatorVisibility;
    final int hudIndicatorOffsetX;
    final int hudIndicatorOffsetY;
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

    HudIndicatorSettings hudIndicatorSettings() {
        return new HudIndicatorSettings(
                hudIndicator, hudIndicatorCorner, hudIndicatorSize, hudIndicatorMargin, hudIndicatorColor,
                hudIndicatorContent, hudIndicatorVisibility,
                hudIndicatorOffsetX, hudIndicatorOffsetY);
    }

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
        this.blockedKeywords = boundedKeywords(properties.getProperty("blocked-keywords", ""));
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
        this.onlineProviderConfig = OnlineProviderConfig.from(properties);
        this.configFile = configFile;
        this.cacheFile = cacheFile;
        this.offlineDirectory = configFile.getParent().resolve("universal-translator-offline");
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
            // Before this, a single damaged byte took the whole mod away: the exception escaped
            // load(), the bootstrap logged one line and shut translation down, and every later
            // attempt — including the settings screen, the one place a player could have fixed
            // anything — read the same file and failed the same way. Keep the bytes for a bug
            // report instead, and continue on the defaults so the session is usable.
            //
            // Both exception shapes are real: MalformedInputException for a file that is not valid
            // UTF-8, and IllegalArgumentException for a truncated \\uXXXX escape (Properties.load
            // throws it unchecked). A partially parsed file is discarded rather than merged, since
            // load() fills the map as it reads and a truncated key would otherwise survive.
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
            // Per key: this used to overwrite all three unconditionally, so a hand-written file that
            // simply had not been created by this build yet — the version key is the only thing
            // checked — was rewritten to the historical defaults, including a display mode the
            // player had chosen. The values are still on disk at this point, so the guard has to be
            // "the file does not say" rather than "the file is old".
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
                properties, file, configDirectory.resolve(PersistentTranslationCache.FILE_NAME));
        // The rewrite is what makes a quarantined file self-healing: without it the defaults would
        // only live in memory and the next start would find no file at all. When the quarantine
        // itself failed (a locked or read-only file) the original is still on disk, and overwriting
        // it would be the one thing that destroys the player's data for good — so leave it alone.
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

    /**
     * Moves a damaged configuration out of the way and names the copy it became.
     *
     * <p>Moved rather than deleted on purpose: the file may be the only record of a long-lived
     * setup, and it is what a bug report about the corruption would need. Returns {@code null} when
     * the move failed, which the caller treats as "do not touch the file".
     */
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
     * <p>{@link #toProperties()} re-reads the advanced keys from disk so an older snapshot cannot
     * undo a change the diagnostics panel made meanwhile, and that read is what made one Save cost
     * four file reads: {@code withSettings}, {@code withPromptSettings} and
     * {@code withTranslationTuning} each rebuilt from {@code toProperties()}, and {@code save()}
     * rebuilt a fourth time. All of it ran on the client thread, straight from a button callback, so
     * the game froze for the duration of four reads and one write.
     *
     * <p>Applying all three groups to a single {@code Properties} keeps every guarantee — the
     * prompt keys are claimed here exactly as {@link #withPromptSettings} claims them, and the
     * throughput keys keep the inherited claim — and leaves the chain at two reads: this one and
     * the one {@code save()} performs.
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
        // Same claim {@link #withPromptSettings} makes: this call wrote the two prompt keys itself,
        // so a later toProperties() must not restore the older values from disk.
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
        // Unique per attempt. Writers on the same path used to share one "<file>.tmp" name, so a
        // second save — F8 pressed while the screen's Save is running — deleted the file the first
        // one was writing into, and the move then truncated the real configuration.
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

    String editorApiVersion(String selectedProvider) {
        return onlineProviderConfig.llmEditorApiVersion(selectedProvider);
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
            // A fallback that cannot be built must not take the offline engine with it. The fallback
            // provider is optional, and the default provider name for it needs a credential, so an
            // unfilled key used to make createProvider() throw — the session was never built and
            // local translation did not run at all, for a setting the player had not even enabled.
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
        properties.setProperty("hud-indicator", "true");
        properties.setProperty("hud-indicator-corner", "top-left");
        properties.setProperty("hud-indicator-size", "6");
        properties.setProperty("hud-indicator-margin", "4");
        properties.setProperty("hud-indicator-color", "green");
        properties.setProperty("hud-indicator-content", "dot");
        properties.setProperty("hud-indicator-visibility", "always");
        properties.setProperty("hud-indicator-offset-x", "0");
        properties.setProperty("hud-indicator-offset-y", "0");
        properties.setProperty("blocked-keywords", "");
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
        properties.setProperty("hud-indicator", Boolean.toString(hudIndicator));
        properties.setProperty("hud-indicator-corner", hudIndicatorCorner.configName());
        properties.setProperty("hud-indicator-size", Integer.toString(hudIndicatorSize));
        properties.setProperty("hud-indicator-margin", Integer.toString(hudIndicatorMargin));
        properties.setProperty("hud-indicator-color", hudIndicatorColor.configName());
        properties.setProperty("hud-indicator-content", hudIndicatorContent.configName());
        properties.setProperty("hud-indicator-visibility", hudIndicatorVisibility.configName());
        properties.setProperty("hud-indicator-offset-x", Integer.toString(hudIndicatorOffsetX));
        properties.setProperty("hud-indicator-offset-y", Integer.toString(hudIndicatorOffsetY));
        properties.setProperty("blocked-keywords", blockedKeywords);
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
            // Clamped, not discarded: a value outside the range still says what the player wanted
            // ("a bigger indicator", "hug the edge"), and the old fallback turned 200 into 6 and 0
            // into 4, which is the opposite of the intent and was then written back as if chosen.
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
