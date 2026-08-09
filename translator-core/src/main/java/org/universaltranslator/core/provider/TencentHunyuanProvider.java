package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.JsonStrings;
import org.universaltranslator.core.net.TencentCloudV3Signer;

import java.net.URI;
import java.util.Locale;
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
    private long nextRequestAtMillis;

    public TencentHunyuanProvider(String secretId, String secretKey, String model) {
        this(secretId, secretKey, model, new HttpJsonClient(5000, 30000));
    }

    TencentHunyuanProvider(String secretId, String secretKey, String model, HttpJsonClient http) {
        this.secretId = requireCredential("Tencent SecretId", secretId);
        this.secretKey = requireCredential("Tencent SecretKey", secretKey);
        this.model = normalizeModel(model);
        this.http = http;
    }

    @Override
    public String id() {
        return "tencent-hunyuan:" + model;
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
        String translated = JsonStrings.readStringField(response, "Content");
        if (translated == null || translated.trim().isEmpty()) {
            String code = JsonStrings.readStringField(response, "Code");
            String message = JsonStrings.readStringField(response, "Message");
            throw new IllegalStateException("Tencent Hunyuan response did not contain translated content"
                    + (code == null ? "" : " (" + code + ")")
                    + (message == null ? "" : ": " + message));
        }
        return translated;
    }

    private synchronized void awaitRateLimit() throws InterruptedException {
        long now = System.currentTimeMillis();
        long delay = nextRequestAtMillis - now;
        if (delay > 0L) {
            Thread.sleep(delay);
            now = System.currentTimeMillis();
        }
        nextRequestAtMillis = now + MIN_REQUEST_INTERVAL_MILLIS;
    }

    private static String normalizeSource(String language) {
        if (language == null || language.trim().isEmpty() || "auto".equalsIgnoreCase(language.trim())) {
            return null;
        }
        return normalizeLanguage(language);
    }

    private static String normalizeTarget(String language) {
        if (language == null || language.trim().isEmpty()) {
            throw new IllegalArgumentException("Target language is required");
        }
        return normalizeLanguage(language);
    }

    private static String normalizeLanguage(String language) {
        String normalized = language.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if ("zh-cn".equals(normalized) || "zh-hans".equals(normalized)) {
            return "zh";
        }
        if ("zh-tw".equals(normalized) || "zh-hk".equals(normalized)
                || "zh-tr".equals(normalized) || "zh-hant".equals(normalized)) {
            return "zh-TR";
        }
        int separator = normalized.indexOf('-');
        return separator < 0 ? normalized : normalized.substring(0, separator);
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
