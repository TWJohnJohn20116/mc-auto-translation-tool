package org.universaltranslator.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Per-generation half of the Universal Translator screens for Minecraft 1.16.5.
 *
 * <p>Same as the 1.16 to 1.16.4 variant except that {@code AbstractButtonWidget} was renamed to
 * {@code ClickableWidget} in 1.16.5.
 */
abstract class UniversalTranslatorScreenBase extends Screen {
    UniversalTranslatorScreenBase(Text title) {
        super(title);
    }

    protected <T extends net.minecraft.client.gui.widget.ClickableWidget> T addDrawableChild(T child) {
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

    @Override
    public boolean isPauseScreen() {
        return pausesGame();
    }

    protected boolean pausesGame() {
        return false;
    }
}
