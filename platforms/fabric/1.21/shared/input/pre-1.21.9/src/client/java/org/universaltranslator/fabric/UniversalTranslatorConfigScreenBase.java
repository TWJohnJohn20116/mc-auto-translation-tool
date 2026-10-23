package org.universaltranslator.fabric;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * 1.20.1 through 1.21.8 half of the settings screen.
 *
 * <p>The body lives once in {@code platforms/fabric/shared/screens-shared}. This class supplies the
 * {@link DrawContext} render surface, the vanilla button factory and the classic mouse callbacks.
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
