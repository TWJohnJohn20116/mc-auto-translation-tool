package org.universaltranslator.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Per-generation half of the Universal Translator screens for Minecraft 1.18.2.
 *
 * <p>1.18.2 renamed {@code Screen#onClose} to {@code close} and {@code isPauseScreen} to
 * {@code shouldPause}, and both are now {@code @Override}s of the vanilla methods.
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
    public void close() {
        onBack();
    }

    protected abstract void onBack();

    @Override
    public boolean shouldPause() {
        return pausesGame();
    }

    /**
     * Whether the singleplayer game keeps running behind this screen.
     *
     * <p>Answered in one place for every generation that shares the screens: 1.16.5, 1.17, 1.17.1,
     * 1.18, 1.18.1 and now 1.18.2 all return {@code false}. 1.18.2 used to answer {@code true} from
     * a seam of its own, so opening the settings there froze the world while the same screen on
     * 1.18.1 did not.
     */
    protected boolean pausesGame() {
        return false;
    }
}
