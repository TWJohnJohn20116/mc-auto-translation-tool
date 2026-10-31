package org.universaltranslator.core.provider;

import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.TranslationOutputValidator;
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

/**
 * DeepL's translation API.
 *
 * <p>DeepL is a dedicated machine-translation service rather than a chat model: one form-encoded
 * POST carries the text, the target language and the optional source language, and the credential
 * travels in an {@code Authorization: DeepL-Auth-Key ...} header. There is no system prompt and no
 * incremental form, so the inherited {@code translateStreaming} — one callback carrying the finished
 * text — is exactly the right behaviour, and the quality mode has no effect here.
 */
public final class DeepLTranslationProvider implements TranslationProvider {
    /** Free-tier endpoint. A Pro key uses {@code https://api.deepl.com/v2/translate} instead. */
    public static final String DEFAULT_ENDPOINT = "https://api-free.deepl.com/v2/translate";
    /** The probe only has to prove the key and the endpoint work, so it translates one word. */
    private static final String PROBE_TEXT = "ping";
    private static final String PROBE_TARGET = "EN-US";

    private final URI endpoint;
    private final String apiKey;
    /** DeepL's {@code model_type}: {@code latency_optimized} or {@code quality_optimized}. */
    private final String modelType;
    private final HttpJsonClient http;

    public DeepLTranslationProvider(String endpoint, String apiKey) {
        this(endpoint, apiKey, "", new HttpJsonClient(5000, 15000));
    }

    DeepLTranslationProvider(String endpoint, String apiKey, String modelType, HttpJsonClient http) {
        this.endpoint = EndpointPolicy.requireSafeEndpoint(
                endpoint == null || endpoint.trim().isEmpty() ? DEFAULT_ENDPOINT : endpoint);
        this.apiKey = ProviderSupport.requireCredential("DeepL API key", apiKey);
        this.modelType = requireModelType(modelType);
        this.http = http;
    }

    /**
     * Validates {@link #modelType} while the setting is being applied.
     *
     * <p>The field only accepts two values; a typo used to reach DeepL on every single line and come
     * back as {@code 400 model_type not supported}, which the retry policy rightly refuses to repeat,
     * so the whole provider was unusable with the vendor's one-line reply as the only clue.
     *
     * @param modelType configured value, empty for "do not send the field"
     * @return the value to send, or an empty string
     */
    private static String requireModelType(String modelType) {
        String value = ProviderSupport.optional(modelType);
        if (value.isEmpty() || "latency_optimized".equals(value) || "quality_optimized".equals(value)) {
            return value;
        }
        throw new IllegalArgumentException(
                "DeepL model_type must be latency_optimized or quality_optimized, not " + value);
    }

    /**
     * The endpoint is part of the id because the free and the Pro tier are different hosts, and the
     * coordinator's cache key is built from this value.
     */
    @Override
    public String id() {
        return "deepl:" + endpoint.getHost() + endpoint.getPath();
    }

    /**
     * DeepL answers a form document rather than a chat completion, so it has no way to be asked for
     * a JSON object. It is also the one provider that never batches, so nothing is lost.
     */
    public static JsonOutputMode jsonOutputMode() {
        return JsonOutputMode.NONE;
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("text", request.getText());
        fields.put("target_lang", ProviderLanguageCodes.deeplTarget(request.getTargetLanguage()));
        String source = ProviderLanguageCodes.deeplSource(request.getSourceLanguage());
        if (!source.isEmpty()) {
            fields.put("source_lang", source);
        }
        if (!modelType.isEmpty()) {
            fields.put("model_type", modelType);
        }
        DebugLog.global().logRequest(id(), modelType, endpoint.toString(),
                request.getKind().name(), request.getText());
        String response = post(fields);
        String translated = ProviderErrors.pathOrNull("DeepL", response, "translations[0].text");
        if (translated == null || translated.trim().isEmpty()) {
            throw new IllegalStateException(
                    ProviderJson.describeMissingContent("DeepL", response));
        }
        DebugLog.global().logResponse(id(), request.getKind().name(), translated);
        return TranslationOutputValidator.requireValid(request.getText(), translated);
    }

    /**
     * Sends one one-word translation to prove the credential and the endpoint.
     *
     * @return the raw response body
     * @throws Exception when the key, the endpoint or the response is unusable
     */
    public String probe() throws Exception {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        fields.put("text", PROBE_TEXT);
        fields.put("target_lang", PROBE_TARGET);
        String response = post(fields);
        if (JsonStrings.readStringPath(response, "translations[0].text") == null) {
            throw new IllegalStateException(
                    ProviderJson.describeMissingContent("DeepL", response));
        }
        return response;
    }

    private String post(Map<String, String> fields) throws Exception {
        // DeepL requires the credential in Authorization with its own scheme, and reads the request
        // as a form rather than as JSON.
        return http.postForm(endpoint, CryptoSupport.formEncode(fields),
                Collections.singletonMap("Authorization", "DeepL-Auth-Key " + apiKey));
    }
}
