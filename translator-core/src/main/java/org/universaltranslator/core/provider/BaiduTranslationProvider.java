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

/** Baidu VIP text translation API using its documented appid/salt MD5 signature. */
public final class BaiduTranslationProvider implements TranslationProvider {
    private final URI endpoint;
    private final String appId;
    private final String secret;
    private final HttpJsonClient http;

    public BaiduTranslationProvider(String endpoint, String appId, String secret, HttpJsonClient http) {
        this.endpoint = EndpointPolicy.requireSafeEndpoint(endpoint);
        this.appId = ProviderSupport.requireCredential("Baidu app ID", appId);
        this.secret = ProviderSupport.requireCredential("Baidu secret", secret);
        this.http = http;
    }

    @Override
    public String id() {
        return "baidu";
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        ProviderSupport.requireUtf8Bytes("Baidu source text", request.getText(), 6000);
        String salt = UUID.randomUUID().toString();
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("q", request.getText());
        fields.put("from", ProviderLanguageCodes.baidu(request.getSourceLanguage(), true));
        fields.put("to", ProviderLanguageCodes.baidu(request.getTargetLanguage(), false));
        fields.put("appid", appId);
        fields.put("salt", salt);
        fields.put("sign", CryptoSupport.md5Hex(appId + request.getText() + salt + secret));
        String response = http.postForm(endpoint, CryptoSupport.formEncode(fields),
                Collections.<String, String>emptyMap());
        String translated = JsonStrings.readStringField(response, "dst");
        if (translated == null || translated.trim().isEmpty()) {
            throw providerError("Baidu", response, "error_code", "error_msg");
        }
        return translated;
    }

    static IllegalStateException providerError(
            String provider,
            String response,
            String codeField,
            String messageField
    ) {
        String code = JsonStrings.readStringField(response, codeField);
        String message = JsonStrings.readStringField(response, messageField);
        return new IllegalStateException(provider + " response did not contain translated text"
                + (code == null ? "" : " (" + code + ")")
                + (message == null ? "" : ": " + message));
    }
}
