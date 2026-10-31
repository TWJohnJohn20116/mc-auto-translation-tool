package org.universaltranslator.core;

/**
 * Turns untrusted text into one bounded line without splitting a character in half.
 *
 * <p>Four failure paths — a JSON body excerpt, a redirect target, a llama.cpp log summary and a
 * process start message — each carried their own copy of "flatten, truncate, append an ellipsis",
 * and only one of them refused to cut between the halves of a surrogate pair. The orphan half is
 * encoded to {@code ?} by every UTF-8 writer downstream (HUD drawing, chat, the debug log and the
 * diagnostics bundle), so the same symptom could reappear in four layers while only one was fixed.
 * Keeping one implementation is what makes the next message inherit the guard.
 */
public final class TextPreview {
    private TextPreview() {
    }

    /**
     * Flattens control characters to spaces and truncates to {@code maxChars}.
     *
     * @param text text to describe, possibly {@code null}
     * @param maxChars longest result in chars, ignoring any ellipsis the caller adds
     * @return the flattened text, never {@code null}, never ending on an unpaired high surrogate
     */
    public static String oneLine(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        int limit = Math.max(0, Math.min(text.length(), maxChars));
        StringBuilder single = new StringBuilder(text.length());
        for (int index = 0; index < limit; index++) {
            char character = text.charAt(index);
            single.append(character < 0x20 ? ' ' : character);
        }
        if (limit >= text.length()) {
            return single.toString();
        }
        // The cut fell inside the text: drop a trailing high surrogate instead of encoding it alone.
        if (single.length() > 0 && Character.isHighSurrogate(single.charAt(single.length() - 1))) {
            single.setLength(single.length() - 1);
        }
        return single.toString();
    }
}
