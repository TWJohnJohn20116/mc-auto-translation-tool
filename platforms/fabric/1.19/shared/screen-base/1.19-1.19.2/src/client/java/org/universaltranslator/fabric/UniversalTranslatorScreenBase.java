package org.universaltranslator.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * Per-generation half of the 1.19 screens, for 1.19 through 1.19.2.
 *
 * <p>The three screens live once in {@code 1.19/shared/screens/1.19-1.19.4}. This variant needs no
 * seam of its own: {@code drawCenteredText} is still inherited here, and {@code ButtonWidget} comes
 * from {@code family/1.16.5-1.19.2}, which restores the newer builder shape on these versions.
 */
abstract class UniversalTranslatorScreenBase extends Screen {
    UniversalTranslatorScreenBase(Text title) {
        super(title);
    }
}
