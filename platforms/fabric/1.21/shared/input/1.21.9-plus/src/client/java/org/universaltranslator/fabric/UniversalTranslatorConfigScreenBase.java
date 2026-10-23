package org.universaltranslator.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * 1.21.9 through 1.21.11 half of the settings screen.
 *
 * <p>Same seam as the earlier generation, but the mouse callbacks take the event object introduced
 * in Minecraft 1.21.9.
 */
abstract class UniversalTranslatorConfigScreenBase extends UniversalTranslatorSettingsScreen {
    static final Text SETTINGS_TITLE = Text.translatable("screen.universal_translator.settings.title");

    UniversalTranslatorConfigScreenBase(Screen parent, FabricConfig config) {
        super(parent, config, SETTINGS_TITLE);
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
    protected SettingsButtonBuilder of(String message, ButtonWidget.PressAction action) {
        ButtonWidget.Builder builder = ButtonWidget.builder(Text.literal(message), action);
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
    protected void drawCentered(Object canvas, TextRenderer renderer, String text, int centerX, int y,
            int color) {
        ((DrawContext) canvas).drawCenteredTextWithShadow(renderer, text, centerX, y, color);
    }

    @Override
    protected void drawLeft(Object canvas, TextRenderer renderer, String text, int x, int y, int color) {
        ((DrawContext) canvas).drawTextWithShadow(renderer, text, x, y, color);
    }

    @Override
    protected net.minecraft.client.gui.widget.ButtonWidget addSettingButton(
            net.minecraft.client.gui.widget.ButtonWidget button) {
        return addDrawableChild(button);
    }

    @Override
    protected TextFieldWidget addSettingField(TextFieldWidget field) {
        return addDrawableChild(field);
    }

    @Override
    protected void showScreen(Screen screen) {
        if (minecraftClient() != null) {
            minecraftClient().setScreen(screen);
        }
    }

    @Override
    public void close() {
        onBack();
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
    @Override
    protected TextRenderer textRenderer() {
        return this.textRenderer;
    }

    @Override
    protected String trStr(String key, Object... arguments) {
        return Text.translatable(key, arguments).getString();
    }

    @Override
    protected String lit(String value) {
        return value;
    }

    @Override
    protected String emptyStr() {
        return "";
    }

    @Override
    protected MinecraftClient minecraftClient() {
        return this.client;
    }

    @Override
    protected void setLabel(net.minecraft.client.gui.widget.ButtonWidget button, String label) {
        button.setMessage(Text.literal(label));
    }

}
