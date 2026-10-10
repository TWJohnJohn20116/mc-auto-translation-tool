package org.universaltranslator.core;

/**
 * Builds the system prompt every chat-completion provider sends.
 *
 * <p>Two things are configurable here and nothing else:
 *
 * <ul>
 *   <li>{@code translation-quality} selects between three fixed instruction sets. {@link
 *       TranslationQuality#STANDARD} reproduces the historical prompt character for character, so
 *       the default configuration is byte-identical to what the mod sent before quality modes
 *       existed.
 *   <li>{@code custom-system-prompt} replaces the instruction set entirely when it is not empty.
 * </ul>
 *
 * <p>A custom prompt cannot switch off the behaviour the rest of the pipeline depends on. Whatever
 * the user writes, {@link #GUARDRAIL_SINGLE} or {@link #GUARDRAIL_BATCH} is appended, so the model
 * is always told to answer with the translation alone and to keep one output line per input line.
 * Without that, a custom prompt would silently break {@code ProtectedText} restoration (the reply
 * would carry prose around the restored token) and the numbered batch protocol (the reply would
 * merge or drop lines), which are exactly the failures the built-in prompts were written to avoid.
 */
public final class TranslationPrompt {
    // --- The historical single-text prompt, split exactly where the original concatenation split ---
    private static final String IDENTITY =
            "You are a professional Minecraft game-localization translator. ";
    private static final String SINGLE_TRANSLATE = "Translate the user text to ";
    private static final String SINGLE_ONLY =
            ". Reply with only the translation, without quotes, labels, notes, or explanations. ";
    private static final String SINGLE_PRESERVE =
            "Preserve punctuation, whitespace, URLs, usernames, placeholders, and Minecraft "
                    + "formatting markers.";
    private static final String KEEP_LINE_ORDER =
            " Keep exactly the same number and order of lines.";

    // --- The historical numbered-batch prompt, split at the same boundaries ---
    private static final String BATCH_TRANSLATE = "Translate every numbered line of the user message to ";
    private static final String BATCH_NUMBERING =
            ". Reply with the same numbering: exactly one translated line per input line, in "
                    + "the same order, without merging, splitting, reordering or omitting lines. ";
    private static final String BATCH_PRESERVE =
            "Preserve punctuation, whitespace, URLs, usernames, placeholders and Minecraft "
                    + "formatting markers. Reply with only the numbered translations, without quotes, "
                    + "labels, notes or explanations.";

    /** Strictness added by {@link TranslationQuality#HIGH}; shared by both paths. */
    private static final String HIGH_STRICTNESS =
            " Preserve the tone, register and terminology of the original, and translate repeated "
                    + "game terms identically every time. Do not add, remove, reorder or summarize "
                    + "any meaning.";

    /**
     * Appended to every custom prompt. It restates the two contracts the pipeline relies on, so a
     * user-written prompt can change the wording but never the protocol.
     */
    static final String GUARDRAIL_SINGLE =
            " Reply with only the translation, without quotes, labels, notes, or explanations. "
                    + "Preserve punctuation, whitespace, URLs, usernames, placeholders, and Minecraft "
                    + "formatting markers.";
    static final String GUARDRAIL_BATCH =
            " Reply with the same numbering: exactly one translated line per input line, in the same "
                    + "order, without merging, splitting, reordering or omitting lines. Preserve "
                    + "punctuation, whitespace, URLs, usernames, placeholders and Minecraft formatting "
                    + "markers. Reply with only the numbered translations, without quotes, labels, "
                    + "notes or explanations.";

    private TranslationPrompt() {
    }

    /** The prompt configuration one provider instance was built with. */
    public static final class Settings {
        private static final Settings DEFAULT_SETTINGS =
                new Settings(TranslationQuality.DEFAULT, "");

        private final TranslationQuality quality;
        private final String customSystemPrompt;

        public Settings(TranslationQuality quality, String customSystemPrompt) {
            this.quality = quality == null ? TranslationQuality.DEFAULT : quality;
            String custom = customSystemPrompt == null ? "" : customSystemPrompt.trim();
            this.customSystemPrompt = custom;
        }

        /** The configuration that behaves exactly like every build before this feature existed. */
        public static Settings standard() {
            return DEFAULT_SETTINGS;
        }

        public TranslationQuality quality() {
            return quality;
        }

        public String customSystemPrompt() {
            return customSystemPrompt;
        }

        public boolean hasCustomSystemPrompt() {
            return !customSystemPrompt.isEmpty();
        }

        /** Whether this is the historical configuration, and therefore must change nothing. */
        public boolean isDefault() {
            return quality == TranslationQuality.DEFAULT && customSystemPrompt.isEmpty();
        }

        /**
         * Stable marker of this configuration, used to keep cached translations apart.
         *
         * <p>Empty for the default configuration, so the cache key of a default install is exactly
         * what it always was and no user pays for a one-off cache miss on upgrade.
         *
         * @return {@code ""} when default, otherwise a short deterministic tag
         */
        public String signature() {
            if (isDefault()) {
                return "";
            }
            return "q=" + quality.configName() + ";p=" + customSystemPrompt.length() + ":"
                    + Integer.toHexString(customSystemPrompt.hashCode());
        }
    }

    /** The system prompt for one single text (the batch path builds its own). */
    public static String single(Settings settings, TranslationRequest request) {
        Settings effective = settings == null ? Settings.standard() : settings;
        boolean multiLine = request.getText().indexOf('\n') >= 0;
        if (effective.hasCustomSystemPrompt()) {
            return expand(effective.customSystemPrompt(), request, effective.quality())
                    + GUARDRAIL_SINGLE
                    + (multiLine ? KEEP_LINE_ORDER : "");
        }
        String target = TargetLanguage.translationInstruction(request.getTargetLanguage());
        switch (effective.quality()) {
            case FAST:
                return SINGLE_TRANSLATE + target + ". Reply with only the translation."
                        + (multiLine ? KEEP_LINE_ORDER : "");
            case HIGH:
                return IDENTITY + SINGLE_TRANSLATE + target + SINGLE_ONLY + SINGLE_PRESERVE
                        + HIGH_STRICTNESS
                        + (multiLine ? KEEP_LINE_ORDER : "");
            case STANDARD:
            default:
                return IDENTITY + SINGLE_TRANSLATE + target + SINGLE_ONLY + SINGLE_PRESERVE
                        + (multiLine ? KEEP_LINE_ORDER : "");
        }
    }

    /** The system prompt for one numbered batch; {@code request} supplies the target language. */
    public static String batch(Settings settings, TranslationRequest request) {
        Settings effective = settings == null ? Settings.standard() : settings;
        if (effective.hasCustomSystemPrompt()) {
            return expand(effective.customSystemPrompt(), request, effective.quality())
                    + GUARDRAIL_BATCH;
        }
        String target = TargetLanguage.translationInstruction(request.getTargetLanguage());
        switch (effective.quality()) {
            case FAST:
                return BATCH_TRANSLATE + target
                        + ". Reply with the same numbering, one translated line per input line.";
            case HIGH:
                return IDENTITY + BATCH_TRANSLATE + target + BATCH_NUMBERING + BATCH_PRESERVE
                        + HIGH_STRICTNESS;
            case STANDARD:
            default:
                return IDENTITY + BATCH_TRANSLATE + target + BATCH_NUMBERING + BATCH_PRESERVE;
        }
    }

    /**
     * Expands the supported placeholders. An unknown {@code {name}} is left untouched rather than
     * blanked, so a prompt that uses braces for its own purposes survives.
     *
     * @param template user-written prompt
     * @param request request the prompt is being built for
     * @param quality active quality mode, exposed as {@code {mode}}
     * @return the template with the known placeholders replaced
     */
    static String expand(String template, TranslationRequest request, TranslationQuality quality) {
        String mode = quality == null ? TranslationQuality.DEFAULT.configName() : quality.configName();
        return template
                .replace("{target}", describe(request.getTargetLanguage(), false))
                .replace("{source}", describe(request.getSourceLanguage(), true))
                .replace("{kind}", String.valueOf(request.getKind()))
                .replace("{mode}", mode);
    }

    /** {@code 繁體中文 (zh-TW)} style description; the source side says so when it is auto-detected. */
    private static String describe(String language, boolean allowAuto) {
        if (language == null || language.trim().isEmpty()) {
            return allowAuto ? "auto-detect" : "";
        }
        String trimmed = language.trim();
        if (allowAuto && "auto".equalsIgnoreCase(trimmed)) {
            return "auto-detect";
        }
        String canonical = TargetLanguage.canonicalize(trimmed);
        if (canonical.isEmpty()) {
            return trimmed;
        }
        String name = TargetLanguage.displayName(canonical);
        return name.equals(canonical) ? canonical : name + " (" + canonical + ")";
    }
}
