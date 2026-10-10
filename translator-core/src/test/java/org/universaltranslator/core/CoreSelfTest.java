package org.universaltranslator.core;

import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.JsonStrings;
import org.universaltranslator.core.net.TencentCloudV3Signer;
import org.universaltranslator.core.offline.VerifiedDownloader;
import org.universaltranslator.core.offline.SafeArchiveExtractor;
import org.universaltranslator.core.offline.OfflineEngineAsset;
import org.universaltranslator.core.offline.OfflineProcessSupport;
import org.universaltranslator.core.provider.FallbackTranslationProvider;
import org.universaltranslator.core.provider.LlamaCppOfflineProvider;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Dependency-free checks that can also run on legacy Java-compatible builds. */
public final class CoreSelfTest {
    public static void main(String[] args) throws Exception {
        protectsDynamicScoreboardValues();
        skipsAlreadyChineseAndNonTextValues();
        skipsModOwnMessagesInEveryLocale();
        protectsExistingChineseInMixedText();
        stylesCompletedTranslations();
        plansPerRunStyling();
        validatesSmallModelOutputs();
        preservesRecentUserMessages();
        cachesDynamicTemplates();
        deduplicatesConcurrentRequests();        deduplicatesRefreshedProtectedLiterals();
        separatesRequestsWithDifferentProtectedLiterals();
        completesQueuedRequestsWhenClosed();
        fallsBackToOriginalOnFailure();
        enforcesSafeEndpoints();
        handlesJsonStrings();
        updatesRenderLookupsWithoutBlocking();
        translatesRelatedTooltipLinesTogether();
        translatesOutgoingChatAsynchronously();
        publishesOutgoingTranslationWhileItIsGenerated();
        boundsOutgoingPreviewState();
        exposesRenderTranslationFailures();
        sanitizesProviderLabelsForLogs();
        protectsLiteralsOffTheRenderThread();
        boundsBusyLobbyTranslationWork();
        rateLimitsBusyLobbyWithoutStarvingTooltips();
        doesNotTranslateCompletedOutputAgain();
        persistsOnlyHashedCacheKeys();
        ignoresMalformedPersistentCache();
        protectsPlayerNames();
        protectsNetworkAddresses();
        skipsFullyProtectedText();
        neverSendsProtectedValuesToProvider();
        blocksConfiguredKeywords();
        discardsResultsBlockedWhileInFlight();
        separatesCacheEntriesByTextKind();
        prefersChinaDownloadSources();
        selectsAndroidOfflineRuntime();
        configuresWindowsOfflineRuntimePath();
        preparesAsciiWindowsModelPath();
        animatesSettingsUiDeterministically();
        laysOutInPlaceSettingsLists();
        keepsSettingsActionsReachable();
        exportsSecretFreeDiagnostics();
        reportsOfflineStartupDiagnostics();
        matchesTencentCloudOfficialSignatureVector();
        keepsOriginalTextInBilingualMode();
        fallsBackFromOfflineToApi();
        verifiesDownloadedFileHashes();
        reportsVerifiedDownloadProgress();
        promotesCompleteVerifiedPartialDownloads();
        extractsOfflineEngineArchivesSafely();
        normalizesOfflineModelSelections();
        supportsTraditionalChineseTargets();
        cyclesSelectableTargetLanguages();
        identifiesVanillaScreenContent();
        formatsSecretFreeDiagnostics();
        localizesDiagnosticsAndRuntimeStatus();
        handlesMalformedPlaceholderTokensGracefully();
        countsTranslationStatistics();
        classifiesTranslationFailures();
        persistsTranslationStatistics();
        redactsDebugLogSecrets();
        writesAndRotatesTheDebugLog();
        exportsADiagnosticsBundle();
        System.out.println("CoreSelfTest: all checks passed");
    }

    private static void translatesOutgoingChatAsynchronously() throws Exception {
        TranslationProvider provider = new TranslationProvider() {
            @Override
            public String id() {
                return "outgoing-test";
            }

            @Override
            public String translate(TranslationRequest request) {
                assertEquals("en", request.getTargetLanguage());
                // Protected player names are restored by the coordinator and must never be
                // included in the provider request or response.
                return "Hello";
            }
        };
        try (RenderTranslationSession session = new RenderTranslationSession(
                provider, "auto", "zh-CN", 100, 1)) {
            session.setProtectedLiteralsSupplier(() -> Arrays.asList("Steve_42"));
            TranslationResult result = session.translateInteractive(
                    "你好 Steve_42", TextKind.CHAT, "en", false)
                    .get(2, TimeUnit.SECONDS);
            assertTrue(result.isTranslated());
            assertEquals("Hello Steve_42", result.getTranslatedText());
        }
    }

    /**
     * The outgoing line has to show the translation while it is still being generated, and the
     * half-finished text must never reach the cache: a later request for the same text would then be
     * served a fragment. The provider blocks in the middle of the stream so both states are observed
     * instead of being raced against.
     */
    private static void publishesOutgoingTranslationWhileItIsGenerated() throws Exception {
        final CountDownLatch firstPartial = new CountDownLatch(1);
        final CountDownLatch resume = new CountDownLatch(1);
        final List<String> stored = Collections.synchronizedList(new ArrayList<String>());
        TranslationStore store = new TranslationStore() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public void put(String key, String value) {
                stored.add(value);
            }

            @Override
            public void clear() {
                stored.clear();
            }
        };
        TranslationProvider provider = new TranslationProvider() {
            @Override
            public String id() {
                return "streaming-outgoing-test";
            }

            @Override
            public String translate(TranslationRequest request) {
                throw new AssertionError("the outgoing line must use the streaming entry point");
            }

            @Override
            public String translateStreaming(
                    TranslationRequest request, TranslationStreamListener listener) {
                listener.onPartialText("Hel");
                firstPartial.countDown();
                awaitLatch(resume);
                listener.onPartialText("Hello");
                return "Hello";
            }
        };
        try (RenderTranslationSession session = new RenderTranslationSession(
                provider, "auto", "zh-CN", store, 1)) {
            session.setProtectedLiteralsSupplier(() -> Arrays.asList("Steve_42"));
            CompletableFuture<TranslationResult> pending = session.translateInteractive(
                    "你好 Steve_42", TextKind.CHAT, "en", false, true);
            assertTrue(firstPartial.await(5, TimeUnit.SECONDS));
            // The partial translation is on the HUD line while the provider is still working.
            assertEquals("Hel", OutgoingTranslationPreview.text());
            // Nothing partial was cached: only the validated final translation is written.
            assertTrue(stored.isEmpty());
            resume.countDown();
            TranslationResult result = pending.get(5, TimeUnit.SECONDS);
            assertTrue(result.isTranslated());
            assertEquals("Hello Steve_42", result.getTranslatedText());
            assertEquals(Collections.singletonList("Hello"), new ArrayList<String>(stored));
            // The line is released once the request settles, so it cannot outlive its own message.
            long deadline = System.currentTimeMillis() + 5_000L;
            while (OutgoingTranslationPreview.text() != null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertEquals(null, OutgoingTranslationPreview.text());
        }
    }

    /** The preview holder is shared by every platform, so its ownership rules are checked here. */
    private static void boundsOutgoingPreviewState() throws Exception {
        OutgoingTranslationPreview.setStaleMillisForTesting(50L);
        try {
            OutgoingTranslationPreview.Handle first = OutgoingTranslationPreview.begin();
            // Nothing is shown until the first fragment arrives.
            assertEquals(null, OutgoingTranslationPreview.text());
            // Formatting codes and control characters cannot reach a single-line HUD draw.
            first.onPartialText("  \u00a7a你好\n世界  ");
            assertEquals("你好 世界", OutgoingTranslationPreview.text());
            // A newer outgoing translation takes the line over, and the older one can neither
            // overwrite it nor release it.
            OutgoingTranslationPreview.Handle second = OutgoingTranslationPreview.begin();
            second.onPartialText("second");
            assertEquals("second", OutgoingTranslationPreview.text());
            first.onPartialText("late");
            assertEquals("second", OutgoingTranslationPreview.text());
            first.finish();
            assertEquals("second", OutgoingTranslationPreview.text());
            second.finish();
            assertEquals(null, OutgoingTranslationPreview.text());
            // A preview nobody released goes stale instead of pinning the line for the session.
            OutgoingTranslationPreview.Handle third = OutgoingTranslationPreview.begin();
            third.onPartialText("stale");
            assertEquals("stale", OutgoingTranslationPreview.text());
            Thread.sleep(150L);
            assertEquals(null, OutgoingTranslationPreview.text());
            // An endpoint that never stops talking cannot grow the line without bound, and a code
            // that arrives without its letter still leaves no dangling character behind.
            OutgoingTranslationPreview.Handle fourth = OutgoingTranslationPreview.begin();
            fourth.onPartialText("0123456789012345678901234567890123456789012345678901234567890");
            String bounded = OutgoingTranslationPreview.text();
            assertEquals(48, bounded.length());
            assertTrue(bounded.endsWith("..."));
            fourth.onPartialText("abc\u00a7");
            assertEquals("abc", OutgoingTranslationPreview.text());
            fourth.finish();
        } finally {
            OutgoingTranslationPreview.setStaleMillisForTesting(
                    OutgoingTranslationPreview.DEFAULT_STALE_MILLIS);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for the test to continue");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the test to continue", interrupted);
        }
    }

    private static void supportsTraditionalChineseTargets() {
        assertEquals("zh-TW", TargetLanguage.canonicalize("zh_Hant"));
        assertEquals("zh-TW", TargetLanguage.canonicalize("zh-HK"));
        assertEquals("zh-TW", TargetLanguage.nextPreset("zh-CN"));
        assertEquals("en", TargetLanguage.nextPreset("zh-TW"));
        assertEquals("繁體中文", TargetLanguage.displayName("zh-TW"));
        assertEquals("zt", TargetLanguage.libreTranslateCode("zh-TW"));
        assertEquals("zh", TargetLanguage.libreTranslateCode("zh-CN"));
        assertTrue(TargetLanguage.translationInstruction("zh-TW")
                .contains("Traditional Chinese characters"));
        assertFalse(LanguageHeuristics.shouldTranslate("金幣：123", "zh-TW"));
        assertTrue(LanguageHeuristics.shouldTranslate("Coins: 123", "zh-TW"));
    }

    private static void cyclesSelectableTargetLanguages() {
        String target = TargetLanguage.SIMPLIFIED_CHINESE;
        String[] expected = {
                "zh-TW", "en", "ja", "ko", "fr", "de", "es", "pt", "ru", "zh-CN"
        };
        for (String next : expected) {
            target = TargetLanguage.nextPreset(target);
            assertEquals(next, target);
            assertFalse(TargetLanguage.displayName(target).isEmpty());
            assertFalse(TargetLanguage.translationInstruction(target).isEmpty());
        }
        assertEquals("pt", TargetLanguage.canonicalize("pt-BR"));
        assertEquals("ja", TargetLanguage.libreTranslateCode("ja-JP"));
    }

    private static void identifiesVanillaScreenContent() {
        assertTrue(MinecraftContentScope.isVanillaClassName(
                "net.minecraft.client.gui.screen.TitleScreen"));
        assertTrue(MinecraftContentScope.isVanillaClassName(
                "net.minecraft.class_500"));
        assertFalse(MinecraftContentScope.isVanillaClassName(
                "com.example.mod.CustomMenuScreen"));
        assertFalse(MinecraftContentScope.isVanillaClassName(null));
    }

    private static void localizesDiagnosticsAndRuntimeStatus() {
        UiTranslator translator = new UiTranslator() {
            @Override
            public String translate(String key, Object... arguments) {
                return key + (arguments.length == 0 ? "" : "=" + java.util.Arrays.toString(arguments));
            }
        };
        TranslationDiagnosticsSnapshot snapshot = new TranslationDiagnosticsSnapshot(
                true, "offline", "offline-llama:model", "zh-TW", OfflineModel.LITE,
                true, true, OfflineModel.LITE.expectedBytes(), 1000L, "离线模型运行中");
        String output = String.join("\n", snapshot.localizedLines(translator));
        assertTrue(output.contains("screen.universal_translator.diagnostics.enabled"));
        assertTrue(output.contains("status.universal_translator.offline_running"));
        assertEquals("status.universal_translator.translation_failed=[timeout]",
                TranslationStatusLocalizer.localize("翻译失败：timeout", translator));
        assertEquals("status.universal_translator.primary_running",
                TranslationStatusLocalizer.localize("主翻译服务运行中", translator));
        assertTrue(TranslationStatusLocalizer.isFailure("离线翻译失败：timeout"));
        assertFalse(TranslationStatusLocalizer.isFailure("离线模型已就绪"));
        // isFailure must normalize exactly like localize, or a padded status is drawn
        // green and shown as an overlay instead of a red failure notification.
        assertTrue(TranslationStatusLocalizer.isFailure("  翻译失败：x  "));
        assertTrue(TranslationStatusLocalizer.isFailure("\t离线翻译失败：timeout\n"));
    }

    private static void normalizesOfflineModelSelections() throws Exception {
        assertEquals(OfflineModel.LITE, OfflineModel.fromConfig(null));
        assertEquals(OfflineModel.LITE, OfflineModel.fromConfig("unknown-model"));
        assertEquals(OfflineModel.LITE, OfflineModel.fromConfig(
                "qwen2.5-0.5b-instruct-q4-k-m"));
        assertEquals(OfflineModel.QUALITY, OfflineModel.fromConfig(" QUALITY "));
        assertEquals(OfflineModel.QUALITY, OfflineModel.fromConfig(
                "qwen2.5-1.5b-instruct-q4-k-m"));
        assertEquals(OfflineModel.QUALITY, OfflineModel.LITE.next());
        assertEquals(OfflineModel.LITE, OfflineModel.QUALITY.next());

        Path directory = Files.createTempDirectory("universal-translator-model-selection-");
        try (LlamaCppOfflineProvider provider = LlamaCppOfflineProvider.forModel(
                directory, false, "invalid-selection")) {
            assertEquals("offline-llama:" + OfflineModel.LITE.modelId(), provider.id());
        }
    }

    private static void formatsSecretFreeDiagnostics() {
        TranslationDiagnosticsSnapshot snapshot = new TranslationDiagnosticsSnapshot(
                true,
                "offline",
                "fallback:offline-llama:model:libretranslate:https://secret.example/translate",
                "zh-CN",
                OfflineModel.QUALITY,
                true,
                true,
                OfflineModel.QUALITY.expectedBytes(),
                1_500L,
                "离线模型失败 https://secret.example/translate api-key=abc123\n重试中");
        String output = String.join("\n", snapshot.displayLines());
        assertTrue(output.contains("离线模型：Quality"));
        assertTrue(output.contains("模型文件：已安装并且大小正确"));
        assertTrue(output.contains("运行服务：离线模型 + API 回退"));
        assertFalse(output.contains("secret.example"));
        assertFalse(output.contains("https://"));
        assertFalse(output.contains("abc123"));
        assertFalse(output.contains("\n重试中"));
        assertTrue(output.contains("[地址已隐藏]"));
        assertTrue(output.contains("api-key=[已隐藏]"));
    }

    private static void keepsOriginalTextInBilingualMode() throws Exception {
        CountingProvider provider = new CountingProvider(false);
        try (RenderTranslationSession session = new RenderTranslationSession(
                provider, "en", "zh-CN", new TranslationCache(100), 1,
                TranslationDisplayMode.ORIGINAL_AND_TRANSLATED)) {
            session.lookup("Coins: 42", TextKind.SCOREBOARD_LINE);
            long deadline = System.currentTimeMillis() + 2000L;
            String translated;
            do {
                Thread.sleep(10L);
                translated = session.lookup("Coins: 42", TextKind.SCOREBOARD_LINE);
            } while ("Coins: 42".equals(translated) && System.currentTimeMillis() < deadline);
            assertEquals("Coins: 42 \u00a78| \u00a7f\u91d1\u5e01: 42", translated);
        }
    }

    private static void fallsBackFromOfflineToApi() throws Exception {
        CountingProvider primary = new CountingProvider(true);
        CountingProvider fallback = new CountingProvider(false);
        TranslationProvider provider = new FallbackTranslationProvider(primary, fallback);
        String translated = provider.translate(new TranslationRequest(
                "Coins: 8", "en", "zh-CN", TextKind.SCOREBOARD_LINE));
        assertEquals("\u91d1\u5e01: 8", translated);
        assertEquals(1, primary.calls.get());
        assertEquals(1, fallback.calls.get());
        assertEquals("主翻译服务失败，已使用 API 回退",
                ((TranslationProviderStatus) provider).status());
    }

    private static void verifiesDownloadedFileHashes() throws Exception {
        Path file = Files.createTempFile("universal-translator-hash-", ".txt");
        Files.write(file, "offline".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("8e2c7ac508139a02af859de64a4743c1f3946837279332c35ec8f5ddf20654ae",
                VerifiedDownloader.sha256(file));
    }

    private static void reportsVerifiedDownloadProgress() throws Exception {
        Path file = Files.createTempFile("universal-translator-progress-", ".txt");
        byte[] bytes = "offline".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, bytes);
        AtomicLong downloaded = new AtomicLong();
        AtomicLong total = new AtomicLong();
        VerifiedDownloader.download(
                Arrays.asList(URI.create("https://example.invalid/model")),
                file,
                bytes.length,
                VerifiedDownloader.sha256(file),
                (current, expected) -> {
                    downloaded.set(current);
                    total.set(expected);
                });
        assertEquals((long) bytes.length, downloaded.get());
        assertEquals((long) bytes.length, total.get());
    }

    private static void promotesCompleteVerifiedPartialDownloads() throws Exception {
        Path directory = Files.createTempDirectory("universal-translator-complete-partial-");
        Path destination = directory.resolve("model.gguf");
        Path partial = directory.resolve("model.gguf.part");
        byte[] bytes = "complete-model".getBytes(StandardCharsets.UTF_8);
        Files.write(partial, bytes);
        VerifiedDownloader.download(
                Arrays.asList(URI.create("https://example.invalid/model")),
                destination,
                bytes.length,
                VerifiedDownloader.sha256(partial));
        assertTrue(Files.isRegularFile(destination));
        assertFalse(Files.exists(partial));
        assertEquals("complete-model", new String(Files.readAllBytes(destination), StandardCharsets.UTF_8));
    }

    private static void extractsOfflineEngineArchivesSafely() throws Exception {
        Path directory = Files.createTempDirectory("universal-translator-archive-");
        Path tar = directory.resolve("engine.tar.gz");
        byte[] script = "#!/bin/sh\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (OutputStream file = Files.newOutputStream(tar);
             GZIPOutputStream gzip = new GZIPOutputStream(file)) {
            writeTarEntry(gzip, "llama-test/llama-server", script);
            gzip.write(new byte[1024]);
        }
        Path tarOutput = directory.resolve("tar-output");
        SafeArchiveExtractor.extract(tar, tarOutput);
        assertTrue(Files.isRegularFile(tarOutput.resolve("llama-test/llama-server")));

        Path zip = directory.resolve("engine.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("llama-test/llama-server.exe"));
            output.write(script);
            output.closeEntry();
        }
        Path zipOutput = directory.resolve("zip-output");
        SafeArchiveExtractor.extract(zip, zipOutput);
        assertTrue(Files.isRegularFile(zipOutput.resolve("llama-test/llama-server.exe")));

        Path unsafeZip = directory.resolve("unsafe.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(unsafeZip))) {
            output.putNextEntry(new ZipEntry("../escape"));
            output.write(script);
            output.closeEntry();
        }
        assertThrows(() -> SafeArchiveExtractor.extract(unsafeZip, directory.resolve("unsafe-output")));

        Path excessiveEntriesZip = directory.resolve("excessive-entries.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(excessiveEntriesZip))) {
            for (int index = 0; index <= 10_000; index++) {
                output.putNextEntry(new ZipEntry("entry-" + index + "/"));
                output.closeEntry();
            }
        }
        assertThrows(() -> SafeArchiveExtractor.extract(
                excessiveEntriesZip, directory.resolve("excessive-entries-output")));
    }

    private static void writeTarEntry(OutputStream output, String name, byte[] data) throws Exception {
        byte[] header = new byte[512];
        byte[] encodedName = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(encodedName, 0, header, 0, encodedName.length);
        writeTarOctal(header, 100, 8, 0755);
        writeTarOctal(header, 108, 8, 0);
        writeTarOctal(header, 116, 8, 0);
        writeTarOctal(header, 124, 12, data.length);
        writeTarOctal(header, 136, 12, 0);
        Arrays.fill(header, 148, 156, (byte) ' ');
        header[156] = '0';
        byte[] magic = "ustar".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, header, 257, magic.length);
        long checksum = 0L;
        for (byte item : header) {
            checksum += item & 0xff;
        }
        String checksumText = String.format("%06o", checksum);
        byte[] checksumBytes = checksumText.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(checksumBytes, 0, header, 148, checksumBytes.length);
        header[154] = 0;
        header[155] = ' ';
        output.write(header);
        output.write(data);
        int padding = (512 - (data.length % 512)) % 512;
        output.write(new byte[padding]);
    }

    private static void writeTarOctal(byte[] header, int offset, int length, long value) {
        String encoded = Long.toOctalString(value);
        int start = offset + length - 1 - encoded.length();
        Arrays.fill(header, offset, start, (byte) '0');
        byte[] bytes = encoded.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, start, bytes.length);
        header[offset + length - 1] = 0;
    }

    private static void doesNotTranslateCompletedOutputAgain() throws Exception {
        CountingProvider provider = new CountingProvider(false);
        try (RenderTranslationSession session = new RenderTranslationSession(
                provider, "auto", "zh-CN", 100, 1)) {
            session.lookup("Coins: 42", TextKind.OTHER);
            long deadline = System.currentTimeMillis() + 2000L;
            String translated;
            do {
                Thread.sleep(10L);
                translated = session.lookup("Coins: 42", TextKind.OTHER);
            } while ("Coins: 42".equals(translated) && System.currentTimeMillis() < deadline);
            assertEquals("\u91d1\u5e01: 42", translated);
            assertEquals("\u91d1\u5e01: 42", session.lookup(translated, TextKind.OTHER));
            Thread.sleep(50L);
            assertEquals(1, provider.calls.get());
        }
    }

    private static void enforcesSafeEndpoints() {
        assertEquals("http", EndpointPolicy.requireSafeEndpoint("http://127.0.0.1:5000/translate").getScheme());
        assertEquals("http", EndpointPolicy.requireSafeEndpoint("http://192.168.1.100:11434/v1/chat/completions").getScheme());
        assertEquals("http", EndpointPolicy.requireSafeEndpoint("http://10.0.0.2:5000/translate").getScheme());
        assertEquals("https", EndpointPolicy.requireSafeEndpoint("https://translate.example/translate").getScheme());
        assertThrows(() -> EndpointPolicy.requireSafeEndpoint("http://translate.example/translate"));
        assertThrows(() -> EndpointPolicy.requireSafeEndpoint("http://8.8.8.8:5000/translate"));
        assertThrows(() -> EndpointPolicy.requireSafeEndpoint("https://user:secret@translate.example/translate"));
    }

    private static void handlesMalformedPlaceholderTokensGracefully() {
        ProtectedText text = ProtectedText.parse("Hello __UT_999__ world");
        assertEquals("Hello __UT_999__ world", text.getOriginal());
        java.util.List<?> segments = text.getSegments();
        assertFalse(segments.isEmpty());
    }

    private static void handlesJsonStrings() {
        String value = "line 1\n\"\u91d1\u5e01\" \\";
        String json = "{\"translatedText\":" + JsonStrings.quote(value) + "}";
        assertEquals(value, JsonStrings.readStringField(json, "translatedText"));
        assertEquals(null, JsonStrings.readStringField(json, "missing"));
        assertEquals("\u91d1\u5e01", JsonStrings.readStringField(
                "{\"Response\":{\"Choices\":[{\"Message\":{\"Content\":\"\\u91d1\\u5e01\"}}]}}",
                "Content"));
    }

    private static void matchesTencentCloudOfficialSignatureVector() throws Exception {
        String payload = "{\"Limit\": 1, \"Filters\": [{\"Values\": [\"\\u672a\\u547d\\u540d\"], \"Name\": \"instance-name\"}]}";
        Map<String, String> headers = TencentCloudV3Signer.headers(
                "cvm",
                "cvm.tencentcloudapi.com",
                "DescribeInstances",
                "2017-03-12",
                "AKID********************************",
                "********************************",
                payload,
                1551113065L);
        assertEquals(
                "TC3-HMAC-SHA256 Credential=AKID********************************/2019-02-25/cvm/tc3_request, "
                        + "SignedHeaders=content-type;host;x-tc-action, "
                        + "Signature=10b1a37a7301a02ca19a647ad722d5e43b4b3cff309d421d85b46093f6ab6c4f",
                headers.get("Authorization"));
        assertEquals("1551113065", headers.get("X-TC-Timestamp"));
    }

    private static void updatesRenderLookupsWithoutBlocking() throws Exception {
        CountingProvider provider = new CountingProvider(false);
        try (RenderTranslationSession session = new RenderTranslationSession(
                provider, "auto", "zh-CN", 100, 1)) {
            assertEquals("Coins: 42", session.lookup("Coins: 42", TextKind.SCOREBOARD_LINE));
            long deadline = System.currentTimeMillis() + 2000L;
            String translated;
            do {
                Thread.sleep(10L);
                translated = session.lookup("Coins: 42", TextKind.SCOREBOARD_LINE);
            } while ("Coins: 42".equals(translated) && System.currentTimeMillis() < deadline);
            assertEquals("\u91d1\u5e01: 42", translated);
        }
    }

    private static void translatesRelatedTooltipLinesTogether() throws Exception {
        CountingProvider provider = new CountingProvider(false);
        try (RenderTranslationSession session = new RenderTranslationSession(
                provider, "auto", "zh-CN", 100, 1)) {
            java.util.List<String> original = Arrays.asList("Players online", "Coins");
            assertEquals(original, session.lookupLines(original, TextKind.TOOLTIP));
            long deadline = System.currentTimeMillis() + 2000L;
            java.util.List<String> translated;
            do {
                Thread.sleep(10L);
                translated = session.lookupLines(original, TextKind.TOOLTIP);
            } while (original.equals(translated) && System.currentTimeMillis() < deadline);
            assertEquals(Arrays.asList("在线玩家", "金币"), translated);
            assertEquals(1, provider.calls.get());
        }
    }

    private static void exposesRenderTranslationFailures() throws Exception {
        CountingProvider provider = new CountingProvider(true);
        try (RenderTranslationSession session = new RenderTranslationSession(
                provider, "auto", "zh-CN", 100, 1)) {
            session.lookup("Server restarting", TextKind.TITLE);
            long deadline = System.currentTimeMillis() + 2000L;
            while (session.lastFailureStatus().isEmpty()
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertTrue(session.lastFailureStatus().startsWith("翻译失败：simulated outage"));
        }
    }

    private static void sanitizesProviderLabelsForLogs() {
        assertEquals("libretranslate", RenderTranslationSession.safeProviderCategoryForLog(
                "libretranslate:http://127.0.0.1:5000/translate?api_key=secret"));
        assertEquals("openai-compatible", RenderTranslationSession.safeProviderCategoryForLog(
                "openai-compatible:private-model-name"));
        assertEquals("fallback", RenderTranslationSession.safeProviderCategoryForLog(
                "fallback:offline-llama:lite:custom-http-json:private.example"));
        assertFalse(RenderTranslationSession.safeProviderCategoryForLog(
                "custom\nprovider:https://private.example").contains("private.example"));
    }

    private static void protectsLiteralsOffTheRenderThread() throws Exception {
        CountingProvider provider = new CountingProvider(false);
        AtomicReference<String> iterationThread = new AtomicReference<String>();
        Iterable<String> names = new Iterable<String>() {
            @Override
            public java.util.Iterator<String> iterator() {
                iterationThread.set(Thread.currentThread().getName());
                return Arrays.asList("Steve_42", "Alex_7").iterator();
            }
        };
        try (RenderTranslationSession session = new RenderTranslationSession(
                provider, "auto", "zh-CN", 100, 1)) {
            session.setProtectedLiteralsSupplier(() -> names);
            long started = System.nanoTime();
            assertEquals("Welcome Steve_42", session.lookup("Welcome Steve_42", TextKind.CHAT));
            long callerMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(callerMillis < 250L);

            long deadline = System.currentTimeMillis() + 2000L;
            while (iterationThread.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertTrue(iterationThread.get() != null
                    && iterationThread.get().startsWith("universal-translator-"));
        }
    }

    private static void boundsBusyLobbyTranslationWork() throws Exception {
        BlockingProvider provider = new BlockingProvider();
        try (RenderTranslationSession session = new RenderTranslationSession(
                provider, "auto", "zh-CN", 100, 1)) {
            long started = System.nanoTime();
            for (int index = 0; index < 5_000; index++) {
                session.lookup("Player message " + index, TextKind.OTHER);
            }
            long callerMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(callerMillis < 1_000L);
        } finally {
            provider.release.countDown();
        }
    }

    private static void rateLimitsBusyLobbyWithoutStarvingTooltips() throws Exception {
        KindRecordingProvider provider = new KindRecordingProvider();
        try (RenderTranslationSession session = new RenderTranslationSession(
                provider, "auto", "zh-CN", 100, 1)) {
            for (int index = 0; index < 500; index++) {
                session.lookup("Transient lobby label " + index, TextKind.OTHER);
            }
            session.lookup("Special tooltip description", TextKind.TOOLTIP);
            long deadline = System.currentTimeMillis() + 2_000L;
            while (provider.lastKind.get() != TextKind.TOOLTIP
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertEquals(TextKind.TOOLTIP, provider.lastKind.get());
            assertTrue(provider.calls.get() <= 5);
        }
    }

    private static void persistsOnlyHashedCacheKeys() throws Exception {
        Path directory = Files.createTempDirectory("universal-translator-test-");
        Path file = directory.resolve("cache.properties");
        PersistentTranslationCache first = new PersistentTranslationCache(file, 10);
        first.put("Server secret text", "\u670d\u52a1\u5668\u6587\u672c");
        String persisted = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(persisted.contains("Server secret text"));
        PersistentTranslationCache second = new PersistentTranslationCache(file, 10);
        assertEquals("\u670d\u52a1\u5668\u6587\u672c", second.get("Server secret text"));
    }

    private static void protectsPlayerNames() {
        ProtectedText text = ProtectedText.parse(
                "Welcome Steve_42, balance 500", Arrays.asList("Steve_42"));
        assertEquals("Welcome __UT_0__, balance __UT_1__", text.getTemplate());
        assertEquals("\u6b22\u8fce Steve_42\uff0c\u4f59\u989d 500", text.restore("\u6b22\u8fce __UT_0__\uff0c\u4f59\u989d __UT_1__"));

        ProtectedText formatted = ProtectedText.parse(
                "\u00a7b[MVP+] Steve_42: Welcome", Arrays.asList("Steve_42"));
        assertEquals("__UT_0__[MVP+] __UT_1__: Welcome", formatted.getTemplate());
    }

    private static void protectsNetworkAddresses() {
        String original = "Join play.example.cn:25565, 203.0.113.7:25565, "
                + "[2001:db8::1]:25565 or localhost:5000";
        ProtectedText text = ProtectedText.parse(original);
        assertEquals("Join __UT_0__, __UT_1__, __UT_2__ or __UT_3__", text.getTemplate());
        assertEquals(original, text.restore("Join __UT_0__, __UT_1__, __UT_2__ or __UT_3__"));
    }

    private static void skipsFullyProtectedText() throws Exception {
        CountingProvider provider = new CountingProvider(false);
        try (TranslationCoordinator coordinator = new TranslationCoordinator(
                provider, new TranslationCache(10), 1)) {
            TranslationResult address = coordinator.translate(
                    "play.example.cn:25565", "auto", "zh-CN", TextKind.OTHER)
                    .get(2, TimeUnit.SECONDS);
            TranslationResult player = coordinator.translate(
                    "Steve_42", "auto", "zh-CN", TextKind.CHAT,
                    Arrays.asList("Steve_42")).get(2, TimeUnit.SECONDS);
            assertEquals("play.example.cn:25565", address.getTranslatedText());
            assertEquals("Steve_42", player.getTranslatedText());
            assertEquals(0, provider.calls.get());
        }
    }

    private static void neverSendsProtectedValuesToProvider() throws Exception {
        SegmentRecordingProvider provider = new SegmentRecordingProvider();
        String original = "Welcome Steve_42 at play.example.cn:25565 with 42 coins";
        try (TranslationCoordinator coordinator = new TranslationCoordinator(
                provider, new TranslationCache(20), 1)) {
            TranslationResult result = coordinator.translate(
                    original, "auto", "zh-CN", TextKind.CHAT,
                    Arrays.asList("Steve_42")).get(2, TimeUnit.SECONDS);
            assertTrue(result.getTranslatedText().contains("Steve_42"));
            assertTrue(result.getTranslatedText().contains("play.example.cn:25565"));
            assertTrue(result.getTranslatedText().contains("42"));
            String requests = provider.requests.toString();
            assertFalse(requests.contains("Steve_42"));
            assertFalse(requests.contains("play.example.cn"));
            assertFalse(requests.contains("42"));
            assertFalse(requests.contains("__UT_"));
        }
    }

    private static void prefersChinaDownloadSources() {
        assertEquals("modelscope.cn", LlamaCppOfflineProvider.DEFAULT_MODEL_CHINA_URI.getHost());
        assertEquals("huggingface.co", LlamaCppOfflineProvider.DEFAULT_MODEL_URI.getHost());
        OfflineEngineAsset engine = OfflineEngineAsset.current();
        assertEquals("gh-proxy.com", engine.downloadSources().get(0).getHost());
        assertEquals("github.com", engine.downloadSources().get(1).getHost());
    }

    private static void configuresWindowsOfflineRuntimePath() throws Exception {
        Path directory = Files.createTempDirectory("offline-process-path");
        Path server = Files.createDirectories(directory.resolve("engine"));
        Path javaBin = Files.createDirectories(directory.resolve("java-bin"));
        ProcessBuilder builder = new ProcessBuilder("offline-test");
        builder.environment().put("PATH", "existing-path");
        OfflineProcessSupport.prependWindowsLibraryPath(builder, server, javaBin);
        String expectedPrefix = server.toAbsolutePath().normalize().toString()
                + File.pathSeparator + javaBin.toAbsolutePath().normalize().toString()
                + File.pathSeparator;
        assertTrue(builder.environment().get("PATH").startsWith(expectedPrefix));

        ProcessBuilder androidBuilder = new ProcessBuilder("offline-test");
        androidBuilder.environment().put("LD_LIBRARY_PATH", "existing-library-path");
        OfflineProcessSupport.prependEnvironmentPath(
                androidBuilder, "LD_LIBRARY_PATH", server);
        assertTrue(androidBuilder.environment().get("LD_LIBRARY_PATH")
                .startsWith(server.toAbsolutePath().normalize().toString()
                        + File.pathSeparator));
    }

    private static void selectsAndroidOfflineRuntime() {
        assertTrue(OfflineEngineAsset.isAndroidRuntime(
                "Linux", "OpenJDK Runtime Environment", "/data/user/0/net.kdt.pojavlaunch", false));
        assertTrue(OfflineEngineAsset.isAndroidRuntime(
                "Linux", "OpenJDK Runtime Environment", "/home/player", true));
        assertFalse(OfflineEngineAsset.isAndroidRuntime(
                "Linux", "OpenJDK Runtime Environment", "/home/player", false));

        OfflineEngineAsset android = OfflineEngineAsset.select("Linux", "aarch64", true);
        assertEquals("android-arm64", android.platformId);
        assertEquals("llama-b9637-bin-android-arm64.tar.gz", android.archiveName);
        assertEquals(75_515_871L, android.size);
        assertEquals("66068af2400dbaaadb4dc3e4042d120c6633f115ecd2fe1a8979fb55e0648e4d",
                android.sha256);
        assertEquals("linux-arm64",
                OfflineEngineAsset.select("Linux", "aarch64", false).platformId);
        assertThrows(() -> OfflineEngineAsset.select("Linux", "x86_64", true));

        assertTrue(OfflineProcessSupport.isAndroidSharedStorage(
                java.nio.file.Paths.get("/storage/emulated/0/games/PojavLauncher")));
        assertFalse(OfflineProcessSupport.isAndroidSharedStorage(
                java.nio.file.Paths.get("/data/user/0/net.kdt.pojavlaunch/cache")));
    }

    private static void preparesAsciiWindowsModelPath() throws Exception {
        Path asciiRoot = Files.createTempDirectory("offline-ascii-root");
        Path unicodeDirectory = Files.createDirectories(asciiRoot.resolve("游戏目录"));
        Path model = unicodeDirectory.resolve("qwen.gguf");
        byte[] contents = "verified-model-data".getBytes(StandardCharsets.UTF_8);
        Files.write(model, contents);

        String modelDigest = org.universaltranslator.core.offline.VerifiedDownloader.sha256(model);
        Path alias = OfflineProcessSupport.prepareModelPathForNativeProcess(
                model, modelDigest, true);
        assertTrue(OfflineProcessSupport.isAsciiPath(alias));
        assertFalse(alias.equals(model.toAbsolutePath().normalize()));
        assertTrue(Arrays.equals(contents, Files.readAllBytes(alias)));
        assertEquals(alias, OfflineProcessSupport.prepareModelPathForNativeProcess(
                model, modelDigest, true));

        Files.delete(alias);
        Files.write(alias, "damaged--model-data".getBytes(StandardCharsets.UTF_8));
        assertEquals(alias, OfflineProcessSupport.prepareModelPathForNativeProcess(
                model, modelDigest, true));
        assertTrue(Arrays.equals(contents, Files.readAllBytes(alias)));

        Path alreadyAscii = asciiRoot.resolve("qwen.gguf");
        Files.write(alreadyAscii, contents);
        assertEquals(alreadyAscii.toAbsolutePath().normalize(),
                OfflineProcessSupport.prepareModelPathForNativeProcess(
                        alreadyAscii, modelDigest, true));
    }

    private static void animatesSettingsUiDeterministically() {
        long start = 1_000_000_000L;
        assertEquals(0.0F, SettingsUiAnimation.openProgress(start, start));
        assertEquals(1.0F, SettingsUiAnimation.openProgress(
                start, start + SettingsUiAnimation.OPEN_DURATION_NANOS));
        float midpoint = SettingsUiAnimation.openProgress(
                start, start + SettingsUiAnimation.OPEN_DURATION_NANOS / 2L);
        assertTrue(midpoint > 0.49F && midpoint < 0.51F);
        assertEquals(150, SettingsUiAnimation.openingOverlayAlpha(0.0F));
        assertEquals(0, SettingsUiAnimation.openingOverlayAlpha(1.0F));
        assertEquals(50, SettingsUiAnimation.expandingHalfWidth(100, 0.5F));
        assertTrue(SettingsUiAnimation.sweepX(10, 110, start) >= 10);
        assertTrue(SettingsUiAnimation.sweepX(10, 110, start) <= 110);
        assertEquals(0xFF000000, SettingsUiAnimation.pulseColor(start) & 0xFF000000);
    }

    private static void laysOutInPlaceSettingsLists() {
        assertEquals(16, SettingsSelectionList.values(
                SettingsSelectionList.Kind.PROVIDER).length);
        assertEquals(10, SettingsSelectionList.values(
                SettingsSelectionList.Kind.TARGET_LANGUAGE).length);
        SettingsSelectionList.Layout layout = SettingsSelectionList.layout(320, 240, 16);
        assertTrue(layout.x(0) < layout.x(1));
        assertEquals(layout.y(0), layout.y(1));
        assertTrue(layout.y(2) > layout.y(0));
        assertTrue(layout.panelBottom < 240);
        assertEquals(0, layout.optionAt(
                layout.x(0) + 1, layout.y(0) + 1, 16));
        assertEquals(1, layout.optionAt(
                layout.x(1) + 1, layout.y(1) + 1, 16));
        assertEquals(-1, layout.optionAt(0, 0, 16));
        assertTrue(layout.contains(layout.panelLeft(), layout.panelTop));
        assertFalse(layout.contains(0, 0));

        // Mobile launchers, custom GUI scales and embedded windows can be narrower than
        // 200px or shorter than 240px. The columns must stay on-screen and the panel
        // background must still enclose every row it draws.
        int[][] sizes = new int[][] {
                {160, 240}, {320, 160}, {320, 240}, {854, 480}, {160, 160}, {180, 200}
        };
        for (int[] size : sizes) {
            SettingsSelectionList.Layout compact =
                    SettingsSelectionList.layout(size[0], size[1], 16);
            assertTrue(compact.left >= 0);
            assertTrue(compact.panelLeft() >= 0);
            assertTrue(compact.panelRight() <= size[0]);
            for (int index = 0; index < 16; index++) {
                assertTrue(compact.x(index) >= 0);
                assertTrue(compact.x(index) + compact.buttonWidth <= size[0]);
            }
            int lastRowBottom = compact.y(7 * 2) + compact.buttonHeight;
            assertTrue(lastRowBottom <= compact.panelBottom);
            assertTrue(compact.panelBottom <= size[1]);
        }
    }

    private static void keepsSettingsActionsReachable() {
        int[] widths = new int[] {160, 180, 320, 854};
        int[] heights = new int[] {160, 180, 200, 220, 240, 252, 256, 260, 268, 300, 320, 360};
        for (int width : widths) {
            for (int height : heights) {
                SettingsScreenLayout.Geometry layout = SettingsScreenLayout.calculate(width, height);
                assertTrue(layout.left() >= 0);
                assertTrue(layout.right() + layout.buttonWidth() <= width);
                assertTrue(layout.saveY() >= 0);
                assertTrue(layout.saveY() + SettingsScreenLayout.BUTTON_HEIGHT <= height);
                assertTrue(layout.endpointY() + SettingsScreenLayout.BUTTON_HEIGHT <= layout.saveY());
                assertTrue(layout.tabY() >= 0);
                assertTrue(layout.tabX(3) + layout.tabWidth() <= width);
                assertTrue(layout.contentTop() >= layout.tabY() + SettingsScreenLayout.BUTTON_HEIGHT);
                if (height >= 252) {
                    assertTrue(layout.top() >= SettingsScreenLayout.HEADER_BOTTOM + 2);
                }
            }
        }
    }

    private static void exportsSecretFreeDiagnostics() throws Exception {
        Path directory = Files.createTempDirectory("universal-translator-diagnostics-");
        Path report = DiagnosticsLogExporter.export(directory, java.util.Arrays.asList(
                "Runtime: failed https://secret.example/translate",
                "api-key=abc123",
                "authorization: Bearer private-token",
                "Authorization Bearer 9f8e7d6c5b4a3210",
                "baidu-secret=9f8e7d6c",
                "Translation service returned HTTP 401: Incorrect API key provided: 9f8e7d6c5b4a"));
        String output = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(output.contains("MC Auto Translation Tool - Diagnostics"));
        assertFalse(output.contains("secret.example"));
        assertFalse(output.contains("abc123"));
        assertFalse(output.contains("private-token"));
        assertFalse(output.contains("9f8e7d6c5b4a3210"));
        assertFalse(output.contains("9f8e7d6c"));
        assertTrue(output.contains("[address hidden]"));
    }

    private static void reportsOfflineStartupDiagnostics() throws Exception {
        Path log = Files.createTempFile("offline-process", ".log");
        Files.write(log, "old output\n".getBytes(StandardCharsets.UTF_8));
        long offset = Files.size(log);
        Files.write(log, "missing model file\n".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);
        assertEquals("missing model file", OfflineProcessSupport.readNewLogTail(log, offset));
        String missingDependency = OfflineProcessSupport.describeStartupExit(
                OfflineProcessSupport.WINDOWS_MISSING_DEPENDENCY_EXIT, "");
        assertTrue(missingDependency.contains("Visual C++"));
        assertTrue(missingDependency.contains("0xC0000135"));
        assertTrue(OfflineProcessSupport.describeStartupExit(2, "bad option")
                .contains("bad option"));
        assertTrue(OfflineProcessSupport.describeStartupExit(1,
                "error: unknown argument: -fit").contains("启动参数不兼容"));
        assertTrue(OfflineProcessSupport.describeStartupExit(
                OfflineProcessSupport.WINDOWS_ILLEGAL_INSTRUCTION_EXIT, "")
                .contains("CPU 不支持"));
        assertTrue(OfflineProcessSupport.describeProcessStartFailure(
                new java.io.IOException("Permission denied")).contains("执行权限"));
        assertTrue(OfflineProcessSupport.describeStartupTimeout("model loading")
                .contains("model loading"));

        String llamaLog = "main: loading model\n"
                + "gguf_init_from_file_impl: failed to read magic\n"
                + "common_init_from_params: failed to load model 'C:\\\\models\\\\qwen.gguf'\n"
                + "srv operator(): operator(): cleaning up before exit...\n";
        String summary = OfflineProcessSupport.summarizeLog(llamaLog);
        assertTrue(summary.contains("failed to read magic"));
        assertFalse(summary.contains("cleaning up"));
        assertTrue(OfflineProcessSupport.describeStartupExit(1, summary)
                .contains("模型文件读取失败"));
        assertTrue(OfflineProcessSupport.describeStartupExit(1,
                "common_init_from_params: failed to load model 'C:\\\\bad\\\\qwen.gguf'")
                .contains("模型文件读取失败"));

        java.util.List<String> normal = new java.util.ArrayList<String>();
        OfflineProcessSupport.appendStableModelLoadingArguments(normal, false);
        assertEquals(Arrays.asList("-fit", "off", "--no-direct-io"), normal);
        java.util.List<String> conservative = new java.util.ArrayList<String>();
        OfflineProcessSupport.appendStableModelLoadingArguments(conservative, true);
        assertEquals(Arrays.asList("--no-mmap"), conservative);
    }

    private static void protectsDynamicScoreboardValues() {
        ProtectedText text = ProtectedText.parse("\u00a7aCoins: 12,583 | https://example.org | 75%");
        assertEquals("__UT_0__Coins: __UT_1__ | __UT_2__ | __UT_3__", text.getTemplate());
        assertEquals("\u00a7a\u91d1\u5e01: 12,583 | https://example.org | 75%", text.restore("__UT_0__\u91d1\u5e01: __UT_1__ | __UT_2__ | __UT_3__"));
        // A leading list bullet is decoration. A model that saw one replaced it with a different
        // glyph, and because the render bridge re-attaches styling per text run, that replacement
        // also shifted the colours of the line. Bullets are kept verbatim instead.
        ProtectedText bulleted = ProtectedText.parse("\u2022 Wins: 0");
        assertFalse(bulleted.getTemplate().contains("\u2022"));
        assertTrue(bulleted.getTemplate().startsWith("__UT_0__"));
        assertEquals("\u2022 \u52dd\u5229: 0", bulleted.restore("__UT_0__\u52dd\u5229: 0"));
    }

    private static void skipsAlreadyChineseAndNonTextValues() {
        assertFalse(LanguageHeuristics.shouldTranslate("\u91d1\u5e01\uff1a123", "zh-CN"));
        assertFalse(LanguageHeuristics.shouldTranslate("123 / 456", "zh-CN"));
        assertTrue(LanguageHeuristics.shouldTranslate("Coins: 123", "zh-CN"));
        assertTrue(LanguageHeuristics.shouldTranslate("欢迎 VIP", "zh-CN"));
    }

    private static void skipsModOwnMessagesInEveryLocale() {
        // The mod's own messages come from assets/universal_translator/lang/*. Only some of them
        // wrap the name in brackets, and the Traditional Chinese name is a different string from
        // the Simplified one, so a bracket-only literal list used to submit them for translation.
        // The expected strings below are copied from zh_cn.json, zh_tw.json and en_us.json.
        assertFalse(LanguageHeuristics.shouldTranslate(
                "MC Auto Translation Tool: Toggle failed", "zh-CN"));
        assertFalse(LanguageHeuristics.shouldTranslate(
                "\u00a7b[MC Auto Translation Tool] \u00a7fPress U to open settings.", "zh-CN"));
        assertFalse(LanguageHeuristics.shouldTranslate(
                "MC \u81ea\u52a8\u7ffb\u8bd1\u5de5\u5177\uff1a\u5207\u6362\u5931\u8d25", "en"));
        assertFalse(LanguageHeuristics.shouldTranslate(
                "MC \u81ea\u52a8\u7ffb\u8bd1\u5de5\u5177\uff1a\u5207\u6362\u5931\u8d25", "zh-CN"));
        assertFalse(LanguageHeuristics.shouldTranslate(
                "MC \u81ea\u52d5\u7ffb\u8b6f\u5de5\u5177\uff1a\u5207\u63db\u5931\u6557", "en"));
        assertFalse(LanguageHeuristics.shouldTranslate(
                "\u00a7c[MC \u81ea\u52d5\u7ffb\u8b6f\u5de5\u5177] \u5df2\u8207\u4f3a\u670d\u5668\u4e2d\u65b7\u9023\u7dda\u3002",
                "en"));
        assertFalse(LanguageHeuristics.shouldTranslate(
                "Universal Translator: Toggle failed", "zh-CN"));
        // Server text that merely mentions a translation tool must still be translated.
        assertTrue(LanguageHeuristics.shouldTranslate("Translation tool update available", "zh-CN"));
    }

    private static void protectsExistingChineseInMixedText() throws Exception {
        ProtectedText protectedText = ProtectedText.parse(
                "Welcome 欢迎 VIP 服务器", java.util.Collections.<String>emptyList(), true);
        assertEquals("Welcome __UT_0__ VIP __UT_1__", protectedText.getTemplate());
        assertEquals("欢迎 欢迎 贵宾 服务器",
                protectedText.restore("欢迎 __UT_0__ 贵宾 __UT_1__"));

        RecordingProvider provider = new RecordingProvider();
        try (TranslationCoordinator coordinator = new TranslationCoordinator(
                provider, new TranslationCache(10), 1)) {
            TranslationResult result = coordinator.translate(
                    "Welcome 欢迎", "auto", "zh-CN", TextKind.OTHER,
                    java.util.Collections.<String>emptyList(), true).get(2, TimeUnit.SECONDS);
            assertFalse(provider.lastRequest.get().contains("欢迎"));
            assertEquals("欢迎 欢迎", result.getTranslatedText());
        }
    }

    private static void stylesCompletedTranslations() {
        String styled = TranslationTextStyling.applyLegacyColor(
                "\u00a7aCoins \u00a7r42", TranslationTextColor.AQUA);
        assertEquals("\u00a7bCoins \u00a7r\u00a7b42\u00a7r", styled);
        assertEquals("Coins 42", TranslationTextStyling.stripLegacyFormatting(styled));
        assertEquals("Coins", TranslationTextStyling.applyLegacyColor(
                "Coins", TranslationTextColor.ORIGINAL));
        assertEquals("\u00a7d| \u00a7c金币 155", TranslationTextStyling.applyTranslatedStyle(
                "\u00a7d| \u00a7cCOINS 155", "\u00a7d| \u00a7c金币 155", TranslationTextColor.AQUA));
        assertEquals("\u00a7b金币 155\u00a7r", TranslationTextStyling.applyTranslatedStyle(
                "COINS 155", "金币 155", TranslationTextColor.AQUA));
        assertTrue(TranslationTextStyling.hasLegacyColor("\u00a7dINFORMATION"));
        assertFalse(TranslationTextStyling.hasLegacyColor("\u00a7lINFORMATION"));
    }

    private static void plansPerRunStyling() {
        java.util.List<TranslationStyleRuns.Run> merged = TranslationStyleRuns.mergeAdjacent(
                Arrays.asList("[Guild] ", "Steve", " says hi"),
                Arrays.<Object>asList("gold", "gold", "white"));
        assertEquals(2, merged.size());
        assertEquals("[Guild] Steve", merged.get(0).text());
        assertEquals(0, merged.get(0).sourceIndex());
        assertEquals(" says hi", merged.get(1).text());
        // sourceIndex is the index in the input list, not the index of the merged run: the
        // bridges use it to fetch the Style of the sibling the run starts at. " says hi" is the
        // third input entry, so its index is 2.
        assertEquals(2, merged.get(1).sourceIndex());
        assertEquals(2, TranslationStyleRuns.distinctStyleCount(merged));
        assertTrue(TranslationStyleRuns.shouldRebuildRuns(merged));
        assertTrue(TranslationStyleRuns.shouldRebuildRuns(merged, "gold"));
        assertTrue(TranslationStyleRuns.shouldRebuildRuns(merged, "white"));

        // A single run whose style is not the root's still has to be rebuilt: the colour of such
        // a line lives on a child component (a team prefix or suffix, or any nested component a
        // server builds), and a literal flattened with the root style alone drops it and draws
        // the line in the default colour.
        java.util.List<TranslationStyleRuns.Run> childStyled = TranslationStyleRuns.mergeAdjacent(
                Arrays.asList("AS Practice"), Arrays.<Object>asList("light-purple"));
        assertEquals(1, childStyled.size());
        assertFalse(TranslationStyleRuns.shouldRebuildRuns(childStyled));
        assertTrue(TranslationStyleRuns.shouldRebuildRuns(childStyled, "plain"));
        assertFalse(TranslationStyleRuns.shouldRebuildRuns(childStyled, "light-purple"));

        java.util.List<TranslationStyleRuns.Run> flat = TranslationStyleRuns.mergeAdjacent(
                Arrays.asList("Coins: ", "42"), Arrays.<Object>asList("plain", "plain"));
        assertEquals(1, flat.size());
        assertEquals("Coins: 42", flat.get(0).text());
        assertFalse(TranslationStyleRuns.shouldRebuildRuns(flat));
        assertFalse(TranslationStyleRuns.shouldRebuildRuns(flat, "plain"));
        assertTrue(TranslationStyleRuns.shouldRebuildRuns(flat, "light-purple"));
        assertFalse(TranslationStyleRuns.shouldRebuildRuns(TranslationStyleRuns.mergeAdjacent(
                java.util.Collections.<String>emptyList(),
                java.util.Collections.<Object>emptyList())));
        assertFalse(TranslationStyleRuns.shouldRebuildRuns(TranslationStyleRuns.mergeAdjacent(
                java.util.Collections.<String>emptyList(),
                java.util.Collections.<Object>emptyList()), "plain"));
        assertFalse(TranslationStyleRuns.shouldRebuildRuns(null));
        assertFalse(TranslationStyleRuns.shouldRebuildRuns(null, "plain"));
        assertEquals(0, TranslationStyleRuns.distinctStyleCount(null));

        assertEquals(TranslationTextColor.AQUA, TranslationStyleRuns.resolveTranslatedColor(
                false, TranslationTextColor.AQUA));
        assertEquals(TranslationTextColor.ORIGINAL, TranslationStyleRuns.resolveTranslatedColor(
                true, TranslationTextColor.AQUA));
        assertEquals(TranslationTextColor.ORIGINAL, TranslationStyleRuns.resolveTranslatedColor(
                false, TranslationTextColor.ORIGINAL));
        assertEquals(TranslationTextColor.ORIGINAL, TranslationStyleRuns.resolveTranslatedColor(
                false, null));
    }

    private static void validatesSmallModelOutputs() {
        assertEquals("欢迎 __UT_0__", TranslationOutputValidator.requireValid(
                "Welcome __UT_0__", "\"欢迎 __UT_0__\""));
        assertThrows(() -> TranslationOutputValidator.requireValid(
                "Start", repeat("开始", 100)));
        assertThrows(() -> TranslationOutputValidator.requireValid(
                "Welcome __UT_0__", "欢迎"));
        assertThrows(() -> TranslationOutputValidator.requireValid(
                "Welcome __UT_0__ and __UT_1__", "欢迎 __UT_1__ 和 __UT_0__"));
        assertThrows(() -> TranslationOutputValidator.requireValid(
                "Welcome", "Return only the translation. Welcome"));
        assertThrows(() -> TranslationOutputValidator.requireValid(
                "Welcome __UT_0__",
                "Minecraft server interface text from auto to zh-CN. Return only the translation. __UT_0__"));
        assertThrows(() -> TranslationOutputValidator.requireValid(
                "Welcome", "游戏服务器界面文本从自动翻译为中文，不要解释"));
        assertThrows(() -> TranslationOutputValidator.requireDisplaySafe(
                "Welcome Steve", "欢迎 __UT_0__"));
        assertThrows(() -> TranslationOutputValidator.requireDisplaySafe(
                "Welcome", "欢迎\n不要解释"));
        assertFalse(LanguageHeuristics.shouldTranslate(
                "Minecraft server interface text from auto to zh-CN", "zh-CN"));
    }

    private static void preservesRecentUserMessages() {
        RecentUserText recent = new RecentUserText();
        recent.remember("hello world");
        assertTrue(recent.shouldPreserve("hello world"));
        assertTrue(recent.shouldPreserve("<Player> hello world"));
        assertTrue(recent.shouldPreserve("Player: hello world"));
        assertTrue(recent.shouldPreserve("[MVP] Player » hello world"));
        assertTrue(recent.shouldPreserve("Player >> hello world"));
        assertFalse(recent.shouldPreserve("Server says hello"));
        recent.clear();
        assertFalse(recent.shouldPreserve("hello world"));
    }

    private static String repeat(String value, int count) {
        StringBuilder output = new StringBuilder(value.length() * count);
        for (int index = 0; index < count; index++) {
            output.append(value);
        }
        return output.toString();
    }

    private static void cachesDynamicTemplates() throws Exception {
        CountingProvider provider = new CountingProvider(false);
        try (TranslationCoordinator coordinator = new TranslationCoordinator(provider, new TranslationCache(100), 2)) {
            TranslationResult first = coordinator.translate("Coins: 100", "auto", "zh-CN", TextKind.SCOREBOARD_LINE)
                    .get(2, TimeUnit.SECONDS);
            TranslationResult second = coordinator.translate("Coins: 200", "auto", "zh-CN", TextKind.SCOREBOARD_LINE)
                    .get(2, TimeUnit.SECONDS);
            assertEquals("\u91d1\u5e01: 100", first.getTranslatedText());
            assertEquals("\u91d1\u5e01: 200", second.getTranslatedText());
            assertEquals(1, provider.calls.get());
        }
    }

    private static void deduplicatesConcurrentRequests() throws Exception {
        CountingProvider provider = new CountingProvider(false);
        try (TranslationCoordinator coordinator = new TranslationCoordinator(provider, new TranslationCache(100), 2)) {
            java.util.concurrent.CompletableFuture<TranslationResult> first =
                    coordinator.translate("Players online", "auto", "zh-CN", TextKind.PLAYER_LIST_HEADER);
            java.util.concurrent.CompletableFuture<TranslationResult> second =
                    coordinator.translate("Players online", "auto", "zh-CN", TextKind.PLAYER_LIST_HEADER);
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertEquals(1, provider.calls.get());
        }
    }

    private static void deduplicatesRefreshedProtectedLiterals() throws Exception {
        CountingProvider provider = new CountingProvider(false);
        try (TranslationCoordinator coordinator = new TranslationCoordinator(
                provider, new TranslationCache(100), 2)) {
            // The platform republishes the player-name snapshot as a new List instance every few
            // seconds. Equal contents must still share one in-flight request: an identity based
            // key sent the same line to the provider twice and billed a paid API twice.
            java.util.concurrent.CompletableFuture<TranslationResult> first = coordinator.translate(
                    "Players online", "auto", "zh-CN", TextKind.PLAYER_LIST_HEADER,
                    Arrays.asList("Steve_42", "Alex_7"));
            java.util.concurrent.CompletableFuture<TranslationResult> second = coordinator.translate(
                    "Players online", "auto", "zh-CN", TextKind.PLAYER_LIST_HEADER,
                    Arrays.asList("Steve_42", "Alex_7"));
            assertTrue(first == second);
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertEquals(1, provider.calls.get());
        }
    }

    private static void separatesRequestsWithDifferentProtectedLiterals() throws Exception {
        CountingProvider provider = new CountingProvider(false);
        try (TranslationCoordinator coordinator = new TranslationCoordinator(
                provider, new TranslationCache(100), 2)) {
            // A different snapshot must not reuse a future that was built with another
            // protection set, even when text, kind, languages and size are identical.
            java.util.concurrent.CompletableFuture<TranslationResult> first = coordinator.translate(
                    "Players online", "auto", "zh-CN", TextKind.PLAYER_LIST_HEADER,
                    Arrays.asList("Steve_42"));
            java.util.concurrent.CompletableFuture<TranslationResult> second = coordinator.translate(
                    "Players online", "auto", "zh-CN", TextKind.PLAYER_LIST_HEADER,
                    Arrays.asList("Alex_7"));
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertEquals(2, provider.calls.get());
        }
    }

    private static void completesQueuedRequestsWhenClosed() throws Exception {
        BlockingProvider provider = new BlockingProvider();
        TranslationCoordinator coordinator = new TranslationCoordinator(
                provider, new TranslationCache(100), 1);
        java.util.concurrent.CompletableFuture<TranslationResult> running =
                coordinator.translate("First queued translation", "auto", "zh-CN", TextKind.OTHER);
        java.util.concurrent.CompletableFuture<TranslationResult> queued =
                coordinator.translate("Second queued translation", "auto", "zh-CN", TextKind.OTHER);
        Thread.sleep(30L);
        coordinator.close();
        assertThrows(() -> running.get(1, TimeUnit.SECONDS));
        assertThrows(() -> queued.get(1, TimeUnit.SECONDS));
        provider.release.countDown();
    }

    private static void ignoresMalformedPersistentCache() throws Exception {
        Path directory = Files.createTempDirectory("universal-translator-malformed-cache-");
        Path file = directory.resolve("cache.properties");
        Files.write(file, "broken=\\u12".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        PersistentTranslationCache cache = new PersistentTranslationCache(file, 10);
        assertEquals(0, cache.size());
        cache.put("fresh", "新值");
        assertEquals("新值", cache.get("fresh"));
    }

    private static void fallsBackToOriginalOnFailure() throws Exception {
        CountingProvider provider = new CountingProvider(true);
        try (TranslationCoordinator coordinator = new TranslationCoordinator(provider, new TranslationCache(100), 1)) {
            TranslationResult result = coordinator.translate("Server restarting", "auto", "zh-CN", TextKind.TITLE)
                    .get(2, TimeUnit.SECONDS);
            assertTrue(result.isFailure());
            assertEquals("Server restarting", result.getTranslatedText());
        }
    }

    private static void blocksConfiguredKeywords() throws Exception {
        TranslationBlocklist blocklist = TranslationBlocklist.parse(
                " hello, Lobby\uff0cMaintenance\uff1bHELLO ");
        assertTrue(blocklist.matches("Say HeLLo to everyone"));
        assertTrue(blocklist.matches("Server maintenance starts soon"));
        assertFalse(blocklist.matches("Welcome to the server"));
        assertEquals(3, blocklist.keywords().size());

        CountingProvider provider = new CountingProvider(false);
        try (RenderTranslationSession session = new RenderTranslationSession(
                provider, "auto", "zh-CN", 100, 1)) {
            session.setBlockedKeywords("hello");
            assertEquals("Hello players", session.lookup("Hello players", TextKind.CHAT));
            TranslationResult result = session.translateInteractive(
                    "say HELLO", TextKind.CHAT, "en", false).get(2, TimeUnit.SECONDS);
            assertFalse(result.isTranslated());
            assertEquals("say HELLO", result.getTranslatedText());
            Thread.sleep(50L);
            assertEquals(0, provider.calls.get());
        }
    }

    private static void separatesCacheEntriesByTextKind() throws Exception {
        KindRecordingProvider provider = new KindRecordingProvider();
        try (TranslationCoordinator coordinator = new TranslationCoordinator(
                provider, new TranslationCache(100), 1)) {
            coordinator.translate("Welcome", "auto", "zh-CN", TextKind.CHAT)
                    .get(2, TimeUnit.SECONDS);
            coordinator.translate("Welcome", "auto", "zh-CN", TextKind.TITLE)
                    .get(2, TimeUnit.SECONDS);
            assertEquals(2, provider.calls.get());
            assertEquals(TextKind.TITLE, provider.lastKind.get());
        }
    }

    private static void discardsResultsBlockedWhileInFlight() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        TranslationProvider provider = new TranslationProvider() {
            @Override
            public String id() {
                return "filter-race-test";
            }

            @Override
            public String translate(TranslationRequest request) throws Exception {
                release.await(5L, TimeUnit.SECONDS);
                return "\u6b22\u8fce\u73a9\u5bb6";
            }
        };
        try (RenderTranslationSession session = new RenderTranslationSession(
                provider, "auto", "zh-CN", 100, 1)) {
            assertEquals("Welcome players", session.lookup("Welcome players", TextKind.CHAT));
            session.setBlockedKeywords("welcome");
            release.countDown();
            Thread.sleep(80L);
            assertEquals("Welcome players", session.lookup("Welcome players", TextKind.CHAT));
        } finally {
            release.countDown();
        }
    }

    private static final class CountingProvider implements TranslationProvider {
        private final AtomicInteger calls = new AtomicInteger();
        private final boolean fail;

        private CountingProvider(boolean fail) {
            this.fail = fail;
        }

        @Override
        public String id() {
            return "test";
        }

        @Override
        public String translate(TranslationRequest request) throws Exception {
            calls.incrementAndGet();
            if (fail) {
                throw new Exception("simulated outage");
            }
            Thread.sleep(30L);
            return request.getText()
                    .replace("Coins", "\u91d1\u5e01")
                    .replace("Players online", "\u5728\u7ebf\u73a9\u5bb6");
        }
    }

    private static final class BlockingProvider implements TranslationProvider {
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public String id() {
            return "blocking-test";
        }

        @Override
        public String translate(TranslationRequest request) throws Exception {
            release.await(5L, TimeUnit.SECONDS);
            return request.getText();
        }
    }

    private static final class KindRecordingProvider implements TranslationProvider {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<TextKind> lastKind = new AtomicReference<TextKind>();

        @Override
        public String id() {
            return "kind-recording-test";
        }

        @Override
        public String translate(TranslationRequest request) {
            calls.incrementAndGet();
            lastKind.set(request.getKind());
            return "译文";
        }
    }

    private static final class RecordingProvider implements TranslationProvider {
        private final AtomicReference<String> lastRequest = new AtomicReference<String>();

        @Override
        public String id() {
            return "recording-test";
        }

        @Override
        public String translate(TranslationRequest request) {
            lastRequest.set(request.getText());
            return request.getText().replace("Welcome", "欢迎");
        }
    }

    private static final class SegmentRecordingProvider implements TranslationProvider {
        private final StringBuilder requests = new StringBuilder();

        @Override
        public String id() {
            return "segment-recording-test";
        }

        @Override
        public synchronized String translate(TranslationRequest request) {
            requests.append(request.getText()).append('\n');
            return request.getText()
                    .replace("Welcome", "欢迎")
                    .replace("coins", "硬币");
        }
    }

    /**
     * The counters, the cache rate, the percentiles and the failure classification are pure logic
     * and are checked directly.
     */
    private static void countsTranslationStatistics() {
        TranslationStats stats = TranslationStats.isolated();
        assertCount(0L, stats.snapshot().requests());
        assertCount(0L, stats.snapshot().cacheHitRate());
        stats.recordCacheHit();
        stats.recordCacheMiss();
        stats.recordSuccess("deepl:host/path", 100L);
        stats.recordSuccess("deepl:host/path", 300L);
        stats.recordFailure("deepl:host/path", TranslationStats.REASON_AUTH, 200L);
        TranslationStats.Snapshot snapshot = stats.snapshot();
        assertCount(3L, snapshot.requests());
        assertCount(2L, snapshot.successes());
        assertCount(1L, snapshot.failures());
        assertCount(1L, snapshot.cacheHits());
        assertCount(1L, snapshot.cacheMisses());
        assertCount(50L, snapshot.cacheHitRate());
        // Samples are 100, 200 and 300 ms.
        assertCount(200L, snapshot.averageLatencyMillis());
        assertCount(200L, snapshot.percentileLatencyMillis(50));
        assertCount(300L, snapshot.percentileLatencyMillis(95));
        assertCount(3, snapshot.latencySampleCount());
        assertEquals(Long.valueOf(1L),
                snapshot.failuresByReason().get(TranslationStats.REASON_AUTH));
        assertCount(1, snapshot.providers().size());
        assertCount(3L, snapshot.providers().get(0).requests());
        assertCount(200L, snapshot.providers().get(0).averageLatencyMillis());
        // A provider that was never recorded is simply absent rather than invented.
        assertTrue(!snapshot.failuresByReason().containsKey(TranslationStats.REASON_TIMEOUT));
        // The token totals come from the protocol-specific usage shapes.
        stats.recordUsage("deepl:host/path",
                "{\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}");
        stats.recordUsage("gemini:model",
                "{\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":3}}");
        stats.recordUsage("claude:model",
                "{\"usage\":{\"input_tokens\":2,\"output_tokens\":9}}");
        stats.recordUsage("deepl:host/path", "{\"not\":\"a usage object\"}");
        assertCount(18L, stats.snapshot().promptTokens());
        assertCount(19L, stats.snapshot().completionTokens());
        // The exported report must not carry an endpoint, which is where a provider id puts one.
        assertTrue(TranslationStats.safeProviderId("libretranslate:https://host:5000/translate")
                .endsWith("/..."));
        assertTrue(!TranslationStats.safeProviderId("libretranslate:https://host:5000/translate")
                .contains("translate"));
        assertEquals("azure-openai:my-deployment",
                TranslationStats.safeProviderId("azure-openai:my-deployment"));
        assertTrue(TranslationStats.safeProviderId(null).equals("unknown"));
        stats.reset();
        assertCount(0L, stats.snapshot().requests());
        assertCount(0L, stats.snapshot().promptTokens());
        assertCount(0, stats.snapshot().providers().size());
    }

    /** Every failure kind the coordinator can see has to land in exactly one bucket. */
    private static void classifiesTranslationFailures() {
        assertEquals(TranslationStats.REASON_AUTH,
                TranslationStats.reasonOf(new org.universaltranslator.core.net.HttpStatusException(
                        401, "unauthorized")));
        assertEquals(TranslationStats.REASON_AUTH,
                TranslationStats.reasonOf(new org.universaltranslator.core.net.HttpStatusException(
                        403, "forbidden")));
        assertEquals(TranslationStats.REASON_RATE_LIMIT,
                TranslationStats.reasonOf(new org.universaltranslator.core.net.HttpStatusException(
                        429, "slow down")));
        assertEquals(TranslationStats.REASON_SERVER,
                TranslationStats.reasonOf(new org.universaltranslator.core.net.HttpStatusException(
                        503, "unavailable")));
        assertEquals(TranslationStats.REASON_CLIENT,
                TranslationStats.reasonOf(new org.universaltranslator.core.net.HttpStatusException(
                        400, "bad request")));
        assertEquals(TranslationStats.REASON_TIMEOUT, TranslationStats.reasonOf(
                new java.net.SocketTimeoutException("read timed out")));
        assertEquals(TranslationStats.REASON_NETWORK,
                TranslationStats.reasonOf(new java.io.IOException("connection reset")));
        assertEquals(TranslationStats.REASON_INVALID_OUTPUT,
                TranslationStats.reasonOf(new IllegalArgumentException("too long")));
        assertEquals(TranslationStats.REASON_OTHER,
                TranslationStats.reasonOf(new IllegalStateException("no content")));
        // A wrapped cause is classified by what is inside it, and a null has its own bucket.
        assertEquals(TranslationStats.REASON_AUTH, TranslationStats.reasonOf(new RuntimeException(
                new org.universaltranslator.core.net.HttpStatusException(401, "unauthorized"))));
        assertEquals(TranslationStats.REASON_OTHER, TranslationStats.reasonOf(null));
    }

    /** The counters survive a restart, and an unusable file never becomes a translation failure. */
    private static void persistsTranslationStatistics() throws Exception {
        Path file = Files.createTempFile("universal-translator-stats", ".properties");
        Files.deleteIfExists(file);
        Path temporary = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try {
            TranslationStats first = TranslationStats.isolated();
            first.attach(file);
            first.recordCacheHit();
            first.recordCacheMiss();
            first.recordSuccess("libretranslate:https://host/translate", 120L);
            first.recordFailure("libretranslate:https://host/translate",
                    TranslationStats.REASON_RATE_LIMIT, 80L);
            first.recordUsage("libretranslate:https://host/translate",
                    "{\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7}}");
            first.flush();
            assertTrue(Files.exists(file));

            TranslationStats second = TranslationStats.isolated();
            second.attach(file);
            TranslationStats.Snapshot snapshot = second.snapshot();
            assertCount(2L, snapshot.requests());
            assertCount(1L, snapshot.successes());
            assertCount(1L, snapshot.failures());
            assertCount(1L, snapshot.cacheHits());
            assertCount(1L, snapshot.cacheMisses());
            assertCount(11L, snapshot.promptTokens());
            assertCount(7L, snapshot.completionTokens());
            assertCount(1, snapshot.providers().size());
            assertEquals("libretranslate:https://host/translate", snapshot.providers().get(0).id());
            assertCount(100L, snapshot.providers().get(0).averageLatencyMillis());
            assertEquals(Long.valueOf(1L),
                    snapshot.failuresByReason().get(TranslationStats.REASON_RATE_LIMIT));

            // A malformed file starts the counters at zero rather than failing the platform.
            String malformed = "bad=" + '\\' + "uZZZZ";
            Files.write(file, malformed.getBytes(StandardCharsets.UTF_8));
            TranslationStats third = TranslationStats.isolated();
            third.attach(file);
            assertCount(0L, third.snapshot().requests());

            // An unwritable target and an unattached accumulator are both no-ops, not failures.
            TranslationStats unattached = TranslationStats.isolated();
            unattached.attach(null);
            unattached.recordSuccess("offline-llama:1", 5L);
            unattached.flush();
            assertCount(1L, unattached.snapshot().requests());
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(temporary);
        }
    }

    /** Every credential shape the debug log can be handed has to come out redacted. */
    private static void redactsDebugLogSecrets() {
        String openAiKey = "sk-abcdefghijklmnopqrstuvwxyz012345";
        String genericKey = "0123456789abcdef0123456789abcdef";
        String deeplKey = "12345678-1234-1234-1234-123456789012:fx";
        assertTrue(!DebugLog.redact("Authorization: Bearer " + openAiKey).contains(openAiKey));
        assertTrue(!DebugLog.redact("Authorization: Bearer " + genericKey).contains(genericKey));
        assertTrue(!DebugLog.redact("api-key=" + genericKey).contains(genericKey));
        assertTrue(!DebugLog.redact("x-api-key: " + genericKey).contains(genericKey));
        assertTrue(!DebugLog.redact("x-goog-api-key=" + genericKey).contains(genericKey));
        assertTrue(!DebugLog.redact("anthropic-version header, x-api-key " + genericKey)
                .contains(genericKey));
        // A credential name that carries a trailing -id, as several providers spell their key.
        assertTrue(!DebugLog.redact("aliyun-access-key-id=" + genericKey).contains(genericKey));
        assertTrue(!DebugLog.redact("tencent-secret-id=" + genericKey).contains(genericKey));
        // A provider-specific name that no shared rule knows about, followed by a token-like value.
        assertTrue(!DebugLog.redact("DeepL-Auth-Key " + deeplKey).contains(deeplKey));
        // A bare long base64 run is redacted even without a credential name in front of it.
        String base64 = "aGVsbG8gd29ybGQgdGhpcyBpcyBhIGxvbmdlciBzZWNyZXQ=";
        assertTrue(!DebugLog.redact("payload " + base64).contains(base64));
        // Endpoints are excluded from the log and the bundle, so a URL never survives.
        assertTrue(!DebugLog.redact("https://api.example.com/v1/chat/completions")
                .contains("api.example.com"));
        // Ordinary text stays readable, otherwise the log would be useless.
        assertEquals("Hello world", DebugLog.redact("Hello world"));
        assertTrue(DebugLog.redact("the token expired").contains("expired"));
        // Only the host of an endpoint is kept, never the path or a query.
        assertEquals("api-free.deepl.com",
                DebugLog.hostOnly("https://api-free.deepl.com/v2/translate?x=1"));
        assertEquals("host:5000", DebugLog.hostOnly("http://user:secret@host:5000/translate"));
        assertEquals("", DebugLog.hostOnly(null));
        // The preview is flattened and bounded.
        assertEquals("a b", DebugLog.preview("a\nb"));
        String longText = new String(new char[400]).replace('\0', 'x');
        assertCount(DebugLog.MAXIMUM_PREVIEW_CHARS + 3, DebugLog.preview(longText).length());
    }

    /** The trace is only written when it is on, and it rotates instead of growing without bound. */
    private static void writesAndRotatesTheDebugLog() throws Exception {
        Path directory = Files.createTempDirectory("universal-translator-debug");
        Path log = directory.resolve(DebugLog.FILE_NAME);
        Path rotated = directory.resolve(DebugLog.ROTATED_FILE_NAME);
        try {
            DebugLog off = DebugLog.isolated();
            off.configure(log, false);
            assertFalse(off.isEnabled());
            off.logRequest("libretranslate", "n/a", "https://host/translate", "CHAT", "hello");
            off.logFailure("libretranslate", new java.io.IOException("connection reset"));
            off.logStreamEvent("gemini:model", "delta", 3);
            assertTrue(!Files.exists(log));

            DebugLog on = DebugLog.isolated();
            on.configure(log, true);
            assertTrue(on.isEnabled());
            on.logRequest("deepl", "latency_optimized",
                    "https://api-free.deepl.com/v2/translate", "CHAT", "Hello world");
            on.logFailure("deepl",
                    new org.universaltranslator.core.net.HttpStatusException(429, "slow down"));
            on.logRetry("deepl", 1, 200L,
                    new org.universaltranslator.core.net.HttpStatusException(503, "unavailable"));
            on.logStreamEvent("gemini:model", "delta", 7);
            String content = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            assertTrue(content.contains("request"));
            assertTrue(content.contains("provider=deepl"));
            assertTrue(content.contains("host=api-free.deepl.com"));
            assertTrue(content.contains("model=latency_optimized"));
            assertTrue(content.contains("kind=CHAT"));
            assertTrue(content.contains("length=11"));
            assertTrue(content.contains("preview=Hello world"));
            assertTrue(content.contains("reason=rate-limit"));
            assertTrue(content.contains("reason=server"));
            assertTrue(content.contains("delay-ms=200"));
            assertTrue(content.contains("event=delta"));
            assertTrue(content.contains("characters=7"));
            // The endpoint's path never reaches the log.
            assertTrue(!content.contains("/v2/translate"));

            // Once the file passes the limit it is moved aside and a fresh one is started, so a long
            // session keeps exactly one rotated file.
            Files.write(log, new byte[(int) DebugLog.MAXIMUM_BYTES + 1]);
            on.log("after-rotation");
            assertTrue(Files.exists(rotated));
            String after = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
            assertTrue(after.contains("after-rotation"));
            assertTrue(!after.contains("preview=Hello world"));
            assertCount(DebugLog.MAXIMUM_BYTES + 1, Files.size(rotated));
        } finally {
            Files.deleteIfExists(log);
            Files.deleteIfExists(rotated);
            Files.deleteIfExists(directory);
        }
    }

    /** The exported bundle holds the four entries and no credential or endpoint. */
    private static void exportsADiagnosticsBundle() throws Exception {
        Path directory = Files.createTempDirectory("universal-translator-bundle");
        Path configFile = directory.resolve("universal-translator.properties");
        Path logFile = directory.resolve(DebugLog.FILE_NAME);
        try {
            String genericKey = "0123456789abcdef0123456789abcdef";
            Files.write(configFile, ("llm-api-key=" + genericKey
                    + "\nllm-api-endpoint=https://api.example.com/v1\n")
                    .getBytes(StandardCharsets.UTF_8));
            Files.write(logFile, ("Authorization: Bearer sk-abcdefghijklmnopqrstuvwxyz012345\n")
                    .getBytes(StandardCharsets.UTF_8));

            Path archive = DiagnosticsBundleExporter.export(directory, configFile, logFile,
                    "fabric-1.21.x", Collections.singletonList("diagnostic line"));
            assertTrue(Files.exists(archive));
            Map<String, String> entries = readArchive(archive);
            assertEquals(Integer.valueOf(4), Integer.valueOf(entries.size()));
            for (String name : DiagnosticsBundleExporter.entryNames()) {
                assertTrue(entries.containsKey(name));
            }
            assertTrue(entries.get("environment.txt").contains("Mod version:"));
            assertTrue(entries.get("environment.txt").contains("fabric-1.21.x"));
            assertTrue(entries.get("diagnostics.txt").contains("diagnostic line"));
            assertTrue(entries.get("diagnostics.txt").contains("Statistics: requests="));
            assertTrue(entries.get("config.properties").contains("llm-api-key=[key hidden]"));
            assertTrue(!entries.get("config.properties").contains(genericKey));
            assertTrue(!entries.get("config.properties").contains("api.example.com"));
            assertTrue(!entries.get("debug.log")
                    .contains("sk-abcdefghijklmnopqrstuvwxyz012345"));

            // A missing log is reported rather than failing the export.
            Path withoutLog = DiagnosticsBundleExporter.export(directory, configFile,
                    directory.resolve("absent.log"), "fabric-1.21.x", null);
            Map<String, String> second = readArchive(withoutLog);
            assertTrue(second.get("debug.log").contains("no log was written"));
            assertTrue(second.get("diagnostics.txt").contains("Diagnostics unavailable"));
        } finally {
            File[] leftovers = directory.toFile().listFiles();
            if (leftovers != null) {
                for (File leftover : leftovers) {
                    leftover.delete();
                }
            }
            Files.deleteIfExists(directory);
        }
    }

    /** Reads a zip archive into a name to text map. */
    private static Map<String, String> readArchive(Path archive) throws Exception {
        Map<String, String> entries = new java.util.LinkedHashMap<String, String>();
        try (java.util.zip.ZipInputStream zip =
                     new java.util.zip.ZipInputStream(Files.newInputStream(archive))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[1024];
                int read = zip.read(chunk);
                while (read > 0) {
                    buffer.write(chunk, 0, read);
                    read = zip.read(chunk);
                }
                entries.put(entry.getName(),
                        new String(buffer.toByteArray(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private static void assertCount(long expected, long actual) {
        if (expected != actual) {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertTrue(boolean value) {
        if (!value) {
            throw new AssertionError("Expected true");
        }
    }

    private static void assertFalse(boolean value) {
        if (value) {
            throw new AssertionError("Expected false");
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertThrows(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Exception expected) {
            return;
        }
        throw new AssertionError("Expected an exception");
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
