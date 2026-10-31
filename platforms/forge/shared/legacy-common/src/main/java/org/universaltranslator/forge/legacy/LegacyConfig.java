package org.universaltranslator.forge.legacy;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationDisplayMode;
import org.universaltranslator.core.TranslationQuality;
import org.universaltranslator.core.TranslationStats;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.PersistentTranslationCache;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.HudIndicatorColor;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HudIndicatorSettings;
import org.universaltranslator.core.HudIndicatorVisibility;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.LocalConfigSecurity;
import org.universaltranslator.core.OfflineModel;
import org.universaltranslator.core.TranslationBlocklist;
import org.universaltranslator.core.provider.FallbackTranslationProvider;
import org.universaltranslator.core.provider.LlamaCppOfflineProvider;
import org.universaltranslator.core.provider.OnlineProviderConfig;
import org.universaltranslator.core.TargetLanguage;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

final class LegacyConfig {
    /** Serializes every writer of one configuration file; see {@link #save()}. */
    private static final java.util.concurrent.ConcurrentHashMap<Path, Object> SAVE_MONITORS =
            new java.util.concurrent.ConcurrentHashMap<Path, Object>();
    /**
     * The advanced keys as they were last read from disk, shared by every snapshot of one file.
     *
     * <p>{@code toProperties()} re-reads those keys so a settings screen holding an older snapshot
     * cannot undo a change the diagnostics panel made meanwhile, and one Save rebuilds it four times
     * (the three {@code withXxx} copies plus the write). Each rebuild was a full file open and
     * {@code Properties.load} on the calling thread — the render thread for a Save, the client tick
     * for F8 — which costs 5–50 ms per read once {@code config/} sits in a cloud-synced folder. One
     * copy is enough: this class is the only writer, and the stamp below re-reads as soon as the
     * file changes by any other means.
     */
    private static volatile DiskCopy diskCopy;
    /**
     * How many saves this process has staged for writing, on any configuration file.
     *
     * <p>A cached copy is only trusted while this has not moved since it was read. {@link #save()}
     * drops the copy inside the same monitor as the write, but a reader can read the entry just
     * before that drop and stat the file just after the replace — and a filesystem whose timestamp
     * granularity is coarser than that gap (FAT 2 s, some SMB and cloud-sync directories 1 s) reports
     * the same (mtime, size) for a same-length rewrite. Counting the saves invalidates exactly that
     * entry, without distrusting a correct one for a fixed window after every save.
     */
    private static volatile long saveCounter;

    /**
     * The last configuration this process loaded, or {@code null} before the first success.
     *
     * <p>{@link LegacyTranslationRuntime#shutdown()} drops the live copy when a load fails, which is
     * exactly the case where the player needs the settings screen to repair the file; only the
     * successful path of {@link #load(File)} writes this, so it can never present a configuration
     * that was never readable.
     */
    private static volatile LegacyConfig lastLoaded;

    /** One read of the configuration file: its parsed contents plus the stamp they were read at. */
    private static final class DiskCopy {
        final Path file;
        final long lastModified;
        final long size;
        final Properties properties;
        /** {@link LegacyConfig#saveCounter} as it stood when this copy was read. */
        final long saveCount;

        DiskCopy(Path file, long lastModified, long size, long saveCount, Properties properties) {
            this.file = file;
            this.lastModified = lastModified;
            this.size = size;
            this.saveCount = saveCount;
            this.properties = properties;
        }
    }
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
    final File offlineDirectory;
    final boolean diskCache;
    final File cacheFile;
    final boolean hudIndicator;
    final HudIndicatorCorner hudIndicatorCorner;
    final int hudIndicatorSize;
    final int hudIndicatorMargin;
    final HudIndicatorColor hudIndicatorColor;
    final HudIndicatorContent hudIndicatorContent;
    final HudIndicatorVisibility hudIndicatorVisibility;
    final int hudIndicatorOffsetX;
    final int hudIndicatorOffsetY;
    private final OnlineProviderConfig onlineProviderConfig;
    private final File configFile;
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

    private LegacyConfig(Properties properties, File configFile, File cacheFile) {
        this(properties, configFile, cacheFile, Collections.<String>emptySet());
    }

    /**
     * @param ownedPromptKeys advanced-panel keys this snapshot wrote itself and must not re-read
     */
    private LegacyConfig(
            Properties properties, File configFile, File cacheFile, Set<String> ownedPromptKeys) {
        this.ownedPromptKeys = Collections.unmodifiableSet(
                new LinkedHashSet<String>(ownedPromptKeys));
        enabled = Boolean.parseBoolean(properties.getProperty("enabled", "false"));
        translateChat = Boolean.parseBoolean(properties.getProperty("translate-chat", "true"));
        translateOther = Boolean.parseBoolean(properties.getProperty("translate-other", "true"));
        translateVanilla = Boolean.parseBoolean(
                properties.getProperty("translate-vanilla", "true"));
        translateOutgoing = Boolean.parseBoolean(
                properties.getProperty("translate-outgoing", "false"));
        streamOutgoing = Boolean.parseBoolean(
                properties.getProperty("stream-outgoing", "true"));
        translatePlayerNames = Boolean.parseBoolean(
                properties.getProperty("translate-player-names", "false"));
        animatedUi = Boolean.parseBoolean(properties.getProperty("animated-ui", "true"));
        blockedKeywords = boundedKeywords(properties.getProperty("blocked-keywords", ""));
        targetLanguage = properties.getProperty("target-language", TargetLanguage.SIMPLIFIED_CHINESE).trim();
        outgoingTargetLanguage = properties.getProperty(
                "outgoing-target-language", "en").trim();
        displayMode = parseField("display-mode",
                properties.getProperty("display-mode", "translated-only"),
                TranslationDisplayMode.TRANSLATED_ONLY, TranslationDisplayMode::fromConfig);
        translateEnglishOnly = Boolean.parseBoolean(
                properties.getProperty("translate-english-only", "true"));
        translatedTextColor = parseField("translated-text-color",
                properties.getProperty("translated-text-color", "original"),
                TranslationTextColor.ORIGINAL, TranslationTextColor::fromConfig);
        provider = properties.getProperty("provider", "offline").trim();
        endpoint = properties.getProperty(
                "libretranslate-endpoint", "http://127.0.0.1:5000/translate").trim();
        apiKey = properties.getProperty("api-key", "").trim();
        tencentSecretId = properties.getProperty("tencent-secret-id", "").trim();
        tencentSecretKey = properties.getProperty("tencent-secret-key", "").trim();
        tencentModel = properties.getProperty(
                "tencent-model", "hunyuan-translation-lite").trim();
        llmEndpoint = properties.getProperty(
                "llm-api-endpoint", "http://127.0.0.1:8080/v1/chat/completions").trim();
        llmApiKey = properties.getProperty("llm-api-key", "").trim();
        llmModel = properties.getProperty("llm-api-model", "local-model").trim();
        offlineAutoDownload = Boolean.parseBoolean(
                properties.getProperty("offline-auto-download", "true"));
        offlineModel = parseField("offline-model",
                properties.getProperty("offline-model", "lite"),
                OfflineModel.LITE, OfflineModel::fromConfig);
        apiFallback = Boolean.parseBoolean(properties.getProperty("api-fallback", "false"));
        apiFallbackProvider = properties.getProperty(
                "api-fallback-provider", "libretranslate").trim();
        diskCache = Boolean.parseBoolean(properties.getProperty("disk-cache", "true"));
        onlineProviderConfig = OnlineProviderConfig.from(properties);
        this.configFile = configFile;
        this.cacheFile = cacheFile;
        this.offlineDirectory = new File(configFile.getParentFile(), "universal-translator-offline");
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

    static LegacyConfig lastLoaded() {
        return lastLoaded;
    }

    static LegacyConfig load(File configDirectory) throws IOException {
        if (!configDirectory.exists() && !configDirectory.mkdirs()) {
            throw new IOException("Could not create config directory: " + configDirectory);
        }
        File file = new File(configDirectory, "universal-translator.properties");
        if (!file.exists()) {
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8)) {
                defaults().store(writer,
                        "MC Auto Translation Tool - online translation may send selected game, mod, and modpack text to this endpoint");
            }
        }
        Properties stored = new Properties();
        boolean recovered = false;
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8)) {
            stored.load(reader);
        } catch (IOException | RuntimeException malformed) {
            // Same isolation as the modern Forge config: one unreadable byte used to disable
            // translation for the whole session, and the broken file stayed in place for the next
            // launch and for the settings screen that is the only way to repair it.
            recovered = true;
            stored = new Properties();
            quarantine(file, malformed);
        }
        Properties properties = defaults();
        properties.putAll(stored);
        boolean legacyMigration = !stored.containsKey("config-version");
        boolean migrated = configVersion(stored) < 6;
        if (legacyMigration) {
            properties.setProperty("display-mode", "translated-only");
            properties.setProperty("translate-english-only", "true");
            properties.setProperty("translated-text-color", "original");
        }
        properties.setProperty("config-version", "6");
        LocalConfigSecurity.restrictToOwner(file.toPath());
        LegacyConfig loaded = new LegacyConfig(
                properties, file, new File(configDirectory, PersistentTranslationCache.FILE_NAME));
        if (migrated || recovered) {
            try {
                loaded.save();
            } catch (IOException unwritable) {
                // Reading must not require a writable directory; failing here disabled translation
                // over an optional write-back.
                DebugLog.global().log("config", "write", "skipped",
                        "reason", String.valueOf(unwritable.getClass().getSimpleName()));
            }
        }
        // The statistics file sits next to the configuration. Reading it here is the one place every
        // platform passes through at startup, so no runtime has to know about statistics.
        TranslationStats.global().attach(
                file.toPath().resolveSibling(TranslationStats.FILE_NAME));
        DebugLog.global().configure(
                file.toPath().resolveSibling(DebugLog.FILE_NAME), loaded.debugLog());
        lastLoaded = loaded;
        return loaded;
    }

    LegacyConfig withSettings(
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
        // This save does not edit the advanced keys, so it passes the claim along unchanged: whichever
        // of them an earlier step wrote stays written, and the rest are still re-read from disk.
        return new LegacyConfig(properties, configFile, cacheFile, ownedPromptKeys);
    }

    LegacyConfig withEnabled(boolean enabled) {
        Properties properties = toProperties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        return new LegacyConfig(properties, configFile, cacheFile, ownedPromptKeys);
    }

    /**
     * Same configuration with a different provider name, without saving anything.
     *
     * <p>The engine tab's "check settings" button must answer for the provider the screen shows,
     * but that value only reaches a config object when the form is saved. Building the snapshot
     * here lets the button ask the real question, with no write to disk.
     */
    LegacyConfig withProvider(String newProvider) {
        Properties properties = toProperties();
        properties.setProperty("provider", newProvider == null ? "" : newProvider.trim());
        return new LegacyConfig(properties, configFile, cacheFile, ownedPromptKeys);
    }

    LegacyConfig withHomeSettings(
            boolean enabled,
            boolean translateVanilla,
            String targetLanguage
    ) {
        Properties properties = toProperties();
        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("translate-vanilla", Boolean.toString(translateVanilla));
        properties.setProperty("target-language", targetLanguage.trim());
        return new LegacyConfig(properties, configFile, cacheFile, ownedPromptKeys);
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
    LegacyConfig withPromptSettings(TranslationQuality quality, String customSystemPrompt) {
        Properties properties = toProperties();
        OnlineProviderConfig.applyPromptSettings(properties, quality, customSystemPrompt);
        // Claim exactly the two keys just overwritten, so the next toProperties() keeps them instead
        // of restoring the older value from disk. debug-log is deliberately not claimed: this call
        // does not write it.
        return new LegacyConfig(properties, configFile, cacheFile,
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
    LegacyConfig withTranslationTuning(int workers, int batchSize) {
        Properties properties = toProperties();
        OnlineProviderConfig.applyTranslationTuning(properties, workers, batchSize);
        // Only throughput keys are written here, so the claim is passed along unchanged rather than
        // widened: claiming the prompt keys would stop toProperties() from picking up a change the
        // diagnostics panel made after this snapshot was taken.
        return new LegacyConfig(properties, configFile, cacheFile, ownedPromptKeys);
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
    LegacyConfig withDebugLog(boolean debugEnabled) {
        Properties properties = toProperties();
        OnlineProviderConfig.applyDebugLog(properties, debugEnabled);
        // The diagnostics screen saves this copy straight away, so the switch it just wrote has to
        // survive the rebuild; the two prompt keys stay unclaimed and are still re-read from disk.
        return new LegacyConfig(properties, configFile, cacheFile,
                claimedKeys(OnlineProviderConfig.DEBUG_LOG_KEY));
    }

    /** The configuration file this instance was loaded from, for the diagnostics export. */
    Path configFile() {
        return configFile.toPath();
    }

    /**
     * Moves an unreadable configuration aside so the next launch starts from the defaults.
     *
     * <p>Renamed rather than deleted: the file may hold nothing but an edit the player can repair by
     * hand, and the diagnostics page can point at the copy.
     */
    private static void quarantine(File file, Exception cause) {
        try {
            File backup = new File(file.getParentFile(), file.getName() + ".corrupt-"
                    + System.currentTimeMillis());
            Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            DebugLog.global().log("config", "quarantined",
                    "file", String.valueOf(backup.getName()),
                    "reason", String.valueOf(cause.getClass().getSimpleName()));
        } catch (IOException | RuntimeException unquarantinable) {
            // Nothing else can be done here; the defaults still let the session run.
            DebugLog.global().log("config", "quarantine", "failed",
                    "reason", String.valueOf(unquarantinable.getClass().getSimpleName()));
        }
    }

    void save() throws IOException {
        Path file = configFile.toPath();
        // One writer at a time per configuration file; see the note in the modern Forge config.
        // Two paths do reach save() together (the settings screen's Save and the F8 toggle).
        Path lockKey = file.toAbsolutePath();
        Object monitor;
        synchronized (SAVE_MONITORS) {
            monitor = SAVE_MONITORS.get(lockKey);
            if (monitor == null) {
                monitor = new Object();
                SAVE_MONITORS.put(lockKey, monitor);
            }
        }
        synchronized (monitor) {
            writeAtomically(file);
            // What is on disk is now this snapshot, so no cached copy can be newer than it.
            diskCopy = null;
        }
    }

    private void writeAtomically(Path file) throws IOException {
        // Merge before the generation moves. The merge read can then still be answered by the copy
        // withXxx() just built — this process has not touched the file since — where bumping first
        // made the stamp differ and forced one open plus Properties.load on every single save.
        Properties staged = toProperties();
        // Advance the generation only now, and still before the move below: a reader that stats the
        // new file must never pick up the older count and trust a stale copy.
        saveCounter++;
        // Unique per attempt, so two writers can never delete each other's temporary file.
        Path temporary = file.resolveSibling(
                file.getFileName().toString() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.createFile(temporary);
            LocalConfigSecurity.restrictToOwner(temporary);
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    Files.newOutputStream(temporary,
                            StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                    StandardCharsets.UTF_8)) {
                staged.store(writer,
                        "MC Auto Translation Tool - online translation may send selected game, mod, and modpack text to this endpoint");
            }
            try {
                Files.move(temporary, file,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the original save failure, if any.
            }
        }
        LocalConfigSecurity.restrictToOwner(file);
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
                Path model = offlineDirectory.toPath().resolve(offlineModel.modelFile());
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
                    offlineDirectory.toPath(), offlineAutoDownload, offlineModel);
            return apiFallback
                    ? new FallbackTranslationProvider(local, createApiProvider(apiFallbackProvider))
                    : local;
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
        // The same nine keys ForgeConfig.defaults() writes. Without them, a config file first created
        // by 1.8.9/1.12.2 was missing every HUD-indicator line, and the next Forge version to save
        // the same file added them — the file shape depended on which version touched it first.
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

    /**
     * The whole configuration file as it currently stands on disk, read at most once per revision.
     *
     * @param file configuration file; {@code null} reads nothing
     * @return the parsed file, or {@code null} when it cannot be read right now
     */
    private static Properties diskProperties(Path file) {
        if (file == null) {
            return null;
        }
        long lastModified;
        long size;
        try {
            lastModified = Files.getLastModifiedTime(file).toMillis();
            size = Files.size(file);
        } catch (IOException | RuntimeException missing) {
            return null;
        }
        DiskCopy cached = diskCopy;
        long saves = saveCounter;
        // save() already drops the copy under the monitor that performs the write, so this is the
        // second line of defence rather than the first: the counter catches the reader that read the
        // entry before the drop and reached the file after the replace. What no reader can catch is
        // another process's same-length rewrite inside one timestamp tick, and a clock cannot tell
        // those apart either.
        if (cached != null && cached.saveCount == saves && cached.file.equals(file)
                && cached.lastModified == lastModified && cached.size == size) {
            return cached.properties;
        }
        Properties stored = new Properties();
        try (InputStreamReader reader = new InputStreamReader(
                Files.newInputStream(file), StandardCharsets.UTF_8)) {
            stored.load(reader);
        } catch (IOException | RuntimeException malformed) {
            return null;
        }
        diskCopy = new DiskCopy(file, lastModified, size, saves, stored);
        return stored;
    }

    /** Drops the shared copy of the file; called when the client releases its configuration. */
    static void clearCaches() {
        diskCopy = null;
        saveCounter = 0L;
    }

    private Properties toProperties() {
        Properties properties = new Properties();
        onlineProviderConfig.writeTo(properties);
        // Keep the advanced settings the diagnostics panel may have changed after this snapshot,
        // except for the ones this snapshot wrote itself (see ownedPromptKeys).
        Properties stored = diskProperties(configFile.toPath());
        if (stored == null) {
            // Unreadable file: write this snapshot as it stands, and keep the trace the shared helper
            // logs so a value the panel just wrote is not lost silently.
            OnlineProviderConfig.preservePromptSettings(configFile.toPath(), properties,
                    ownedPromptKeys);
        } else {
            for (String key : OnlineProviderConfig.promptKeys()) {
                if (ownedPromptKeys.contains(key)) {
                    continue;
                }
                if (stored.containsKey(key)) {
                    properties.setProperty(key, stored.getProperty(key, ""));
                }
            }
        }
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

    /**
     * Reads a bounded integer, falling back to {@code fallback} when the value is unusable.
     *
     * <p>Every other implementation in this repository (ForgeConfig, FabricConfig both generations)
     * returns the fallback for an out-of-range value; this one clamped to the nearest end instead, so
     * a hand-edited {@code hud-indicator-size=99} showed the maximum size on 1.8.9/1.12.2 and the
     * default on every other Forge version — the same file, two different results.
     */
    private static int parseBoundedInt(String raw, int fallback, int min, int max) {
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value < min || value > max ? fallback : value;
        } catch (RuntimeException ignored) {
            // Covers a non-numeric value as well, so this cannot escape load().
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

