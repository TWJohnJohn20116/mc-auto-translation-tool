package org.universaltranslator.forge;

import net.minecraft.client.gui.components.EditBox;

/**
 * Reports the text field the shared 26.x mixin is about to render to this layer's local-input guard.
 *
 * <p>The mixin lives in {@code 26.x/shared/common}, which every 26.x tree compiles, but the guards
 * the trees keep are not the same: this layer is told the instance, while 26.1-26.1.2 still resolves
 * the chat box by reflection. The seam keeps the shared mixin on one call site so neither layer has
 * to declare a mixin the other one cannot compile.
 */
public final class ForgeChatInputTracker {
    private ForgeChatInputTracker() {
    }

    public static void trackRenderedInput(EditBox widget) {
        ForgeLocalTextGuard.trackRenderedInput(widget);
    }
}
