package org.universaltranslator.core.provider;

/**
 * Remembers that an endpoint's event stream never declares its own end.
 *
 * <p>All three streaming providers already recorded such a stream in the debug trace and then sent
 * one complete request again, because a stream that starts cleanly, accumulates content and simply
 * never sends {@code [DONE]}, {@code finish_reason} or {@code message_stop} was not remembered
 * anywhere: only "this endpoint is not an event stream at all" and "this endpoint rejected the
 * request" set the permanent {@code streamingUnsupported} flag. A relay that always drops the tail
 * therefore paid two requests and two round trips for every single line, for the whole session, with
 * the doubling visible only to whoever read the trace.
 *
 * <p>Two incomplete streams in a row are enough to conclude that this endpoint does not declare its
 * end, and the existing flag then routes every later line straight to the plain path. One complete
 * stream resets the count, so a single dropped connection never disables streaming on an endpoint
 * that does declare its end.
 */
final class StreamCompletionMemory {
    /** Consecutive incomplete streams after which streaming is abandoned. */
    static final int INCOMPLETE_STREAMS_BEFORE_FALLBACK = 2;

    private int incompleteStreams;

    /**
     * Records one stream that ended without an end marker.
     *
     * @return {@code true} when this endpoint should stop being asked to stream
     */
    synchronized boolean rememberIncomplete() {
        incompleteStreams++;
        return incompleteStreams >= INCOMPLETE_STREAMS_BEFORE_FALLBACK;
    }

    /** Records one stream that reached its own end marker; clears the streak. */
    synchronized void rememberComplete() {
        incompleteStreams = 0;
    }

    /**
     * Consecutive incomplete streams observed so far, for the diagnostics trace.
     *
     * <p>An empty answer (a block reason, a refused prompt) is deliberately never recorded here: it
     * says nothing about whether the endpoint declares its end, and counting it would disable
     * streaming on an endpoint that merely answered "nothing to translate".
     */
    synchronized int incompleteStreams() {
        return incompleteStreams;
    }
}
