package org.universaltranslator.core.provider;

import org.universaltranslator.core.net.JsonStrings;

import java.util.Map;

/** Small JSON readers shared by the providers whose reply is an array of text fragments. */
final class ProviderJson {
    /** Guards against a provider that answers with an unbounded array. */
    private static final int MAXIMUM_FRAGMENTS = 256;

    private ProviderJson() {
    }

    /**
     * Joins one field of every element of an array.
     *
     * <p>An Anthropic reply carries {@code content[0].text}, {@code content[1].text}, ... and a
     * Gemini reply carries {@code candidates[0].content.parts[0].text}, ...; both must be joined
     * before the result is validated, because a single logical translation is routinely split
     * across several fragments.
     *
     * <p>The body is parsed once here. Reading each fragment through
     * {@link JsonStrings#readStringPath} rebuilt the whole document per fragment, so an answer made
     * of seven pieces cost eight parses of the same bytes on the worker that was assembling it.
     *
     * @param json response body
     * @param arrayPath path of the array, without an index
     * @param field field of each element to join
     * @return the concatenated text, or {@code null} when the array carried none
     */
    static String joinField(String json, String arrayPath, String field) {
        return joinField(JsonTree.parseOrNull(json), arrayPath, field);
    }

    /**
     * The same join over an already parsed body, for callers that need other fields of it too.
     *
     * @param root parsed body, or {@code null} when it was not JSON
     * @param arrayPath path of the array, without an index
     * @param field field of each element to join
     * @return the concatenated text, or {@code null} when the array carried none
     */
    static String joinField(Object root, String arrayPath, String field) {
        return JsonTree.joinField(root, arrayPath, field, MAXIMUM_FRAGMENTS);
    }

    /**
     * Explains a response that carried no translation, preferring the provider's own message.
     *
     * <p>Every one of these APIs answers a bad key, an unknown model or an exhausted quota with a
     * small JSON error object, and reporting only "no content" hides the one sentence that says
     * what to fix.
     *
     * @param provider human-readable provider name
     * @param json raw response body
     * @return a message naming what the provider reported
     */
    static String describeMissingContent(String provider, String json) {
        String message = JsonStrings.readStringField(json, "message");
        if (message == null || message.trim().isEmpty()) {
            message = nestedMessage(json);
        }
        if (message != null && !message.trim().isEmpty()) {
            return provider + " reported: " + message.trim();
        }
        return provider + " response did not contain translated content"
                + JsonStrings.bodyPreview(json);
    }

    /** Reads {@code error.message}, which is how Anthropic and Gemini report failures. */
    private static String nestedMessage(String json) {
        Object root;
        try {
            root = JsonStrings.parse(json);
        } catch (RuntimeException malformed) {
            return null;
        }
        if (!(root instanceof Map)) {
            return null;
        }
        Object error = ((Map<?, ?>) root).get("error");
        if (error instanceof Map) {
            Object message = ((Map<?, ?>) error).get("message");
            if (message instanceof String) {
                return (String) message;
            }
        }
        if (error instanceof String) {
            return (String) error;
        }
        Object feedback = ((Map<?, ?>) root).get("promptFeedback");
        if (feedback instanceof Map) {
            Object blocked = ((Map<?, ?>) feedback).get("blockReason");
            if (blocked instanceof String) {
                return "prompt blocked (" + blocked + ")";
            }
        }
        return null;
    }
}
