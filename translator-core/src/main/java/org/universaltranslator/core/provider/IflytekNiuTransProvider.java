package org.universaltranslator.core.provider;

import org.universaltranslator.core.TranslationProvider;
import org.universaltranslator.core.TranslationRequest;
import org.universaltranslator.core.net.CryptoSupport;
import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.JsonStrings;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** iFlytek NiuTrans 2.0 API using its host/date/request-line/digest HMAC signature. */
public final class IflytekNiuTransProvider implements TranslationProvider {
    private final URI endpoint;
    private final String appId;
    private final String apiKey;
    private final String apiSecret;
    private final HttpJsonClient http;

    public IflytekNiuTransProvider(
            String endpoint,
            String appId,
            String apiKey,
            String apiSecret,
            HttpJsonClient http
    ) {
        this.endpoint = EndpointPolicy.requireSafeEndpoint(endpoint);
        if (this.endpoint.getRawQuery() != null || this.endpoint.getFragment() != null) {
            throw new IllegalArgumentException("iFlytek endpoint must not contain a query or fragment");
        }
        this.appId = ProviderSupport.requireCredential("iFlytek app ID", appId);
        this.apiKey = ProviderSupport.requireCredential("iFlytek API key", apiKey);
        this.apiSecret = ProviderSupport.requireCredential("iFlytek API secret", apiSecret);
        this.http = http;
    }

    @Override
    public String id() {
        return "iflytek-niutrans";
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        ProviderSupport.requireUtf8Bytes("iFlytek source text", request.getText(), 15000);
        String payload = "{"
                + "\"common\":{\"app_id\":" + JsonStrings.quote(appId) + "},"
                + "\"business\":{"
                + "\"from\":" + JsonStrings.quote(
                        ProviderLanguageCodes.iflytek(request.getSourceLanguage(), true)) + ','
                + "\"to\":" + JsonStrings.quote(
                        ProviderLanguageCodes.iflytek(request.getTargetLanguage(), false)) + "},"
                + "\"data\":{\"text\":" + JsonStrings.quote(
                        CryptoSupport.base64(request.getText())) + "}}";
        String date = rfc1123(new Date());
        String digest = "SHA-256=" + CryptoSupport.base64(CryptoSupport.sha256Hex(payload));
        String path = endpoint.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String signatureOrigin = "host: " + endpoint.getHost() + "\n"
                + "date: " + date + "\n"
                + "POST " + path + " HTTP/1.1\n"
                + "digest: " + digest;
        String signature = CryptoSupport.hmacSha256Base64(apiSecret, signatureOrigin);
        String authorization = "api_key=\"" + apiKey
                + "\", algorithm=\"hmac-sha256\", headers=\"host date request-line digest\", signature=\""
                + signature + "\"";
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Date", date);
        headers.put("Digest", digest);
        headers.put("Authorization", authorization);
        String response = http.post(endpoint, payload, headers);
        String translated = JsonStrings.readStringField(response, "dst");
        if (translated == null || translated.trim().isEmpty()) {
            throw BaiduTranslationProvider.providerError(
                    "iFlytek NiuTrans", response, "code", "message");
        }
        return translated;
    }

    private static String rfc1123(Date value) {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(value);
    }
}
