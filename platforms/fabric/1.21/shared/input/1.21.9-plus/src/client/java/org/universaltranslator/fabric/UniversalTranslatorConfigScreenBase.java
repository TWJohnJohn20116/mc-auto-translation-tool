package org.universaltranslator.fabric;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * 1.21.9 through 1.21.11 half of the settings screen.
 *
 * <p>Same seam as the earlier generation, but the mouse callbacks take the event object introduced
 * in Minecraft 1.21.9.
 */
abstract class UniversalTranslatorConfigScreenBase extends UniversalTranslatorSettingsScreen {
    UniversalTranslatorConfigScreenBase(Screen parent, FabricConfig config) {
        super(parent, config);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderChrome(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        renderOverlay(context, mouseX, mouseY, delta);
    }

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

    @Override
    protected SettingsButtonBuilder of(Text message, ButtonWidget.PressAction action) {
        ButtonWidget.Builder builder = ButtonWidget.builder(message, action);
        return new SettingsButtonBuilder() {
            @Override
            public SettingsButtonBuilder dimensions(int x, int y, int width, int height) {
                builder.dimensions(x, y, width, height);
                return this;
            }

            @Override
            public ButtonWidget build() {
                return builder.build();
            }
        };
    }

    @Override
    protected void fillRect(Object canvas, int x1, int y1, int x2, int y2, int color) {
        ((DrawContext) canvas).fill(x1, y1, x2, y2, color);
    }

    @Override
    protected void drawCentered(Object canvas, TextRenderer renderer, Text text, int centerX, int y,
            int color) {
        ((DrawContext) canvas).drawCenteredTextWithShadow(renderer, text, centerX, y, color);
    }

    @Override
    protected void drawLeft(Object canvas, TextRenderer renderer, Text text, int x, int y, int color) {
        ((DrawContext) canvas).drawTextWithShadow(renderer, text, x, y, color);
    }
}
