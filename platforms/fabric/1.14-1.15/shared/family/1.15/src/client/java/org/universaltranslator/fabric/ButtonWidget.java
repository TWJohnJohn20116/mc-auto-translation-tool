package org.universaltranslator.fabric;

import net.minecraft.text.Text;

/** Restores the newer builder shape on Minecraft versions that only expose string button constructors. */
public final class ButtonWidget extends net.minecraft.client.gui.widget.ButtonWidget {
    private ButtonWidget(int x, int y, int width, int height, Text message, PressAction action) {
        super(x, y, width, height, message.getString(), action);
    }

    public static Builder builder(Text message, PressAction action) {
        return new Builder(message, action);
    }

    public void setMessage(Text message) {
        super.setMessage(message.getString());
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
