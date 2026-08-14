package org.universaltranslator.core.provider;

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
        ProviderSupport.requireUtf8Bytes("Tencent TMT source text", request.getText(), 2000);
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
        String translated = JsonStrings.readStringField(response, "TargetText");
        if (translated == null || translated.trim().isEmpty()) {
            throw BaiduTranslationProvider.providerError(
                    "Tencent TMT", response, "Code", "Message");
        }
        return translated;
    }
}
