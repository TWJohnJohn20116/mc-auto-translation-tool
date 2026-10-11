package org.universaltranslator.core.provider;

/**
 * How a provider can be asked to answer with a JSON object.
 *
 * <p>The batch protocol keys its reply by input line number, so a provider that can constrain its
 * own output format should say so instead of having the request builder guess. Declaring the mode
 * is not the same as using it: only the OpenAI-compatible provider builds batch requests today, and
 * the other dialects are emitted by their own request builders when a JSON reply is requested.
 *
 * <p>A provider that cannot express the constraint still takes part in a batch. The prompt asks for
 * JSON either way, the reply is parsed tolerantly (prose before or after the object is ignored), and
 * the numbered-list parser the protocol used before is the last resort — which is exactly the path a
 * small local model that ignores the instruction travels.
 */
public enum JsonOutputMode {
    /** No way to ask for one: DeepL answers a form document, not a chat completion. */
    NONE,
    /** OpenAI and Azure OpenAI: {@code "response_format":{"type":"json_object"}}. */
    RESPONSE_FORMAT,
    /** Google Gemini: {@code generationConfig.responseMimeType = "application/json"}. */
    RESPONSE_MIME_TYPE,
    /**
     * Anthropic Claude: an assistant turn prefilled with the opening brace.
     *
     * <p>Claude has no output-format field, so the object is started for it. The reply then continues
     * from that brace, which means a caller using this mode has to put the same character back in
     * front of {@code content[].text} before parsing it.
     */
    PREFILL
}
