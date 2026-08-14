package org.universaltranslator.core.net;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Map;

/** Dependency-free hashes, HMACs, and form encoding used by translation APIs. */
public final class CryptoSupport {
    private CryptoSupport() {
    }

    public static String md5Hex(String value) {
        return hex(digest("MD5", utf8(value)));
    }

    public static String md5Base64(String value) {
        return Base64.getEncoder().encodeToString(digest("MD5", utf8(value)));
    }

    public static String sha256Hex(String value) {
        return hex(digest("SHA-256", utf8(value)));
    }

    public static String sha256Hex(byte[] value) {
        return hex(digest("SHA-256", value));
    }

    public static String hmacSha1Base64(String secret, String value) {
        return Base64.getEncoder().encodeToString(hmac("HmacSHA1", utf8(secret), utf8(value)));
    }

    public static String hmacSha256Base64(String secret, String value) {
        return Base64.getEncoder().encodeToString(hmac("HmacSHA256", utf8(secret), utf8(value)));
    }

    public static byte[] hmacSha256(byte[] secret, String value) {
        return hmac("HmacSHA256", secret, utf8(value));
    }

    public static String hmacSha256Hex(byte[] secret, String value) {
        return hex(hmacSha256(secret, value));
    }

    public static String base64(String value) {
        return Base64.getEncoder().encodeToString(utf8(value));
    }

    public static String formEncode(Map<String, String> fields) {
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            if (encoded.length() > 0) {
                encoded.append('&');
            }
            encoded.append(urlEncode(field.getKey())).append('=').append(urlEncode(field.getValue()));
        }
        return encoded.toString();
    }

    public static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String rfc3986Encode(String value) {
        return urlEncode(value).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    public static byte[] utf8(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }

    public static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) {
            result.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            result.append(Character.forDigit(item & 0x0f, 16));
        }
        return result.toString();
    }

    private static byte[] digest(String algorithm, byte[] value) {
        try {
            return MessageDigest.getInstance(algorithm).digest(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(algorithm + " is unavailable", exception);
        }
    }

    private static byte[] hmac(String algorithm, byte[] secret, byte[] value) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(secret, algorithm));
            return mac.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(algorithm + " is unavailable", exception);
        }
    }
}
