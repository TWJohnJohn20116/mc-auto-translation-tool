package org.universaltranslator.forge;

import org.universaltranslator.core.DebugLog;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records which optional (non-fatal) mixin hooks actually ran, once per process.
 *
 * <p>The four Forge targets used to disagree about injection failure: 1.20.1 injected with
 * {@code defaultRequire: 1} and crashed the client on a single miss, while 1.16.5, 1.18.2 and
 * 1.19.2 injected with {@code defaultRequire: 0} and lost the hook without a word. Both failure
 * modes are now explicit per hook: the hooks the translation pipeline cannot live without declare
 * {@code require = 1} and fail the launch loudly, and the optional ones (context kinds, HUD
 * decoration, the title-screen entry) declare {@code require = 0} and report their first run here,
 * so a silently skipped hook leaves a trace in the debug log instead of looking like "the mod is
 * on but nothing translates".
 *
 * <p>The check is a single volatile read per call, so the per-frame HUD hooks may call it on every
 * frame without measurable cost.
 */
public final class ForgeMixinDiagnostics {
    /** Boss bar context kind (optional: text still translates with the neutral kind). */
    public static final int BOSS_BAR_CONTEXT = 0;
    /** Scoreboard sidebar context kind (optional). */
    public static final int SCOREBOARD_CONTEXT = 1;
    /** Tab-list context kind (optional). */
    public static final int PLAYER_LIST_CONTEXT = 2;
    /** HUD status indicator and outgoing preview (pure decoration, optional). */
    public static final int HUD_INDICATOR = 3;
    /** Title-screen settings entry (decorative entry point, optional). */
    public static final int TITLE_SCREEN_ENTRY = 4;
    /** Inventory tooltip translation (optional). */
    public static final int ITEM_TOOLTIP = 5;

    private static final AtomicInteger REPORTED = new AtomicInteger();

    private ForgeMixinDiagnostics() {
    }

    /**
     * Logs {@code hook} the first time the calling optional hook runs.
     *
     * @param slot one of this class's {@code *_CONTEXT}/{@code *_ENTRY}/{@code *_INDICATOR} slots
     * @param hook short hook name used as the debug-log value
     */
    public static void optionalHookActive(int slot, String hook) {
        int mask = 1 << slot;
        if ((REPORTED.get() & mask) != 0) {
            return;
        }
        if ((REPORTED.getAndUpdate(value -> value | mask) & mask) != 0) {
            return;
        }
        DebugLog.global().log("mixin", hook, "optional-injection-active");
    }
}
