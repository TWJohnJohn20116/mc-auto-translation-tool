package org.universaltranslator.fabric;

import net.minecraft.text.Text;

/**
 * Restores the newer builder shape so the shared 1.19 screens can name {@code ButtonWidget}
 * without an import on every generation.
 *
 * <p>1.19.3 added a {@code NarrationSupplier} parameter to the button constructor, so this copy
 * passes the inherited {@code DEFAULT_NARRATION_SUPPLIER} (protected, therefore reachable from a
 * subclass). The 1.19 through 1.19.2 builds keep using the copy in
 * {@code legacy/shared/family/1.16.5-1.19.2}, which has the older six-argument constructor.
 */
public final class ButtonWidget extends net.minecraft.client.gui.widget.ButtonWidget {
    private ButtonWidget(int x, int y, int width, int height, Text message, PressAction action) {
        super(x, y, width, height, message, action, DEFAULT_NARRATION_SUPPLIER);
    }

    public static Builder of(Text message, PressAction action) {
        return new Builder(message, action);
    }

    public static final class Builder {
        private final Text message;
        private final PressAction action;
        private int x;
        private int y;
        private int width;
        private int height;

        private Builder(Text message, PressAction action) {
            this.message = message;
            this.action = action;
        }

        public Builder dimensions(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            return this;
        }

        public ButtonWidget build() {
            return new ButtonWidget(x, y, width, height, message, action);
        }
    }
}
