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
import java.util.UUID;

/** Alibaba Cloud Machine Translation general REST edition with ROA HMAC-SHA1 signing. */
public final class AliyunMachineTranslationProvider implements TranslationProvider {
    private static final String ACCEPT = "application/json";
    private static final String CONTENT_TYPE = "application/json;charset=utf-8";
    private static final String API_VERSION = "2019-01-02";

    private final URI endpoint;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final HttpJsonClient http;

    public AliyunMachineTranslationProvider(
            String endpoint,
            String accessKeyId,
            String accessKeySecret,
            HttpJsonClient http
    ) {
        this.endpoint = EndpointPolicy.requireSafeEndpoint(endpoint);
        if (this.endpoint.getRawQuery() != null || this.endpoint.getFragment() != null) {
            throw new IllegalArgumentException("Alibaba Cloud MT endpoint must not contain a query or fragment");
        }
        this.accessKeyId = ProviderSupport.requireCredential("Alibaba Cloud AccessKey ID", accessKeyId);
        this.accessKeySecret = ProviderSupport.requireCredential(
                "Alibaba Cloud AccessKey secret", accessKeySecret);
        this.http = http;
    }

    @Override
    public String id() {
        return "aliyun-mt";
    }

    @Override
    public String translate(TranslationRequest request) throws Exception {
        if (request.getText().length() > 5000) {
            throw new IllegalArgumentException("Alibaba Cloud source text exceeds 5000 characters");
        }
        String payload = "{"
                + "\"FormatType\":\"text\","
                + "\"SourceLanguage\":" + JsonStrings.quote(
                        ProviderLanguageCodes.aliyun(request.getSourceLanguage(), true)) + ','
                + "\"TargetLanguage\":" + JsonStrings.quote(
                        ProviderLanguageCodes.aliyun(request.getTargetLanguage(), false)) + ','
                + "\"SourceText\":" + JsonStrings.quote(request.getText()) + ','
                + "\"Scene\":\"general\"}";
        String date = rfc1123(new Date());
        String nonce = UUID.randomUUID().toString();
        String contentMd5 = CryptoSupport.md5Base64(payload);
        String path = endpoint.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String stringToSign = "POST\n" + ACCEPT + "\n" + contentMd5 + "\n"
                + CONTENT_TYPE + "\n" + date + "\n"
                + "x-acs-signature-method:HMAC-SHA1\n"
                + "x-acs-signature-nonce:" + nonce + "\n"
                + "x-acs-version:" + API_VERSION + "\n"
                + path;
        String authorization = "acs " + accessKeyId + ':'
                + CryptoSupport.hmacSha1Base64(accessKeySecret, stringToSign);
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Accept", ACCEPT);
        headers.put("Content-Type", CONTENT_TYPE);
        headers.put("Content-MD5", contentMd5);
        headers.put("Date", date);
        headers.put("Authorization", authorization);
        headers.put("x-acs-signature-nonce", nonce);
        headers.put("x-acs-signature-method", "HMAC-SHA1");
        headers.put("x-acs-version", API_VERSION);
        String response = http.post(endpoint, payload, headers);
        String translated = JsonStrings.readStringPath(response, "Data.Translated");
        if (translated == null || translated.trim().isEmpty()) {
            throw BaiduTranslationProvider.providerError(
                    "Alibaba Cloud MT", response, "Code", "Message");
        }
        return translated;
    }

    private static String rfc1123(Date value) {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(value);
    }
}
