package org.universaltranslator.core.provider;

import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.TranslationOutputValidator;
import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.JsonStrings;
import org.universaltranslator.core.net.TencentCloudV3Signer;

import java.net.URI;
import java.util.Map;

/** Tencent Cloud TMT TextTranslate API with TC3-HMAC-SHA256 authentication. */
public final class TencentTmtProvider implements TranslationProvider {
    private static final String SERVICE = "tmt";
    private static final String ACTION = "TextTranslate";
    private static final String VERSION = "2018-03-21";

    private final URI endpoint;
    private final String host;
    private final String secretId;
    private final String secretKey;
    private final HttpJsonClient http;

    public TencentTmtProvider(
            String endpoint,
            String secretId,
            String secretKey,
            HttpJsonClient http
    ) {
        this.endpoint = EndpointPolicy.requireSafeEndpoint(endpoint);
        String path = this.endpoint.getRawPath();
        if ((path != null && !path.isEmpty() && !"/".equals(path))
                || this.endpoint.getRawQuery() != null || this.endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Tencent TMT endpoint must use the root path without a query or fragment");
        }
        this.host = this.endpoint.getHost();
        this.secretId = ProviderSupport.requireCredential("Tencent TMT SecretId", secretId);
        this.secretKey = ProviderSupport.requireCredential("Tencent TMT SecretKey", secretKey);
        this.http = http;
    }

    @Override
    public String id() {
        return "tencent-tmt";
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        // Characters, not UTF-8 bytes: TMT documents a 2000-character limit, and every other provider
        // in this package checks characters (Aliyun 5000, Huawei 2000, Youdao 5000). Counting bytes
        // made a Chinese source hit the ceiling at ~666 characters — a third of the documented
        // limit — and rejected the request locally with a message the user could not act on.
        // Baidu keeps its byte check because its documented unit really is 6000 bytes.
        if (request.getText().length() > 2000) {
            throw new IllegalArgumentException("Tencent TMT source text exceeds 2000 characters");
        }
        String payload = "{"
                + "\"SourceText\":" + JsonStrings.quote(request.getText()) + ','
                + "\"Source\":" + JsonStrings.quote(
                        ProviderLanguageCodes.tencent(request.getSourceLanguage(), true)) + ','
                + "\"Target\":" + JsonStrings.quote(
                        ProviderLanguageCodes.tencent(request.getTargetLanguage(), false)) + ','
                + "\"ProjectId\":0}";
        Map<String, String> headers = TencentCloudV3Signer.headers(
                SERVICE, host, ACTION, VERSION, secretId, secretKey,
                payload, System.currentTimeMillis() / 1000L);
        String response = http.post(endpoint, payload, headers);
        // The error object is read first. "TargetText is absent" has two very different meanings —
        // the request failed, or there was nothing to translate — and only Response.Error separates
        // them; the shared fault also hid which of the two had happened.
        if (hasResponseError(response)) {
            throw BaiduTranslationProvider.providerError(
                    "Tencent TMT", response, "Code", "Message");
        }
        String translated = ProviderErrors.pathOrNull(
                "Tencent TMT", response, "Response.TargetText");
        if (translated == null || translated.trim().isEmpty()) {
            // The reply is wrapped in "Response"; a relay that answers without the wrapper still
            // carries the field, so the bare path is the second read.
            translated = ProviderErrors.pathOrNull("Tencent TMT", response, "TargetText");
        }
        if (translated == null || translated.trim().isEmpty()) {
            if (hasTranslatableContent(request.getText())) {
                // No error object and no text for a text that has something to translate: the engine
                // answered nothing. Returning the source here would write "the source is the
                // translation" into the persistent cache, where it would be replayed for the rest of
                // the installation without a single request being made.
                throw BaiduTranslationProvider.providerError(
                        "Tencent TMT", response, "Code", "Message");
            }
            // A source of pure symbols or spaces has nothing to translate, so the source really is
            // the answer. The trace records it, because "returned the source" has to stay visible
            // instead of looking like a translation.
            DebugLog.global().logStreamEvent("tencent-tmt", "tmt-empty", request.getText().length());
            return request.getText();
        }
        return TranslationOutputValidator.requireValid(request.getText(), translated);
    }

    /**
     * Whether a text carries anything a translation engine could change.
     *
     * <p>The line between "nothing to translate" and "the engine answered nothing" is exactly
     * whether the source has a letter or a digit in it; punctuation, formatting markers and spaces
     * are the same in every language.
     */
    private static boolean hasTranslatableContent(String text) {
        for (int index = 0; index < text.length(); index++) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint) - 1;
            if (Character.isLetterOrDigit(codePoint)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the reply carries {@code Response.Error}. */
    private static boolean hasResponseError(String response) {
        Object root = JsonTree.parseOrNull(response);
        if (!(root instanceof Map)) {
            return false;
        }
        Object inner = ((Map<?, ?>) root).get("Response");
        return inner instanceof Map && ((Map<?, ?>) inner).get("Error") != null;
    }
}
