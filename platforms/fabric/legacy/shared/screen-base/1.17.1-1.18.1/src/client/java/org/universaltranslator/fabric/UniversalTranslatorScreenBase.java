package org.universaltranslator.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Per-generation half of the Universal Translator screens for Minecraft 1.17.1 through 1.18.1.
 *
 * <p>{@code MinecraftClient#openScreen} was renamed to {@code setScreen} in 1.17.1; everything else
 * matches the 1.17 variant except the pause hook, which this range splits in half — see
 * {@link #isPauseScreen()}.
 */
abstract class UniversalTranslatorScreenBase extends Screen {
    UniversalTranslatorScreenBase(Text title) {
        super(title);
    }

    protected void openScreen(Screen screen) {
        if (this.client != null) {
            this.client.setScreen(screen);
        }
    }

    @Override
    public void onClose() {
        onBack();
    }

    protected abstract void onBack();

    // The pause answer is declared under both names, and neither carries @Override, because this
    // layer straddles the vanilla rename instead of sitting on one side of it:
    //   yarn 1.17.1+build.65 and 1.18+build.1  -> Screen#isPauseScreen
    //   yarn 1.18.1+build.22                   -> Screen#shouldPause (isPauseScreen is gone)
    // Each name is absent from one half of the range, so a single @Override cannot compile here
    // (1.18.1 rejects isPauseScreen, 1.17.1 and 1.18 reject shouldPause). Exactly one of the two
    // overrides Screen per version and returns pausesGame(); the other is an inert extra method.
    // Splitting this layer into 1.17.1-1.18 and 1.18.1 is what lets each half keep its @Override.
    public boolean isPauseScreen() {
        return pausesGame();
    }

    public boolean shouldPause() {
        return pausesGame();
    }

    /** Whether the singleplayer game keeps running behind this screen; see the 1.18.2 base. */
    protected boolean pausesGame() {
        return false;
    }
}
