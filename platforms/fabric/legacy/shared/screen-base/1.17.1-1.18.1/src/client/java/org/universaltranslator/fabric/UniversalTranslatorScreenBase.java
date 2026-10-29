package org.universaltranslator.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Per-generation half of the Universal Translator screens for Minecraft 1.17.1 through 1.18.1.
 *
 * <p>{@code MinecraftClient#openScreen} was renamed to {@code setScreen} in 1.17.1; everything else
 * matches the 1.17 variant.
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

    // @Override so a rename in this generation breaks the build instead of silently reverting to
    // Screen's own answer; 1.18.2 renames this method to shouldPause and has its own base.
    @Override
    public boolean isPauseScreen() {
        return pausesGame();
    }

    /** Whether the singleplayer game keeps running behind this screen; see the 1.18.2 base. */
    protected boolean pausesGame() {
        return false;
    }
}
