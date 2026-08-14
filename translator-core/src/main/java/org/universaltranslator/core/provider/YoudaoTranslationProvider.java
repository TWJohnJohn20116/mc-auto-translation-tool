package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.net.CryptoSupport;
import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.JsonStrings;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Youdao text translation v3 API with SHA-256 application signature. */
public final class YoudaoTranslationProvider implements TranslationProvider {
    private final URI endpoint;
    private final String appKey;
    private final String secret;
    private final String vocabularyId;
    private final HttpJsonClient http;

    public YoudaoTranslationProvider(
            String endpoint,
            String appKey,
            String secret,
            String vocabularyId,
            HttpJsonClient http
    ) {
        this.endpoint = EndpointPolicy.requireSafeEndpoint(endpoint);
        this.appKey = ProviderSupport.requireCredential("Youdao app key", appKey);
        this.secret = ProviderSupport.requireCredential("Youdao secret", secret);
        this.vocabularyId = ProviderSupport.optional(vocabularyId);
        this.http = http;
    }

    @Override
    public String id() {
        return "youdao";
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        String salt = UUID.randomUUID().toString();
        String currentTime = Long.toString(System.currentTimeMillis() / 1000L);
        String input = signatureInput(request.getText());
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("q", request.getText());
        fields.put("from", ProviderLanguageCodes.youdao(request.getSourceLanguage(), true));
        fields.put("to", ProviderLanguageCodes.youdao(request.getTargetLanguage(), false));
        fields.put("appKey", appKey);
        fields.put("salt", salt);
        fields.put("sign", CryptoSupport.sha256Hex(appKey + input + salt + currentTime + secret));
        fields.put("signType", "v3");
        fields.put("curtime", currentTime);
        if (!vocabularyId.isEmpty()) {
            fields.put("vocabId", vocabularyId);
        }
        String response = http.postForm(endpoint, CryptoSupport.formEncode(fields),
                Collections.<String, String>emptyMap());
        String translated = JsonStrings.readStringPath(response, "translation[0]");
        if (translated == null || translated.trim().isEmpty()) {
            throw BaiduTranslationProvider.providerError(
                    "Youdao", response, "errorCode", "msg");
        }
        return translated;
    }

    static String signatureInput(String text) {
        int length = text.codePointCount(0, text.length());
        if (length <= 20) {
            return text;
        }
        int firstEnd = text.offsetByCodePoints(0, 10);
        int lastStart = text.offsetByCodePoints(0, length - 10);
        return text.substring(0, firstEnd) + length + text.substring(lastStart);
    }
}
