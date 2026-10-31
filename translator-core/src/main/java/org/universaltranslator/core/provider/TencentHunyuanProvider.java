package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationOutputValidator;
import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.JsonStrings;
import org.universaltranslator.core.net.TencentCloudV3Signer;

import java.net.URI;
import java.util.Map;

/** Tencent Hunyuan ChatTranslations provider using the maintained API 3.0 endpoint. */
public final class TencentHunyuanProvider implements TranslationProvider {
    private static final URI ENDPOINT = URI.create("https://hunyuan.tencentcloudapi.com/");
    private static final String HOST = "hunyuan.tencentcloudapi.com";
    private static final String SERVICE = "hunyuan";
    private static final String ACTION = "ChatTranslations";
    private static final String VERSION = "2023-09-01";
    private static final long MIN_REQUEST_INTERVAL_MILLIS = 60L;

    private final String secretId;
    private final String secretKey;
    private final String model;
    private final HttpJsonClient http;
    /** The value {@link #id()} reports; see {@link ProviderCacheKey}. */
    private final String id;
    /**
     * One request per interval for this provider's own burst protection.
     *
     * <p>It is <em>not</em> the gate {@link ResilientTranslationProvider} holds: that one is a
     * separate instance built from {@code api-min-request-interval-ms}, so when both apply the
     * effective interval is the sum, not the larger of the two.
     */
    private final RequestGate gate = new RequestGate(MIN_REQUEST_INTERVAL_MILLIS);

    public TencentHunyuanProvider(String secretId, String secretKey, String model) {
        this(secretId, secretKey, model, new HttpJsonClient(5000, 30000));
    }

    TencentHunyuanProvider(String secretId, String secretKey, String model, HttpJsonClient http) {
        this.secretId = requireCredential("Tencent SecretId", secretId);
        this.secretKey = requireCredential("Tencent SecretKey", secretKey);
        this.model = normalizeModel(model);
        this.http = http;
        // This provider talks to one fixed vendor endpoint, so its identity is the endpoint plus the
        // signed action and version. They are constants today, but building the key the same way as
        // every other provider means a future configurable host cannot silently reuse cache entries.
        // The per-request signature headers are deliberately not part of it: they change on every
        // call, and a cache key that changes per request caches nothing.
        this.id = ProviderCacheKey.of("tencent-hunyuan", this.model, ENDPOINT,
                "service=" + SERVICE + ";action=" + ACTION + ";version=" + VERSION, "");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        awaitRateLimit();
        String source = normalizeSource(request.getSourceLanguage());
        String target = normalizeTarget(request.getTargetLanguage());
        StringBuilder body = new StringBuilder(request.getText().length() + 160)
                .append('{')
                .append("\"Model\":").append(JsonStrings.quote(model)).append(',')
                .append("\"Stream\":false,")
                .append("\"Text\":").append(JsonStrings.quote(request.getText())).append(',');
        if (source != null) {
            body.append("\"Source\":").append(JsonStrings.quote(source)).append(',');
        }
        body.append("\"Target\":").append(JsonStrings.quote(target)).append(',')
                .append("\"Field\":\"游戏界面\"")
                .append('}');

        String payload = body.toString();
        long timestamp = System.currentTimeMillis() / 1000L;
        Map<String, String> headers = TencentCloudV3Signer.headers(
                SERVICE, HOST, ACTION, VERSION, secretId, secretKey, payload, timestamp);
        String response = http.post(ENDPOINT, payload, headers);
        // The reply is wrapped in "Response", so the path names it; a relay that answers without the
        // wrapper still carries the field, so the bare path is the second read.
        String translated = ProviderErrors.pathOrNull(
                "Tencent Hunyuan", response, "Response.Content");
        if (translated == null || translated.trim().isEmpty()) {
            translated = ProviderErrors.pathOrNull("Tencent Hunyuan", response, "Content");
        }
        if (translated == null || translated.trim().isEmpty()) {
            // The shared builder, not a hand-rolled message: it is the one place that knows which
            // codes are rate limits (so the request is retried), which are fatal (so the endpoint is
            // paused) and what the reader can do about them.
            throw BaiduTranslationProvider.providerError(
                    "Tencent Hunyuan", response, "Code", "Message");
        }
        return TranslationOutputValidator.requireValid(request.getText(), translated);
    }

    private void awaitRateLimit() throws InterruptedException {
        gate.await();
    }

    private static String normalizeSource(String language) {
        if (language == null || language.trim().isEmpty() || TargetLanguage.isAuto(language)) {
            return null;
        }
        return ProviderLanguageCodes.hunyuan(language, true);
    }

    private static String normalizeTarget(String language) {
        if (language == null || language.trim().isEmpty()) {
            throw new IllegalArgumentException("Target language is required");
        }
        return ProviderLanguageCodes.hunyuan(language, false);
    }

    private static String normalizeModel(String model) {
        String value = model == null || model.trim().isEmpty()
                ? "hunyuan-translation-lite" : model.trim();
        if (!"hunyuan-translation-lite".equals(value) && !"hunyuan-translation".equals(value)) {
            throw new IllegalArgumentException("Unsupported Tencent Hunyuan translation model: " + value);
        }
        return value;
    }

    private static String requireCredential(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required; add it only to the local config file");
        }
        return value.trim();
    }
}
