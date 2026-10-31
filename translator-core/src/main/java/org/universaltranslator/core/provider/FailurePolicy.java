package org.universaltranslator.core.provider;

import org.universaltranslator.core.net.HttpStatusException;
import org.universaltranslator.core.net.TranslationEndpointUnavailableException;

import java.io.IOException;
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
    /**
     * Depth cap for one cause walk.
     *
     * <p>A wrapper built with {@code initCause(this)} makes the chain its own successor, and an
     * unbounded walk then never returns: the worker that asked this question disappears without the
     * coordinator noticing. {@code TranslationStats.reasonOf} bounds the same walk the same way.
     */
    static final int MAXIMUM_CAUSE_DEPTH = 16;

    private FailurePolicy() {
    }

    /** The next link of one cause chain, or {@code null} when the chain ends or points at itself. */
    private static Throwable nextCause(Throwable current) {
        Throwable next = current.getCause();
        return next == current ? null : next;
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
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAXIMUM_CAUSE_DEPTH; depth++) {
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
            current = nextCause(current);
        }
        return true;
    }

    /**
     * Whether one failed batch is worth one more attempt as a whole.
     *
     * <p>Only a transient transport or server failure qualifies. Anything deterministic — a 400, a
     * credential, a refused budget — answers the same way when the identical group is resent.
     *
     * @param failure what the batch request threw
     * @return whether the whole group may be sent once more
     */
    static boolean retryableAsGroup(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAXIMUM_CAUSE_DEPTH; depth++) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            if (current instanceof InterruptedException || current instanceof InterruptedIOException) {
                return false;
            }
            if (current instanceof TranslationEndpointUnavailableException) {
                return false;
            }
            if (current instanceof HttpStatusException) {
                return ((HttpStatusException) current).isRetryable();
            }
            if (current instanceof IOException) {
                return true;
            }
            current = nextCause(current);
        }
        return false;
    }

    /**
     * Whether a failed batch is worth repairing one text at a time.
     *
     * <p>Only a 400 may name one unusable line while the rest would have worked. Every retryable
     * status (429, 5xx, 408, 425), every other client status (401, 403, 404) and every transport
     * failure answers the whole group the same way, so resending each of the eight lines only
     * multiplies the failure by eight. A cancelled session is not repairable either: the caller is
     * gone, and each repair would be a paid request for a text nobody waits for.
     *
     * @param failure what the batch request threw
     * @return whether each text should be retried with a request of its own
     */
    static boolean repairablePerLine(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAXIMUM_CAUSE_DEPTH; depth++) {
            if (current instanceof InterruptedException || current instanceof InterruptedIOException) {
                return false;
            }
            if (current instanceof TranslationEndpointUnavailableException) {
                return false;
            }
            if (current instanceof HttpStatusException) {
                return ((HttpStatusException) current).getStatusCode() == 400;
            }
            if (current instanceof IOException) {
                // A timeout, a watchdog budget or a dropped connection: the whole group shares it.
                return false;
            }
            current = nextCause(current);
        }
        return true;
    }
}
