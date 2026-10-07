package org.universaltranslator.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Bridges the classic mouse callback used through Minecraft 1.21.8. */
abstract class UniversalTranslatorConfigScreenBase extends Screen {
    UniversalTranslatorConfigScreenBase(Text title) {
        super(title);
    }

    protected abstract boolean handleSelectionClick(double mouseX, double mouseY);

    protected abstract boolean handleDragStart(double mouseX, double mouseY);

    protected abstract boolean handleDragMove(double mouseX, double mouseY);

    protected abstract boolean handleDragEnd();

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return handleDragStart(mouseX, mouseY)
                || handleSelectionClick(mouseX, mouseY)
                || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        return handleDragMove(mouseX, mouseY)
                || super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return handleDragEnd()
                || super.mouseReleased(mouseX, mouseY, button);
    }
}
