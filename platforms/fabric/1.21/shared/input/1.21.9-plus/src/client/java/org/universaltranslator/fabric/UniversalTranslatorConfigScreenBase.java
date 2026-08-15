package org.universaltranslator.fabric;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Bridges the event-object mouse callback introduced in Minecraft 1.21.9. */
abstract class UniversalTranslatorConfigScreenBase extends Screen {
    UniversalTranslatorConfigScreenBase(Text title) {
        super(title);
    }

    protected abstract boolean handleSelectionClick(double mouseX, double mouseY);

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        return handleSelectionClick(click.x(), click.y())
                || super.mouseClicked(click, doubleClick);
    }
}
