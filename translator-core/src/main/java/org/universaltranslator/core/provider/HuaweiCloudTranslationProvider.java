package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.JsonStrings;

import java.net.URI;
import java.util.Collections;

/** Huawei Cloud NLP text translation using a user-supplied IAM X-Auth-Token. */
public final class HuaweiCloudTranslationProvider implements TranslationProvider {
    private final URI endpoint;
    private final String authToken;
    private final HttpJsonClient http;

    public HuaweiCloudTranslationProvider(
            String baseEndpoint,
            String projectId,
            String authToken,
            HttpJsonClient http
    ) {
        URI base = EndpointPolicy.requireSafeEndpoint(baseEndpoint);
        String basePath = base.getRawPath();
        if ((basePath != null && !basePath.isEmpty() && !"/".equals(basePath))
                || base.getRawQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("Huawei Cloud base endpoint must use the root path without a query or fragment");
        }
        String project = ProviderSupport.requireCredential("Huawei Cloud project ID", projectId);
        this.endpoint = EndpointPolicy.requireSafeEndpoint(trimSlash(base.toString())
                + "/v1/" + pathSegment(project) + "/machine-translation/text-translation");
        this.authToken = ProviderSupport.requireCredential("Huawei Cloud X-Auth-Token", authToken);
        this.http = http;
    }

    @Override
    public String id() {
        return "huawei-cloud-mt";
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        if (request.getText().length() > 2000) {
            throw new IllegalArgumentException("Huawei Cloud source text exceeds 2000 characters");
        }
        String payload = "{"
                + "\"text\":" + JsonStrings.quote(request.getText()) + ','
                + "\"from\":" + JsonStrings.quote(
                        ProviderLanguageCodes.common(request.getSourceLanguage(), true)) + ','
                + "\"to\":" + JsonStrings.quote(
                        ProviderLanguageCodes.common(request.getTargetLanguage(), false)) + "}";
        String response = http.post(endpoint, payload,
                Collections.singletonMap("X-Auth-Token", authToken));
        String translated = JsonStrings.readStringField(response, "translated_text");
        if (translated == null || translated.trim().isEmpty()) {
            throw BaiduTranslationProvider.providerError(
                    "Huawei Cloud", response, "error_code", "error_msg");
        }
        return translated;
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String pathSegment(String value) {
        if (!value.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Huawei Cloud project ID contains unsupported characters");
        }
        return value;
    }
}
