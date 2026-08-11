package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.JsonStrings;

import java.net.URI;

/** LibreTranslate-compatible provider for local or user-selected hosted instances. */
public final class LibreTranslateProvider implements TranslationProvider {
    private final URI endpoint;
    private final String apiKey;
    private final HttpJsonClient http;

    public LibreTranslateProvider(String endpoint, String apiKey) {
        this(endpoint, apiKey, new HttpJsonClient(5000, 15000));
    }

    LibreTranslateProvider(String endpoint, String apiKey, HttpJsonClient http) {
        this.endpoint = EndpointPolicy.requireSafeEndpoint(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.http = http;
    }

    @Override
    public String id() {
        return "libretranslate:" + endpoint.getScheme() + "://" + endpoint.getAuthority() + endpoint.getPath();
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        StringBuilder body = new StringBuilder(192)
                .append('{')
                .append("\"q\":").append(JsonStrings.quote(request.getText())).append(',')
                .append("\"source\":").append(JsonStrings.quote(normalizeSource(request.getSourceLanguage()))).append(',')
                .append("\"target\":").append(JsonStrings.quote(normalizeTarget(request.getTargetLanguage()))).append(',')
                .append("\"format\":\"text\"");
        if (!apiKey.isEmpty()) {
            body.append(",\"api_key\":").append(JsonStrings.quote(apiKey));
        }
        body.append('}');

        String response = http.post(endpoint, body.toString(), (String) null);
        String translated = JsonStrings.readStringField(response, "translatedText");
        if (translated == null) {
            throw new IllegalStateException("LibreTranslate response did not contain translatedText");
        }
        return translated;
    }

    private static String normalizeSource(String language) {
        return language == null || language.trim().isEmpty() ? "auto" : baseLanguage(language);
    }

    private static String normalizeTarget(String language) {
        if (language == null || language.trim().isEmpty()) {
            throw new IllegalArgumentException("Target language is required");
        }
        return baseLanguage(language);
    }

    private static String baseLanguage(String language) {
        return TargetLanguage.libreTranslateCode(language);
    }
}
