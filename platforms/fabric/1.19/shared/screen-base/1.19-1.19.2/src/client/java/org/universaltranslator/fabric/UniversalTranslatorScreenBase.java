package org.universaltranslator.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Per-generation half of the 1.19 screens, for 1.19 through 1.19.2.
 *
 * <p>The three screens live once in {@code 1.19/shared/screens/1.19-1.19.4}. This variant needs no
 * seam of its own beyond the button factory: {@code drawCenteredText} is still inherited here, and
 * {@code ButtonWidget} comes from {@code family/1.16.5-1.19.2}, which restores the newer builder
 * shape on these versions.
 *
 * <p>{@link #of} exists because the later variant cannot expose a static method named
 * {@code builder}: 1.19.3 added one to the vanilla class, and a subclass static method may not hide
 * it with an unrelated return type. The screens therefore call one shared name.
 */
abstract class UniversalTranslatorScreenBase extends Screen {
    UniversalTranslatorScreenBase(Text title) {
        super(title);
    }

    protected static ButtonWidget.Builder of(Text message, ButtonWidget.PressAction action) {
        return ButtonWidget.builder(message, action);
    }
}
