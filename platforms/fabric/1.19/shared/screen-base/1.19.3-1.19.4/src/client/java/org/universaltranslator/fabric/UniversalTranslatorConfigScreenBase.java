package org.universaltranslator.fabric;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

/**
 * 1.19.3 and 1.19.4 half of the settings screen.
 *
 * <p>The body lives once in {@code platforms/fabric/shared/screens-shared}. This class supplies only
 * the parts that differ per generation: the {@link MatrixStack} render surface, the button factory
 * and the classic mouse callbacks.
 *
 * <p>{@code ButtonWidget} is not imported here on purpose. This tree carries a package-local shim
 * that restores the newer builder shape, and an explicit import would shadow it. 1.19.3 added a
 * static uilder to the vanilla class, so the shim exposes the factory as of instead.
 */
abstract class UniversalTranslatorConfigScreenBase extends UniversalTranslatorSettingsScreen {
    UniversalTranslatorConfigScreenBase(Screen parent, FabricConfig config) {
        super(parent, config);
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        renderChrome(matrices, mouseX, mouseY, delta);
        super.render(matrices, mouseX, mouseY, delta);
        renderOverlay(matrices, mouseX, mouseY, delta);
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
        ButtonWidget.Builder builder = ButtonWidget.of(message, action);
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
        fill((MatrixStack) canvas, x1, y1, x2, y2, color);
    }

    @Override
    protected void drawCentered(Object canvas, TextRenderer renderer, Text text, int centerX, int y,
            int color) {
        renderer.drawWithShadow((MatrixStack) canvas, text,
                centerX - renderer.getWidth(text) / 2.0f, y, color);
    }

    @Override
    protected void drawLeft(Object canvas, TextRenderer renderer, Text text, int x, int y, int color) {
        renderer.drawWithShadow((MatrixStack) canvas, text, x, y, color);
    }
}
