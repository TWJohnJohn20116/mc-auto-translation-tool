package org.universaltranslator.forge;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/**
 * Button construction seam for the shared settings screens.
 *
 * <p>1.20.1 and 1.21 build buttons through {@code Button.builder(..)}; 1.18.2 and 1.19.2 predate it
 * and use the widget constructor, so the shared sources go through this chain instead of naming
 * either. The shape matches the builder call exactly, so the same call sites serve both eras.
 */
final class ButtonSpec {
    private final Button.Builder delegate;

    private ButtonSpec(Button.Builder delegate) {
        this.delegate = delegate;
    }

    static ButtonSpec of(Component message, Press action) {
        return new ButtonSpec(Button.builder(message, button -> action.press(button)));
    }

    ButtonSpec bounds(int x, int y, int width, int height) {
        delegate.bounds(x, y, width, height);
        return this;
    }

    Button build() {
        return delegate.build();
    }

    /** Version-neutral stand-in for {@code Button.OnPress} and the older {@code Button.IPressable}. */
    interface Press {
        void press(Button button);
    }
}