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

    protected boolean pausesGame() {
        return true;
    }
}
