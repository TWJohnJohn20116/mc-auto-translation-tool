package org.universaltranslator.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Per-generation half of the Universal Translator screens for Minecraft 1.16 through 1.16.4.
 *
 * <p>The shared {@code screens/1.16-1.18} layer holds the three screens once; everything that
 * changed between Minecraft versions lives here, so exactly one layer per generation has to be
 * kept in sync with the game.
 *
 * <p>This variant targets 1.16 to 1.16.4: {@code Screen} has no {@code addDrawableChild} yet and the
 * drawable child type is still called {@code AbstractButtonWidget} (renamed to
 * {@code ClickableWidget} in 1.16.5).
 */
abstract class UniversalTranslatorScreenBase extends Screen {
    UniversalTranslatorScreenBase(Text title) {
        super(title);
    }

    protected <T extends net.minecraft.client.gui.widget.AbstractButtonWidget> T addDrawableChild(T child) {
        return addButton(child);
    }

    protected void openScreen(Screen screen) {
        if (this.client != null) {
            this.client.openScreen(screen);
        }
    }

    @Override
    public void onClose() {
        onBack();
    }

    protected abstract void onBack();

    public boolean isPauseScreen() {
        return pausesGame();
    }

    protected boolean pausesGame() {
        return false;
    }
}
