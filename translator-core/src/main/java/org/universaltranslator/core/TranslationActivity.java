package org.universaltranslator.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide count of translations currently in flight, so the HUD indicator can show whether
 * work is happening without every platform runtime having to publish its own status.
 *
 * <p>{@link TranslationCoordinator} is the single choke point every platform goes through, so it
 * marks this; the HUD mixins only read it. That is why this is a static counter rather than a
 * field on {@link HomeQuickSettingsState}: the snapshot is rebuilt from config whenever settings
 * change, while this changes per translation.
 *
 * <p>Deliberately leak-tolerant: {@link #end()} never drives the count below zero. A missed end
 * would otherwise pin the indicator to "translating" forever, and a double end would do the same
 * by going negative.
 */
public final class TranslationActivity {
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();

    private TranslationActivity() {
    }

    /** Call once when a translation is handed to a provider. */
    public static void begin() {
        IN_FLIGHT.incrementAndGet();
    }

    /** Call once when that translation settles, successfully or not. Safe to call unbalanced. */
    public static void end() {
        IN_FLIGHT.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    public static boolean isTranslating() {
        return IN_FLIGHT.get() > 0;
    }

    /** Test/diagnostics hook; the HUD only needs {@link #isTranslating()}. */
    public static int inFlight() {
        return IN_FLIGHT.get();
    }
}
