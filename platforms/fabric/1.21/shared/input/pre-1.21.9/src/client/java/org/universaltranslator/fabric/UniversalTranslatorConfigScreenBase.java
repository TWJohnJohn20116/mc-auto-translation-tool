package org.universaltranslator.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Bridges the classic mouse callback used through Minecraft 1.21.8. */
abstract class UniversalTranslatorConfigScreenBase extends Screen {
    UniversalTranslatorConfigScreenBase(Text title) {
        super(title);
    }

    protected abstract boolean handleSelectionClick(double mouseX, double mouseY);

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return handleSelectionClick(mouseX, mouseY)
                || super.mouseClicked(mouseX, mouseY, button);
    }
}
