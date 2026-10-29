package org.universaltranslator.core.provider;

import org.universaltranslator.core.net.HttpStatusException;
import org.universaltranslator.core.net.TranslationEndpointUnavailableException;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;

/**
 * The two "what kind of failure is this" decisions two providers used to make by accident.
 *
 * <p>{@link FallbackTranslationProvider} caught every exception and treated it as "the primary is
 * broken", and {@link OpenAiChatTranslationProvider}'s batch path repaired a whole group line by
 * line whatever had failed. Both multiplied a deterministic refusal — a text too long for the local
 * context, a rejected answer, an authentication failure — into extra paid requests, and the first
 * one also broke the privacy boundary the offline engine exists for by sending that text to the
 * online API.
 *
 * <p>The two questions are asked here so that "which failures are worth another attempt" has exactly
 * one answer in the package.
 */
final class FailurePolicy {
    private FailurePolicy() {
    }

    /**
     * Whether a primary-provider failure may be handed to the fallback provider.
     *
     * <p>Only a failure that another engine could plausibly answer differently qualifies: a transport
     * error, an HTTP status, a timeout. {@link IllegalArgumentException} and
     * {@link IllegalStateException} are this package's "this request cannot succeed" family — a text
     * that does not fit the context window, a rejected output, a protocol error — and re-sending that
     * text to an online API is a privacy decision the user never made.
     *
     * @param failure what the primary provider threw
     * @return whether the text may be sent to the fallback provider
     */
    static boolean mayFallBack(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof IllegalArgumentException || current instanceof IllegalStateException) {
                return false;
            }
            // A cancelled session is handled by the caller before this question is asked; asking
            // here as well keeps the answer safe if the order ever changes.
            if (current instanceof InterruptedException) {
                return false;
            }
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            if (current instanceof InterruptedIOException) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a failed batch is worth repairing one text at a time.
     *
     * <p>A 401, 403 or 404 (or an endpoint that is not listening at all) answers the same way for
     * every text in the group, so resending each of the eight lines only multiplies the failure by
     * eight. A 400 is deliberately still repaired: the body may name one unusable line while the rest
     * would have worked.
     *
     * @param failure what the batch request threw
     * @return whether each text should be retried with a request of its own
     */
    static boolean repairablePerLine(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof TranslationEndpointUnavailableException) {
                return false;
            }
            if (current instanceof HttpStatusException) {
                int status = ((HttpStatusException) current).getStatusCode();
                return status != 401 && status != 403 && status != 404;
            }
        }
        return true;
    }
}
