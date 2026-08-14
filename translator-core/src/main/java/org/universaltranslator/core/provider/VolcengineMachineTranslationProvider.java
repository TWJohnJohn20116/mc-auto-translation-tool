package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.JsonStrings;
import org.universaltranslator.core.net.VolcengineV4Signer;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Volcengine TranslateText API using the documented OpenAPI HMAC-SHA256 signature. */
public final class VolcengineMachineTranslationProvider implements TranslationProvider {
    private static final String QUERY = "Action=TranslateText&Version=2020-06-01";
    private static final String SERVICE = "translate";

    private final URI endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String region;
    private final HttpJsonClient http;

    public VolcengineMachineTranslationProvider(
            String endpoint,
            String accessKey,
            String secretKey,
            String region,
            HttpJsonClient http
    ) {
        URI base = EndpointPolicy.requireSafeEndpoint(endpoint);
        if (base.getRawQuery() != null || base.getFragment() != null) {
            throw new IllegalArgumentException("Volcengine endpoint must not contain a query or fragment");
        }
        String path = base.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            throw new IllegalArgumentException("Volcengine endpoint path must be /");
        }
        String baseText = base.toString();
        this.endpoint = EndpointPolicy.requireSafeEndpoint(baseText + '?' + QUERY);
        this.accessKey = ProviderSupport.requireCredential("Volcengine AccessKey", accessKey);
        this.secretKey = ProviderSupport.requireCredential("Volcengine SecretKey", secretKey);
        this.region = ProviderSupport.requireCredential("Volcengine region", region);
        this.http = http;
    }

    @Override
    public String id() {
        return "volcengine-mt";
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        String payload = "{"
                + "\"SourceLanguage\":" + JsonStrings.quote(
                        ProviderLanguageCodes.volcengine(request.getSourceLanguage(), true)) + ','
                + "\"TargetLanguage\":" + JsonStrings.quote(
                        ProviderLanguageCodes.volcengine(request.getTargetLanguage(), false)) + ','
                + "\"TextList\":[" + JsonStrings.quote(request.getText()) + "]}";
        String requestDate = requestDate(new Date());
        Map<String, String> headers = VolcengineV4Signer.headers(
                accessKey, secretKey, endpoint.getHost(), region, SERVICE, QUERY, payload, requestDate);
        String response = http.post(endpoint, payload, headers);
        String translated = JsonStrings.readStringPath(response, "TranslationList[0].Translation");
        if (translated == null || translated.trim().isEmpty()) {
            throw BaiduTranslationProvider.providerError(
                    "Volcengine MT", response, "Code", "Message");
        }
        return translated;
    }

    private static String requestDate(Date value) {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(value);
    }
}
