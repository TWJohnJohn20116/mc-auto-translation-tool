package org.universaltranslator.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

/**
 * 1.19 through 1.19.2 half of the settings screen.
 *
 * <p>The body lives once in {@code platforms/fabric/shared/screens-shared}. This class supplies only
 * the parts that differ per generation: the {@link MatrixStack} render surface, the button factory
 * and the classic mouse callbacks.
 *
 * <p>{@code ButtonWidget} is not imported here on purpose. This tree carries a package-local shim
 * that restores the newer builder shape, and an explicit import would shadow it.
 */
abstract class UniversalTranslatorConfigScreenBase extends UniversalTranslatorSettingsScreen {
    static final Text SETTINGS_TITLE = Text.translatable("screen.universal_translator.settings.title");

    UniversalTranslatorConfigScreenBase(Screen parent, FabricConfig config) {
        super(parent, config, SETTINGS_TITLE);
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
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC closes the overlay, not the whole settings screen.
        return handleSelectionKey(keyCode) || super.keyPressed(keyCode, scanCode, modifiers);
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
        fill((MatrixStack) canvas, x1, y1, x2, y2, color);
    }

    @Override
    protected void drawCentered(Object canvas, TextRenderer renderer, String text, int centerX, int y,
            int color) {
        renderer.drawWithShadow((MatrixStack) canvas, Text.literal(text),
                centerX - renderer.getWidth(text) / 2, y, color);
    }

    @Override
    protected void drawLeft(Object canvas, TextRenderer renderer, String text, int x, int y, int color) {
        renderer.drawWithShadow((MatrixStack) canvas, Text.literal(text), x, y, color);
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
    protected TextFieldWidget newTextField(int x, int y, int width, int height,
            String initialText) {
        return new TextFieldWidget(textRenderer(), x, y, width, height,
                Text.literal(initialText));
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
