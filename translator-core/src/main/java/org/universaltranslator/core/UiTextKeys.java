package org.universaltranslator.core;

/**
 * Translation keys that several screens share.
 *
 * <p>A key spelled inline is invisible to the compiler: a typo turns into a raw key string on
 * screen instead of a build failure. The pairs collected here are the ones repeated across every
 * platform's settings and diagnostics screens.
 */
public final class UiTextKeys {
    /** Localised "on", used for any boolean option's current value. */
    public static final String VALUE_ON = "value.universal_translator.on";

    /** Localised "off", the counterpart to {@link #VALUE_ON}. */
    public static final String VALUE_OFF = "value.universal_translator.off";

    private UiTextKeys() {
    }

    /** Never returns null: the key naming a boolean's localised on/off label. */
    public static String onOff(boolean value) {
        return value ? VALUE_ON : VALUE_OFF;
    }
}
