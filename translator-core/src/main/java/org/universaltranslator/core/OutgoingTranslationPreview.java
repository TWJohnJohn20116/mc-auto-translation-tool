package org.universaltranslator.core;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The outgoing-chat translation that is being generated right now, published for the HUD.
 *
 * <p>Sending a chat message closes the chat screen and leaves the player looking at the world while
 * the endpoint works, so the partial translation is drawn beside the HUD status indicator. Every
 * platform reads this one holder instead of the platform runtime having to push a preview through
 * its own state, which is why it is a static holder rather than a field on a session: the render
 * hook that draws it has no session reference.
 *
 * <p>Only the newest request owns the line. {@link Handle#onPartialText(String)} from an older
 * request is ignored once a newer one has started, so a slow answer cannot overwrite a fresh one,
 * and {@link Handle#finish()} only clears the line it still owns.
 *
 * <p>The published text is bounded and sanitized here rather than at the draw call: it comes
 * straight from a remote endpoint and has not been through {@link TranslationOutputValidator} yet,
 * and the HUD draws it as a single literal line.
 */
public final class OutgoingTranslationPreview {
    /** Longest preview the HUD shows; the indicator has room for one short line. */
    private static final int MAXIMUM_PREVIEW_CHARS = 48;
    /** Suffix that marks a preview cut short, so a truncated line is not read as the whole answer. */
    private static final String TRUNCATION_SUFFIX = "...";
    /**
     * A preview that stops arriving is dropped instead of pinning the HUD line for the session. A
     * stream that stalls ends without a partial for as long as the request is still running, so the
     * window is generous: it only has to be shorter than "the player forgot about it". Visible to the
     * self-test, which restores it after shortening the window.
     */
    static final long DEFAULT_STALE_MILLIS = 60_000L;

    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final AtomicReference<State> CURRENT = new AtomicReference<State>();
    private static volatile long staleMillis = DEFAULT_STALE_MILLIS;

    private OutgoingTranslationPreview() {
    }

    /**
     * Claims the HUD line for one outgoing translation.
     *
     * @return a handle that publishes into the line and releases it again
     */
    public static Handle begin() {
        Handle handle = new Handle(SEQUENCE.incrementAndGet());
        publish(handle.state);
        return handle;
    }

    /**
     * The accumulated translation currently being generated, or {@code null} when there is none.
     *
     * <p>Called from the render thread every frame, so this only reads a reference and a length.
     */
    public static String text() {
        State state = CURRENT.get();
        if (state == null || state.partialText.isEmpty()) {
            return null;
        }
        if (System.currentTimeMillis() - state.updatedAtMillis > staleMillis) {
            CURRENT.compareAndSet(state, null);
            return null;
        }
        return state.partialText;
    }

    /** Test seam: shortens the staleness window so its expiry is not a 60-second test. */
    static void setStaleMillisForTesting(long millis) {
        staleMillis = Math.max(0L, millis);
    }

    private static void publish(State next) {
        while (true) {
            State current = CURRENT.get();
            if (current != null && current.sequence > next.sequence) {
                // A newer outgoing translation already owns the line.
                return;
            }
            if (CURRENT.compareAndSet(current, next)) {
                return;
            }
        }
    }

    /** One published preview: its text, when it was published, and which request owns it. */
    private static final class State {
        private final long sequence;
        private final String partialText;
        private final long updatedAtMillis;

        private State(long sequence, String partialText, long updatedAtMillis) {
            this.sequence = sequence;
            this.partialText = partialText;
            this.updatedAtMillis = updatedAtMillis;
        }
    }

    /** Owner of one outgoing translation's line on the HUD. */
    public static final class Handle implements TranslationStreamListener {
        private final long sequence;
        private volatile State state;
        private volatile boolean finished;

        private Handle(long sequence) {
            this.sequence = sequence;
            this.state = new State(sequence, "", System.currentTimeMillis());
        }

        @Override
        public void onPartialText(String partialText) {
            if (finished) {
                return;
            }
            State next = new State(sequence, sanitize(partialText), System.currentTimeMillis());
            state = next;
            publish(next);
        }

        /** Releases the line. Safe to call more than once and from any thread. */
        public void finish() {
            finished = true;
            // Only clears the line this handle still owns: a newer outgoing translation that has
            // already claimed it must keep its own preview.
            CURRENT.compareAndSet(state, null);
        }
    }

    /**
     * Makes one endpoint fragment safe to draw as a single literal HUD line.
     *
     * <p>Legacy formatting codes are removed rather than escaped: a {@code \u00a7} coming from the
     * endpoint would otherwise recolour, or hide, the rest of the line. Control characters are
     * dropped because the HUD draws one line and a stray newline would move the caret.
     */
    private static String sanitize(String partialText) {
        if (partialText == null) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder(Math.min(partialText.length(), MAXIMUM_PREVIEW_CHARS));
        for (int index = 0; index < partialText.length(); index++) {
            char character = partialText.charAt(index);
            if (character == '\u00a7') {
                continue;
            }
            sanitized.append(character < 0x20 || character == 0x7f ? ' ' : character);
        }
        String value = sanitized.toString().trim();
        if (value.length() <= MAXIMUM_PREVIEW_CHARS) {
            return value;
        }
        return value.substring(0, MAXIMUM_PREVIEW_CHARS - TRUNCATION_SUFFIX.length()) + TRUNCATION_SUFFIX;
    }
}
