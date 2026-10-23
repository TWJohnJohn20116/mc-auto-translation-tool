package org.universaltranslator.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;

/**
 * Per-generation gaps of the settings screen for Minecraft 1.16.5.
 *
 * <p>Everything else lives one level up in {@code screens/1.16-1.18}, which the whole 1.16 to 1.18.2
 * range shares. Only what genuinely moved inside that range stays here: same as 1.16.0 to 1.16.4, except that {@code AbstractButtonWidget} was renamed to {@code ClickableWidget}.
 *
 * <p>{@code ButtonWidget} is not imported on purpose: this tree carries a package-local shim that
 * restores the newer builder shape, and an explicit import would shadow it.
 */
abstract class UniversalTranslatorConfigScreenSeams extends UniversalTranslatorSettingsScreen {
    UniversalTranslatorConfigScreenSeams(Screen parent, FabricConfig config, Text title) {
        super(parent, config, title);
    }

    @Override
    protected net.minecraft.client.gui.widget.ButtonWidget addSettingButton(
            net.minecraft.client.gui.widget.ButtonWidget button) {
        return addButton(button);
    }

    @Override
    protected TextFieldWidget addSettingField(TextFieldWidget field) {
        return addButton(field);
    }

    @Override
    public void onClose() {
        onBack();
    }

    public boolean isPauseScreen() {
        return false;
    }
    @Override
    protected void showScreen(Screen screen) {
        if (minecraftClient() != null) {
            minecraftClient().openScreen(screen);
        }
    }

    @Override
    protected void setLabel(net.minecraft.client.gui.widget.ButtonWidget button, String label) {
        button.setMessage(new LiteralText(label));
    }

}