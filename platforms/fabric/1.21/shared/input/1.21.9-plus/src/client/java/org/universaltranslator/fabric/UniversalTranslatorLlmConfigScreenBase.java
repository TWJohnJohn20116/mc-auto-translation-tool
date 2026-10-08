package org.universaltranslator.fabric;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Bridges the event-object mouse callback introduced in Minecraft 1.21.9. */
abstract class UniversalTranslatorLlmConfigScreenBase extends Screen {
    UniversalTranslatorLlmConfigScreenBase(Text title) {
        super(title);
    }

    /**
     * Version-neutral seam for the model picker, exactly like
     * {@link UniversalTranslatorConfigScreenBase#handleSelectionClick(double, double)}: the shared
     * screen owns the picker logic and each version layer bridges its own mouse callback into it.
     *
     * @return {@code true} when the open picker consumed the click
     */
    protected abstract boolean handleModelListClick(double mouseX, double mouseY);

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        return handleModelListClick(click.x(), click.y())
                || super.mouseClicked(click, doubleClick);
    }
}
