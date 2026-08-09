package org.universaltranslator.core.net;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/** Dependency-free implementation of Tencent Cloud API 3.0 TC3-HMAC-SHA256 signing. */
public final class TencentCloudV3Signer {
    private static final String ALGORITHM = "TC3-HMAC-SHA256";
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";

    private TencentCloudV3Signer() {
    }

    public static Map<String, String> headers(
            String service,
            String host,
            String action,
            String version,
            String secretId,
            String secretKey,
            String payload,
            long timestampSeconds
    ) throws Exception {
        requireValue("service", service);
        requireValue("host", host);
        requireValue("action", action);
        requireValue("version", version);
        requireValue("SecretId", secretId);
        requireValue("SecretKey", secretKey);

        String signedHeaders = "content-type;host;x-tc-action";
        String canonicalHeaders = "content-type:" + CONTENT_TYPE + "\n"
                + "host:" + host.toLowerCase(Locale.ROOT) + "\n"
                + "x-tc-action:" + action.toLowerCase(Locale.ROOT) + "\n";
        String canonicalRequest = "POST\n/\n\n"
                + canonicalHeaders + "\n"
                + signedHeaders + "\n"
                + sha256Hex(payload);

        String date = utcDate(timestampSeconds);
        String credentialScope = date + "/" + service + "/tc3_request";
        String stringToSign = ALGORITHM + "\n"
                + timestampSeconds + "\n"
                + credentialScope + "\n"
                + sha256Hex(canonicalRequest);

        byte[] secretDate = hmac(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] secretService = hmac(secretDate, service);
        byte[] secretSigning = hmac(secretService, "tc3_request");
        String signature = hex(hmac(secretSigning, stringToSign));
        String authorization = ALGORITHM
                + " Credential=" + secretId + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Content-Type", CONTENT_TYPE);
        headers.put("Authorization", authorization);
        headers.put("X-TC-Action", action);
        headers.put("X-TC-Timestamp", Long.toString(timestampSeconds));
        headers.put("X-TC-Version", version);
        return headers;
    }

    static String sha256Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String utcDate(long timestampSeconds) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(timestampSeconds * 1000L));
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] output = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            output[index * 2] = digits[value >>> 4];
            output[index * 2 + 1] = digits[value & 0x0f];
        }
        return new String(output);
    }

    private static void requireValue(String name, String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
