package org.universaltranslator.core.provider;

import org.universaltranslator.core.net.JsonStrings;

import java.io.IOException;

/**
 * Builds the exception a vendor-reported error becomes.
 *
 * <p>These APIs answer a rejected request with {@code HTTP 200} and an error object, so the only
 * signal is the code in the body. One builder used to live in {@link BaiduTranslationProvider} and
 * returned the same {@link IllegalStateException} whatever the code said: a rate limit, an exhausted
 * balance and a bad signature were one message, so nothing backed off, nothing stopped, and the
 * text named no action. Editing Baidu also reached the six unrelated providers that called it.
 *
 * <p>Two shapes are enough. A rate limit is the one vendor error another attempt can answer, so it
 * becomes an {@link IOException} — the type {@link ResilientTranslationProvider} already retries and
 * {@link FailurePolicy} already lets through to the fallback. Everything else is this package's "this
 * request cannot succeed" family and stays an {@link IllegalStateException}, with the vendor's own
 * code and message kept in the text.
 */
final class ProviderErrors {
    private ProviderErrors() {
    }

    /**
     * Classifies one vendor error body.
     *
     * @param provider human-readable provider name
     * @param response raw response body
     * @param codeField field carrying the vendor's error code, or {@code null}
     * @param messageField field carrying the vendor's error message, or {@code null}
     * @return the exception to throw
     */
    static Exception of(String provider, String response, String codeField, String messageField) {
        String code = codeField == null ? null : JsonStrings.readStringField(response, codeField);
        String message = messageField == null ? null : JsonStrings.readStringField(response, messageField);
        return of(provider, response, code, message);
    }

    private static Exception of(String provider, String response, String code, String message) {
        String detail = (code == null ? "" : " (" + code + ")")
                + (message == null ? "" : ": " + message);
        String hint = hintFor(code);
        if (isRateLimited(code)) {
            return new IOException(provider + " rate limit" + detail
                    + "; the request is retried after a pause" + hint);
        }
        return new IllegalStateException(provider
                + " response did not contain translated text" + detail + hint);
    }

    /**
     * The exception for "the reply carried no translation".
     *
     * <p>A body that is not JSON at all — an empty {@code 200}, a gateway's HTML page, a truncated
     * document — is a transport-shaped failure another attempt can answer differently, so it becomes
     * an {@link IOException} that the retry policy and the fallback accept. A body that is a valid
     * document but has no text in it is the provider reporting a business error, and stays this
     * package's deterministic family.
     *
     * @param provider human-readable provider name
     * @param response raw response body
     * @param codeField field carrying the vendor's error code, or {@code null}
     * @param messageField field carrying the vendor's error message, or {@code null}
     * @return the exception to throw
     */
    static Exception unreadableOr(String provider, String response, String codeField,
            String messageField) {
        if (JsonTree.parseOrNull(response) == null) {
            return new IOException(provider + " reply is not readable JSON"
                    + JsonStrings.bodyPreview(response));
        }
        return of(provider, response, codeField, messageField);
    }

    /**
     * "This reply is not the provider's document", as a retryable failure.
     *
     * <p>For the providers whose reader is a plain method without a checked exception, so the
     * unreadable case has to be detected and rethrown at the call site.
     *
     * @param provider human-readable provider name
     * @param body raw response body
     * @return the failure to throw
     */
    static IOException unreadable(String provider, String body) {
        return new IOException(provider + " reply is not readable JSON"
                + JsonStrings.bodyPreview(body));
    }

    /**
     * Reads one JSON path, mapping "this body is not the provider's document" to a retryable failure.
     *
     * <p>{@link JsonStrings#readStringPath} throws {@link IllegalArgumentException} for an empty body
     * and for a body that is not a document, which both {@link ResilientTranslationProvider} and
     * {@link FailurePolicy} read as "this request cannot succeed": the retry budget and the fallback
     * were both skipped for exactly the failures a second attempt fixes.
     *
     * @param provider human-readable provider name
     * @param body raw response body
     * @param path path to read
     * @return the string at that path, or {@code null} when the document has no such field
     * @throws IOException when the body could not be read as a document
     */
    static String pathOrNull(String provider, String body, String path) throws IOException {
        try {
            return JsonStrings.readStringPath(body, path);
        } catch (IllegalArgumentException malformed) {
            IOException failure = unreadable(provider, body);
            failure.initCause(malformed);
            throw failure;
        }
    }

    /**
     * Codes a vendor uses for "you are asking too often".
     *
     * <p>Baidu {@code 54003}/{@code 54005}, Youdao {@code 411}, Tencent
     * {@code RequestLimitExceeded}.
     */
    private static boolean isRateLimited(String code) {
        return "54003".equals(code) || "54005".equals(code) || "411".equals(code)
                || "RequestLimitExceeded".equals(code);
    }

    /** What the reader can do about the codes whose next step is not obvious. */
    private static String hintFor(String code) {
        if (code == null) {
            return "";
        }
        if ("54004".equals(code) || "FailedOperation.NoFreeAmount".equals(code)) {
            return "; the account has no translation quota left, top it up before translating again";
        }
        if ("54001".equals(code) || "108".equals(code) || "202".equals(code)) {
            return "; the request signature was rejected, check the secret/API key field for this provider";
        }
        if ("52003".equals(code) || "AuthFailure".equals(code)) {
            return "; the credential was not accepted, check the key field for this provider";
        }
        if ("54005".equals(code)) {
            return "; the text is long enough that the vendor rate-limits it, so it is retried";
        }
        return "";
    }
}
