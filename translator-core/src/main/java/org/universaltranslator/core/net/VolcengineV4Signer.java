package org.universaltranslator.core.net;

import java.util.LinkedHashMap;
import java.util.Map;

/** Fire-and-forget Volcengine HMAC-SHA256 OpenAPI header signer. */
public final class VolcengineV4Signer {
    private static final String ALGORITHM = "HMAC-SHA256";
    private static final String SIGNED_HEADERS = "content-type;host;x-content-sha256;x-date";

    private VolcengineV4Signer() {
    }

    public static Map<String, String> headers(
            String accessKey,
            String secretKey,
            String host,
            String region,
            String service,
            String canonicalQuery,
            String payload,
            String requestDate
    ) {
        if (requestDate == null || !requestDate.matches("\\d{8}T\\d{6}Z")) {
            throw new IllegalArgumentException("Volcengine request date must use yyyyMMdd'T'HHmmss'Z'");
        }
        String contentType = "application/json";
        String payloadHash = CryptoSupport.sha256Hex(payload);
        String canonicalHeaders = "content-type:" + contentType + "\n"
                + "host:" + host.toLowerCase(java.util.Locale.ROOT) + "\n"
                + "x-content-sha256:" + payloadHash + "\n"
                + "x-date:" + requestDate + "\n";
        String canonicalRequest = "POST\n/\n" + canonicalQuery + "\n"
                + canonicalHeaders + "\n" + SIGNED_HEADERS + "\n" + payloadHash;
        String shortDate = requestDate.substring(0, 8);
        String scope = shortDate + '/' + region + '/' + service + "/request";
        String stringToSign = ALGORITHM + "\n" + requestDate + "\n" + scope + "\n"
                + CryptoSupport.sha256Hex(canonicalRequest);
        byte[] dateKey = CryptoSupport.hmacSha256(CryptoSupport.utf8(secretKey), shortDate);
        byte[] regionKey = CryptoSupport.hmacSha256(dateKey, region);
        byte[] serviceKey = CryptoSupport.hmacSha256(regionKey, service);
        byte[] signingKey = CryptoSupport.hmacSha256(serviceKey, "request");
        String signature = CryptoSupport.hmacSha256Hex(signingKey, stringToSign);
        String authorization = ALGORITHM + " Credential=" + accessKey + '/' + scope
                + ", SignedHeaders=" + SIGNED_HEADERS + ", Signature=" + signature;
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", contentType);
        headers.put("X-Content-Sha256", payloadHash);
        headers.put("X-Date", requestDate);
        headers.put("Authorization", authorization);
        return headers;
    }
}
