package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationProviderCatalog;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.core.net.CryptoSupport;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.JsonStrings;
import org.universaltranslator.core.net.TranslationEndpointUnavailableException;
import org.universaltranslator.core.net.VolcengineV4Signer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ConnectException;
import java.net.URI;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free provider/config contract checks with no live credentials or network traffic. */
public final class ProviderSelfTest {
    public static void main(String[] args) throws Exception {
        parsesNestedProviderResponses();
        computesKnownHashes();
        truncatesYoudaoSignaturesByCodePoint();
        expandsCustomTemplatesSafely();
        rejectsUnsafeCustomHeaders();
        rejectsUnsignedProviderPaths();
        sendsCustomRequestsAndRetriesTransientFailures();
        reportsRefusedLocalServicesWithoutRetrying();
        createsEveryConfiguredProviderWithoutNetworkTraffic();
        cyclesTheCompleteProviderCatalog();
        doesNotLeakVolcengineSecretsIntoHeaders();
        System.out.println("ProviderSelfTest: all checks passed");
    }

    private static void parsesNestedProviderResponses() {
        String json = "{\"choices\":[{\"message\":{\"content\":\"Hello \\\"Steve\\\"\"}}],"
                + "\"translation\":[\"你好\"],\"data\":{\"dst\":\"完成\"}}";
        assertEquals("Hello \"Steve\"", JsonStrings.readStringPath(
                json, "choices[0].message.content"));
        assertEquals("你好", JsonStrings.readStringPath(json, "$.translation[0]"));
        assertEquals("完成", JsonStrings.readStringPath(json, "data.dst"));
        assertEquals(null, JsonStrings.readStringPath(json, "choices[1].message.content"));
    }

    private static void computesKnownHashes() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", CryptoSupport.md5Hex("hello"));
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                CryptoSupport.sha256Hex("hello"));
        assertEquals("XUFAKrxLKna5cZ2REBfFkg==", CryptoSupport.md5Base64("hello"));
    }

    private static void truncatesYoudaoSignaturesByCodePoint() {
        assertEquals("short", YoudaoTranslationProvider.signatureInput("short"));
        String longText = "0123456789ABCDEFGHIJKLMNO";
        assertEquals("012345678925FGHIJKLMNO", YoudaoTranslationProvider.signatureInput(longText));
        String emoji = "😀😀😀😀😀😀😀😀😀😀abcdefghijK";
        assertEquals("😀😀😀😀😀😀😀😀😀😀21bcdefghijK", YoudaoTranslationProvider.signatureInput(emoji));
    }

    private static void expandsCustomTemplatesSafely() {
        String expanded = CustomHttpJsonTranslationProvider.expand(
                "{\"q\":${textJson},\"from\":${sourceJson},\"to\":${targetJson},\"key\":${apiKeyJson}}",
                "Hello \"Alex\"", "auto", "zh", "secret");
        assertEquals("{\"q\":\"Hello \\\"Alex\\\"\",\"from\":\"auto\",\"to\":\"zh\",\"key\":\"secret\"}",
                expanded);
        assertEquals("{\"q\":\"Keep ${apiKey} literal\",\"key\":\"secret\"}",
                CustomHttpJsonTranslationProvider.expand(
                        "{\"q\":${textJson},\"key\":${apiKeyJson}}",
                        "Keep ${apiKey} literal", "auto", "zh", "secret"));
    }

    private static void rejectsUnsafeCustomHeaders() {
        assertThrows(() -> new CustomHttpJsonTranslationProvider(
                "https://example.com/translate", "POST", "application/json",
                "{\"q\":${textJson}}", "translatedText",
                Collections.singletonMap("Host", "attacker.example"), "", new HttpJsonClient(1000, 1000)));
        assertThrows(() -> new CustomHttpJsonTranslationProvider(
                "https://example.com/translate", "POST", "application/json",
                "{\"q\":${textJson}}", "translatedText",
                Collections.singletonMap("X-Test", "ok\r\nInjected: yes"), "", new HttpJsonClient(1000, 1000)));
    }

    private static void rejectsUnsignedProviderPaths() {
        assertThrows(() -> new VolcengineMachineTranslationProvider(
                "https://translate.volcengineapi.com/not-root", "id", "secret", "cn-north-1",
                new HttpJsonClient(1000, 1000)));
        assertThrows(() -> new TencentTmtProvider(
                "https://tmt.tencentcloudapi.com/not-root", "id", "secret",
                new HttpJsonClient(1000, 1000)));
        assertThrows(() -> new AliyunMachineTranslationProvider(
                "https://mt.cn-hangzhou.aliyuncs.com/api/translate/web/general?unsigned=yes",
                "id", "secret", new HttpJsonClient(1000, 1000)));
        assertThrows(() -> new IflytekNiuTransProvider(
                "https://ntrans.xfyun.cn/v2/ots?unsigned=yes", "app", "key", "secret",
                new HttpJsonClient(1000, 1000)));
        assertThrows(() -> new HuaweiCloudTranslationProvider(
                "https://nlp-ext.cn-north-4.myhuaweicloud.com/unexpected", "project", "token",
                new HttpJsonClient(1000, 1000)));
    }

    private static void sendsCustomRequestsAndRetriesTransientFailures() throws Exception {
        ServerSocket server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"));
        server.setSoTimeout(5000);
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<String>();
        AtomicReference<String> authorization = new AtomicReference<String>();
        AtomicReference<Throwable> serverFailure = new AtomicReference<Throwable>();
        Thread thread = new Thread(() -> {
            try {
                for (int index = 0; index < 2; index++) {
                    try (Socket socket = server.accept()) {
                        HttpRequest request = readRequest(socket.getInputStream());
                        attempts.incrementAndGet();
                        requestBody.set(request.body);
                        authorization.set(request.headers.get("authorization"));
                        String response = index == 0
                                ? "{\"error\":\"try later\"}"
                                : "{\"data\":{\"translation\":\"你好\"}}";
                        writeResponse(socket.getOutputStream(), index == 0 ? 429 : 200, response);
                    }
                }
            } catch (Throwable failure) {
                serverFailure.set(failure);
            }
        }, "provider-self-test-http");
        thread.setDaemon(true);
        thread.start();
        try {
            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("Authorization", "Token ${apiKey}");
            TranslationProvider custom = new CustomHttpJsonTranslationProvider(
                    "http://127.0.0.1:" + server.getLocalPort() + "/translate", "PUT",
                    "application/json", "{\"q\":${textJson},\"to\":${targetJson}}",
                    "data.translation", headers, "local-secret", new HttpJsonClient(2000, 2000));
            TranslationProvider resilient = new ResilientTranslationProvider(custom, 2, 0);
            String result = resilient.translate(new TranslationRequest(
                    "Hello \"Steve\"", "auto", "zh-CN", TextKind.CHAT));
            assertEquals("你好", result);
            assertEquals(2, attempts.get());
            assertEquals("Token local-secret", authorization.get());
            assertTrue(requestBody.get().contains("Hello \\\"Steve\\\""));
        } finally {
            server.close();
            thread.join(5000);
        }
        if (serverFailure.get() != null) {
            throw new AssertionError("Local custom API test server failed", serverFailure.get());
        }
    }

    private static void reportsRefusedLocalServicesWithoutRetrying() throws Exception {
        URI endpoint = URI.create("http://127.0.0.1:8080/v1/chat/completions?token=secret");
        TranslationEndpointUnavailableException refused =
                TranslationEndpointUnavailableException.connectionRefused(
                        endpoint, new ConnectException("Connection refused: getsockopt"));
        assertTrue(refused.getMessage().contains("本机翻译服务未启动（127.0.0.1:8080）"));
        assertFalse(refused.getMessage().contains("chat/completions"));
        assertFalse(refused.getMessage().contains("secret"));
        assertFalse(refused.getMessage().contains("getsockopt"));

        ServerSocket unusedPort = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        int port = unusedPort.getLocalPort();
        unusedPort.close();
        try {
            new HttpJsonClient(1000, 1000).post(
                    URI.create("http://127.0.0.1:" + port + "/translate?token=not-for-logs"),
                    "{}", "");
            throw new AssertionError("Expected a refused local translation connection");
        } catch (TranslationEndpointUnavailableException expected) {
            assertTrue(expected.getMessage().contains("本机翻译服务未启动（127.0.0.1:" + port + "）"));
            assertFalse(expected.getMessage().contains("not-for-logs"));
        }

        AtomicInteger attempts = new AtomicInteger();
        TranslationProvider unavailable = new TranslationProvider() {
            @Override
            public String id() {
                return "unavailable-local-test";
            }

            @Override
            public String translate(TranslationRequest request) throws Exception {
                attempts.incrementAndGet();
                throw refused;
            }
        };
        TranslationProvider resilient = new ResilientTranslationProvider(unavailable, 5, 0);
        assertThrows(() -> resilient.translate(new TranslationRequest(
                "Server restarting", "auto", "zh-CN", TextKind.CHAT)));
        assertEquals(1, attempts.get());
    }

    private static HttpRequest readRequest(InputStream input) throws IOException {
        String requestLine = readAsciiLine(input);
        assertTrue(requestLine.startsWith("PUT /translate HTTP/1."));
        Map<String, String> headers = new LinkedHashMap<String, String>();
        String line;
        while (!(line = readAsciiLine(input)).isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        int length = Integer.parseInt(headers.get("content-length"));
        byte[] body = new byte[length];
        int offset = 0;
        while (offset < body.length) {
            int count = input.read(body, offset, body.length - offset);
            if (count < 0) throw new IOException("Unexpected end of HTTP request body");
            offset += count;
        }
        return new HttpRequest(headers, new String(body, StandardCharsets.UTF_8));
    }

    private static String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) >= 0) {
            if (value == '\n') break;
            if (value != '\r') line.write(value);
        }
        if (value < 0 && line.size() == 0) throw new IOException("Unexpected end of HTTP headers");
        return new String(line.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static void writeResponse(OutputStream output, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Too Many Requests") + "\r\n"
                + "Content-Type: application/json\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\n\r\n";
        output.write(head.getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.flush();
    }

    private static final class HttpRequest {
        private final Map<String, String> headers;
        private final String body;

        private HttpRequest(Map<String, String> headers, String body) {
            this.headers = headers;
            this.body = body;
        }
    }

    private static void createsEveryConfiguredProviderWithoutNetworkTraffic() throws Exception {
        Properties values = new Properties();
        OnlineProviderConfig.applyDefaults(values);
        values.setProperty("baidu-app-id", "id");
        values.setProperty("baidu-secret", "secret");
        values.setProperty("tencent-tmt-secret-id", "id");
        values.setProperty("tencent-tmt-secret-key", "secret");
        values.setProperty("tencent-secret-id", "id");
        values.setProperty("tencent-secret-key", "secret");
        values.setProperty("aliyun-access-key-id", "id");
        values.setProperty("aliyun-access-key-secret", "secret");
        values.setProperty("youdao-app-key", "id");
        values.setProperty("youdao-secret", "secret");
        values.setProperty("volcengine-access-key", "id");
        values.setProperty("volcengine-secret-key", "secret");
        values.setProperty("iflytek-app-id", "id");
        values.setProperty("iflytek-api-key", "key");
        values.setProperty("iflytek-api-secret", "secret");
        values.setProperty("huawei-project-id", "project");
        values.setProperty("huawei-auth-token", "token");
        values.setProperty("deepseek-api-key", "key");
        values.setProperty("dashscope-api-key", "key");
        values.setProperty("volcengine-ark-api-key", "key");
        values.setProperty("volcengine-ark-model", "endpoint-model-id");
        values.setProperty("zhipu-api-key", "key");
        OnlineProviderConfig config = OnlineProviderConfig.from(values);
        String[] providers = {
                "libretranslate", "baidu", "tencent-tmt", "tencent-hunyuan", "aliyun-mt",
                "youdao", "volcengine-mt", "iflytek-niutrans", "huawei-cloud-mt", "deepseek",
                "dashscope", "volcengine-ark", "zhipu", "openai-compatible", "custom-http-json"
        };
        for (String name : providers) {
            TranslationProvider provider = config.create(name);
            assertTrue(provider.id() != null && !provider.id().isEmpty());
            if (provider instanceof AutoCloseable) {
                ((AutoCloseable) provider).close();
            }
        }
        assertThrows(() -> OnlineProviderConfig.from(new Properties()).create("baidu"));
    }

    private static void cyclesTheCompleteProviderCatalog() {
        String provider = "offline";
        int count = 0;
        do {
            assertTrue(!TranslationProviderCatalog.displayName(provider).isEmpty());
            provider = TranslationProviderCatalog.next(provider);
            count++;
        } while (!"offline".equals(provider) && count < 100);
        assertEquals(16, count);
    }

    private static void doesNotLeakVolcengineSecretsIntoHeaders() {
        Map<String, String> headers = VolcengineV4Signer.headers(
                "ACCESS", "TOP-SECRET", "translate.volcengineapi.com", "cn-north-1", "translate",
                "Action=TranslateText&Version=2020-06-01", "{}", "20260813T120000Z");
        String authorization = headers.get("Authorization");
        assertTrue(authorization.startsWith("HMAC-SHA256 Credential=ACCESS/20260813/cn-north-1/translate/request"));
        assertFalse(authorization.contains("TOP-SECRET"));
        assertEquals(64, headers.get("X-Content-Sha256").length());
    }

    private static void assertTrue(boolean value) {
        if (!value) throw new AssertionError("Expected true");
    }

    private static void assertFalse(boolean value) {
        if (value) throw new AssertionError("Expected false");
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
