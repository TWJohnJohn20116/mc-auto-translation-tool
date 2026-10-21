package org.universaltranslator.forge;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Button construction seam for the shared settings screens.
 *
 * <p>1.18.2 and 1.19.2 build buttons through the widget constructor; the builder form only appeared
 * in 1.19.3 and the constructor became inaccessible with it, so the shared sources go through this
 * chain instead of naming either. The shape matches {@code Button.builder(..).bounds(..).build()}
 * so the same call sites serve both eras.
 */
final class ButtonSpec {
    private final Component message;
    private final Press action;
    private int x;
    private int y;
    private int width;
    private int height;

    private ButtonSpec(Component message, Press action) {
        this.message = message;
        this.action = action;
    }

    static ButtonSpec of(Component message, Press action) {
        return new ButtonSpec(message, action);
    }

    ButtonSpec bounds(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        return this;
    }

    Button build() {
        return new Button(x, y, width, height, message, button -> action.press(button));
    }

    /** Version-neutral stand-in for {@code Button.OnPress} and the older {@code Button.IPressable}. */
    interface Press {
        void press(Button button);
    }
}