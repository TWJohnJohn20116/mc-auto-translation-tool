package org.universaltranslator.fabric;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;

/**
 * Per-generation gaps of the settings screen for Minecraft 1.18.2.
 *
 * <p>Everything else lives one level up in {@code screens/1.16-1.18}, which the whole 1.16 to 1.18.2
 * range shares. Only what genuinely moved inside that range stays here: {@code onClose} became {@code close} and {@code isPauseScreen} became {@code shouldPause}.
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
        return addDrawableChild(button);
    }

    @Override
    protected TextFieldWidget addSettingField(TextFieldWidget field) {
        return addDrawableChild(field);
    }

    @Override
    public void close() {
        onBack();
    }

    @Override
    public boolean shouldPause() {
        // 1.18.2 is the one generation whose base answered true here; kept as it was so this
        // refactor does not change whether the game keeps running behind the screen.
        return true;
    }
    @Override
    protected void setLabel(net.minecraft.client.gui.widget.ButtonWidget button, String label) {
        button.setMessage(new LiteralText(label));
    }

}