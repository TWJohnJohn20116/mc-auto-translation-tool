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

    protected abstract boolean handleDragStart(double mouseX, double mouseY);

    protected abstract boolean handleDragMove(double mouseX, double mouseY);

    protected abstract boolean handleDragEnd();

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        return handleDragStart(click.x(), click.y())
                || handleSelectionClick(click.x(), click.y())
                || super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        return handleDragMove(click.x(), click.y())
                || super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        return handleDragEnd()
                || super.mouseReleased(click);
    }
}
