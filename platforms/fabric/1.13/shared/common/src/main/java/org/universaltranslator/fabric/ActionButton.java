package org.universaltranslator.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

/** Concrete 1.13 ButtonWidget; vanilla's class is abstract after the LWJGL3 GUI rewrite. */
public final class ActionButton extends ButtonWidget {
    private final Runnable onPress;

    public ActionButton(int id, int x, int y, int width, int height, String message) {
        this(id, x, y, width, height, message, null);
    }

    public ActionButton(int id, int x, int y, int width, int height, String message, Runnable onPress) {
        super(id, x, y, width, height, message);
        this.onPress = onPress;
    }

    @Override
    public void click(double mouseX, double mouseY) {
        if (onPress != null) {
            onPress.run();
            return;
        }
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof UniversalTranslatorConfigScreen) {
            ((UniversalTranslatorConfigScreen) screen).buttonClicked(this);
        } else if (screen instanceof UniversalTranslatorDiagnosticsScreen) {
            ((UniversalTranslatorDiagnosticsScreen) screen).buttonClicked(this);
        } else if (screen instanceof UniversalTranslatorLlmConfigScreen) {
            ((UniversalTranslatorLlmConfigScreen) screen).buttonClicked(this);
        }
    }
}
