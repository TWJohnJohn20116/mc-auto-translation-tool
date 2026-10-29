package org.universaltranslator.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Per-generation half of the Universal Translator screens for Minecraft 1.17.
 *
 * <p>1.17 added {@code Screen#addDrawableChild}, so this variant no longer declares one, but the
 * screen stack is still reached through {@code MinecraftClient#openScreen}.
 */
abstract class UniversalTranslatorScreenBase extends Screen {
    UniversalTranslatorScreenBase(Text title) {
        super(title);
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
