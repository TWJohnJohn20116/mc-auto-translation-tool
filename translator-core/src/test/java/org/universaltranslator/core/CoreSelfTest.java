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
import java.util.Arrays;
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
        protectsExistingChineseInMixedText();
        stylesCompletedTranslations();
        validatesSmallModelOutputs();
        preservesRecentUserMessages();
        cachesDynamicTemplates();
        deduplicatesConcurrentRequests();
        completesQueuedRequestsWhenClosed();
        fallsBackToOriginalOnFailure();
        enforcesSafeEndpoints();
        handlesJsonStrings();
        updatesRenderLookupsWithoutBlocking();
        translatesRelatedTooltipLinesTogether();
        translatesOutgoingChatAsynchronously();
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
        exportsSecretFreeDiagnostics();
        reportsOfflineStartupDiagnostics();
        matchesTencentCloudOfficialSignatureVector();
        keepsOriginalTextInBilingualMode();
        fallsBackFromOfflineToApi();
        verifiesDownloadedFileHashes();
        reportsVerifiedDownloadProgress();
        extractsOfflineEngineArchivesSafely();
        normalizesOfflineModelSelections();
        supportsTraditionalChineseTargets();
        cyclesSelectableTargetLanguages();
        identifiesVanillaScreenContent();
        formatsSecretFreeDiagnostics();
        localizesDiagnosticsAndRuntimeStatus();
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
        assertEquals("https", EndpointPolicy.requireSafeEndpoint("https://translate.example/translate").getScheme());
        assertThrows(() -> EndpointPolicy.requireSafeEndpoint("http://translate.example/translate"));
        assertThrows(() -> EndpointPolicy.requireSafeEndpoint("https://user:secret@translate.example/translate"));
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
    }

    private static void exportsSecretFreeDiagnostics() throws Exception {
        Path directory = Files.createTempDirectory("universal-translator-diagnostics-");
        Path report = DiagnosticsLogExporter.export(directory, java.util.Arrays.asList(
                "Runtime: failed https://secret.example/translate",
                "api-key=abc123",
                "authorization: Bearer private-token"));
        String output = new String(Files.readAllBytes(report), StandardCharsets.UTF_8);
        assertTrue(output.contains("MC Auto Translation Tool - Diagnostics"));
        assertFalse(output.contains("secret.example"));
        assertFalse(output.contains("abc123"));
        assertFalse(output.contains("private-token"));
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
        assertTrue(conservative.contains("--no-mmap"));
    }

    private static void protectsDynamicScoreboardValues() {
        ProtectedText text = ProtectedText.parse("\u00a7aCoins: 12,583 | https://example.org | 75%");
        assertEquals("__UT_0__Coins: __UT_1__ | __UT_2__ | __UT_3__", text.getTemplate());
        assertEquals("\u00a7a\u91d1\u5e01: 12,583 | https://example.org | 75%", text.restore("__UT_0__\u91d1\u5e01: __UT_1__ | __UT_2__ | __UT_3__"));
    }

    private static void skipsAlreadyChineseAndNonTextValues() {
        assertFalse(LanguageHeuristics.shouldTranslate("\u91d1\u5e01\uff1a123", "zh-CN"));
        assertFalse(LanguageHeuristics.shouldTranslate("123 / 456", "zh-CN"));
        assertTrue(LanguageHeuristics.shouldTranslate("Coins: 123", "zh-CN"));
        assertTrue(LanguageHeuristics.shouldTranslate("欢迎 VIP", "zh-CN"));
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
