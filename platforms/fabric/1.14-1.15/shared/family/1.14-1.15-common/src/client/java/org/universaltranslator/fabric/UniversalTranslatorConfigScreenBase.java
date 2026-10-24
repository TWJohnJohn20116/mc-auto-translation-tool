package org.universaltranslator.fabric;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;

/**
 * 1.14 and 1.15 half of the settings screen.
 *
 * <p>The body lives once in {@code platforms/fabric/shared/screens-shared}. This class supplies the
 * parts that differ from 1.16 onwards; within 1.14 and 1.15 nothing differs, so this one class
 * serves all eight builds: the text factories predate {@code Text.literal}, the render callback
 * still takes coordinates instead of a matrix stack (which is why {@code canvas} carries nothing
 * here), widgets are registered through {@code addButton}, screens are changed through
 * {@code MinecraftClient#openScreen}, and the exit and pause hooks are still {@code onClose} and
 * {@code isPauseScreen}.
 *
 * <p>{@code ButtonWidget} is not imported on purpose: this tree carries a package-local shim that
 * restores the newer builder shape, and an explicit import would shadow it.
 */
abstract class UniversalTranslatorConfigScreenBase extends UniversalTranslatorSettingsScreen {
    static final Text SETTINGS_TITLE =
            new TranslatableText("screen.universal_translator.settings.title");

    UniversalTranslatorConfigScreenBase(Screen parent, FabricConfig config) {
        super(parent, config, SETTINGS_TITLE);
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        // 1.14 and 1.15 draw straight onto the screen; there is no canvas object to pass around.
        renderChrome(null, mouseX, mouseY, delta);
        super.render(mouseX, mouseY, delta);
        renderOverlay(null, mouseX, mouseY, delta);
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
        ButtonWidget.Builder builder = ButtonWidget.builder(new LiteralText(message), action);
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
        fill(x1, y1, x2, y2, color);
    }

    @Override
    protected void drawCentered(Object canvas, TextRenderer renderer, String text, int centerX, int y,
            int color) {
        drawCenteredString(renderer, text, centerX, y, color);
    }

    @Override
    protected void drawLeft(Object canvas, TextRenderer renderer, String text, int x, int y, int color) {
        drawString(renderer, text, x, y, color);
    }

    @Override
    protected net.minecraft.client.gui.widget.ButtonWidget addSettingButton(
            net.minecraft.client.gui.widget.ButtonWidget button) {
        return addButton(button);
    }

    @Override
    protected net.minecraft.client.gui.widget.TextFieldWidget addSettingField(
            TextFieldWidget field) {
        addButton(field);
        return field;
    }

    @Override
    protected void showScreen(Screen screen) {
        if (minecraftClient() != null) {
            minecraftClient().openScreen(screen);
        }
    }

    @Override
    public void onClose() {
        onBack();
    }

    public boolean isPauseScreen() {
        return false;
    }
    @Override
    protected TextFieldWidget newTextField(int x, int y, int width, int height,
            String initialText) {
        return new TextFieldWidget(textRenderer(), x, y, width, height, initialText);
    }

    @Override
    protected TextRenderer textRenderer() {
        return this.font;
    }

    @Override
    protected String trStr(String key, Object... arguments) {
        return new TranslatableText(key, arguments).getString();
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
        return this.minecraft;
    }

    @Override
    protected void setLabel(net.minecraft.client.gui.widget.ButtonWidget button, String label) {
        button.setMessage(label);
    }

}
