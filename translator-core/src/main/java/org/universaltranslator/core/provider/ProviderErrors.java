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
        return classify(provider, response, code, message);
    }

    private static Exception classify(String provider, String response, String code, String message) {
        String detail = (code == null ? "" : " (" + code + ")")
                + (message == null ? "" : ": " + message);
        String hint = hintFor(code);
        if (isRateLimited(code)) {
            return new IOException(provider + " rate limit" + detail
                    + "; the request is retried after a pause" + hint);
        }
        if (isFatal(code)) {
            // A code no retry can answer — exhausted quota, rejected signature, refused credential.
            // The type carries that verdict so the resilient layer can stop asking for a while
            // instead of paying for one guaranteed failure per line.
            return new FatalException(provider
                    + " response did not contain translated text" + detail + hint, code);
        }
        return new IllegalStateException(provider
                + " response did not contain translated text" + detail + hint);
    }

    /**
     * Codes no retry can answer: an exhausted balance, a rejected signature, a credential the
     * provider does not accept.
     *
     * <p>Every one of them was already classified as "this request cannot succeed", but nothing
     * remembered the verdict, so a forty-line screen sent forty signed requests that all had to
     * fail the same way. Only codes whose next step is a change on the account side are listed:
     * an unknown code stays a plain {@link IllegalStateException} and gets no cooldown.
     */
    private static boolean isFatal(String code) {
        if (code == null) {
            return false;
        }
        for (int index = 0; index < FATAL_CODES.length; index++) {
            if (FATAL_CODES[index].equals(code)) {
                return true;
            }
        }
        return false;
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
        return unreadable(provider, body, null);
    }

    /**
     * The same failure, keeping the reader's own explanation in the text.
     *
     * <p>The reason travels in the message instead of a cause: {@link FailurePolicy#mayFallBack}
     * walks the cause chain and reads {@link IllegalArgumentException} as "this request cannot
     * succeed", so attaching the reader's exception as the cause had the fallback refuse exactly the
     * transport-shaped failures a second attempt fixes.
     *
     * @param provider human-readable provider name
     * @param body raw response body
     * @param reason the reader's explanation, or {@code null}
     * @return the failure to throw
     */
    static IOException unreadable(String provider, String body, String reason) {
        String detail = reason == null || reason.trim().isEmpty() ? "" : " (" + reason.trim() + ")";
        return new IOException(provider + " reply is not readable JSON"
                + JsonStrings.bodyPreview(body) + detail);
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
            // The reader's exception is folded into the message rather than attached as the cause:
            // FailurePolicy.mayFallBack reads an IllegalArgumentException in the chain as "this
            // request cannot succeed", so the wrapped failure was classified retryable by one
            // question and un-fallbackable by the other, and the fallback never took over. The
            // reader's own text already carries the body excerpt, so it is quoted as it is.
            String reason = malformed.getMessage();
            throw new IOException(provider + " reply is not readable JSON"
                    + (reason == null || reason.trim().isEmpty()
                            ? JsonStrings.bodyPreview(body) : ": " + reason));
        }
    }

    /**
     * Codes a vendor uses for "you are asking too often".
     *
     * <p>One table for every provider this package talks to, because the answer to the same
     * question used to depend on which provider asked it: Baidu {@code 54003}/{@code 54005},
     * Youdao {@code 411} and Tencent {@code RequestLimitExceeded} were retried, while Aliyun's
     * {@code Throttling}, Huawei's {@code APIGW.0308}, Volcengine's {@code TooManyRequests} and the
     * OpenAI-shaped {@code RateLimitExceeded} were reported as "this request cannot succeed" — a
     * forty-line screen then hit the same wall forty times, each with its own signature and
     * handshake.
     */
    private static final String[] RATE_LIMIT_CODES = {
            "54003", "54005", "411",
            "RequestLimitExceeded", "Throttling", "Throttling.User", "ServiceUnavailable",
            "APIGW.0308", "TooManyRequests", "RateLimitExceeded"
    };

    /** Codes whose next step is a change on the account side; see {@link #isFatal}. */
    private static final String[] FATAL_CODES = {
            "54004", "FailedOperation.NoFreeAmount",
            "54001", "108", "202",
            "52003", "AuthFailure"
    };

    private static boolean isRateLimited(String code) {
        if (code == null) {
            return false;
        }
        for (int index = 0; index < RATE_LIMIT_CODES.length; index++) {
            if (RATE_LIMIT_CODES[index].equals(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A vendor failure no retry can answer, with the code that said so.
     *
     * <p>It deliberately extends {@link IllegalStateException}: {@link FailurePolicy}'s two
     * questions already answer "do not retry, do not fall back" for that family, and the privacy
     * boundary around the offline engine depends on that answer never changing. The type only adds
     * the cooldown the resilient layer needs.
     */
    static final class FatalException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final String code;

        /** Package-private so the resilient layer can repeat the verdict during its cooldown. */
        FatalException(String message, String code) {
            super(message);
            this.code = code == null ? "" : code;
        }

        /** The vendor code that made this failure fatal, never {@code null}. */
        String code() {
            return code;
        }
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
        if (isRateLimited(code)) {
            return "; the endpoint is asking for fewer requests, so this one is retried after a pause";
        }
        return "";
    }
}
