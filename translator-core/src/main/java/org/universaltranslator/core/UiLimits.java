package org.universaltranslator.core;

/**
 * Layout and input limits shared by every platform's screens.
 *
 * <p>These numbers were copied into each screen class, so raising a limit meant finding every
 * copy. They live here because the value is a product decision, not a per-version one.
 */
public final class UiLimits {
    /** Longest custom prompt the settings screen accepts, in characters. */
    public static final int PROMPT_MAX_CHARS = 4096;

    private UiLimits() {
    }
}
