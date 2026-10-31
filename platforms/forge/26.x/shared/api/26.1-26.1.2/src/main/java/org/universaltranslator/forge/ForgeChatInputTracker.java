package org.universaltranslator.forge;

import net.minecraft.client.gui.components.EditBox;

/**
 * Reports the text field the shared 26.x mixin is about to render to this layer's local-input guard.
 *
 * <p>The mixin lives in {@code 26.x/shared/common}, which every 26.x tree compiles, but the guards the
 * trees keep are per-layer copies: both layers are handed the instance through this seam, so neither has
 * to look the chat box up by reflection any more.
 */
public final class ForgeChatInputTracker {
    private ForgeChatInputTracker() {
    }

    public static void trackRenderedInput(EditBox widget) {
        ForgeLocalTextGuard.trackRenderedInput(widget);
    }
}
