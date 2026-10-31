package org.universaltranslator.forge;

import net.minecraft.client.gui.components.EditBox;

/**
 * Reports the text field the shared 26.x mixin is about to render to this layer's local-input guard.
 *
 * <p>The mixin lives in {@code 26.x/shared/common}, which every 26.x tree compiles, but the guards
 * the trees keep are not the same: 26.2 is told the instance through this seam, while 26.1-26.1.2
 * resolves the chat box by reflection and therefore has nothing to feed.
 */
public final class ForgeChatInputTracker {
    private ForgeChatInputTracker() {
    }

    public static void trackRenderedInput(EditBox widget) {
        // 26.1-26.1.2 reads the chat field reflectively from ChatScreen, so no report is needed.
    }
}
