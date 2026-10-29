package org.universaltranslator.core.provider;

import java.util.Locale;

/**
 * Whether a response really is an event stream.
 *
 * <p>Three providers carried their own copy of this test, so a rule change had to be made three
 * times and any one of them could disagree with the others. A missing content type counts as "not
 * streaming" everywhere: every endpoint that supports SSE announces it, and guessing the other way
 * would make the client parse an ordinary JSON body as a stream.
 *
 * <p>It lives in the provider package rather than next to
 * {@link org.universaltranslator.core.net.ServerSentEvents} because providers own the decision to
 * fall back; the parser itself is shared and unchanged.
 */
final class EventStream {
    private static final String CONTENT_TYPE = "text/event-stream";

    private EventStream() {
    }

    /**
     * @param contentType raw {@code Content-Type} of the response, possibly {@code null}
     * @return whether the body should be read as an event stream
     */
    static boolean isEventStream(String contentType) {
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).contains(CONTENT_TYPE);
    }
}
