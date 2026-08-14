package org.universaltranslator.forge.mixin;

import net.minecraft.client.gui.screen.MainMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.HomeQuickSettingsState;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.forge.ForgeTranslationRuntime;

/** Adds compact, immediately applied translation controls to Minecraft's title screen. */
@Mixin(MainMenuScreen.class)
abstract class TitleScreenMixin extends Screen {
    @Unique private Button universalTranslator$enabled;
    @Unique private Button universalTranslator$vanilla;
    @Unique private Button universalTranslator$target;

    protected TitleScreenMixin(ITextComponent title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void universalTranslator$addQuickSettings(CallbackInfo callback) {
        int x = Math.max(4, this.width - 136);
        universalTranslator$enabled = addButton(new Button(
                x, 6, 132, 20, StringTextComponent.EMPTY,
                button -> universalTranslator$change(0)));
        universalTranslator$vanilla = addButton(new Button(
                x, 29, 132, 20, StringTextComponent.EMPTY,
                button -> universalTranslator$change(1)));
        universalTranslator$target = addButton(new Button(
                x, 52, 132, 20, StringTextComponent.EMPTY,
                button -> universalTranslator$change(2)));
        universalTranslator$refresh(ForgeTranslationRuntime.homeSettings());
    }

    @Unique
    private void universalTranslator$change(int action) {
        try {
            HomeQuickSettingsState state;
            if (action == 0) {
                state = ForgeTranslationRuntime.toggleHomeEnabled();
            } else if (action == 1) {
                state = ForgeTranslationRuntime.toggleHomeVanilla();
            } else {
                state = ForgeTranslationRuntime.cycleHomeTargetLanguage();
            }
            universalTranslator$refresh(state);
        } catch (Exception exception) {
            System.err.println("[MC Auto Translation Tool] Could not update title-screen setting: " + exception);
            universalTranslator$refresh(ForgeTranslationRuntime.homeSettings());
        }
    }

    @Unique
    private void universalTranslator$refresh(HomeQuickSettingsState state) {
        universalTranslator$enabled.setMessage(new TranslationTextComponent(
                "screen.universal_translator.home.enabled",
                new TranslationTextComponent(state.isEnabled()
                        ? "value.universal_translator.enabled"
                        : "value.universal_translator.disabled")));
        universalTranslator$vanilla.setMessage(new TranslationTextComponent(
                "screen.universal_translator.home.vanilla",
                new TranslationTextComponent(state.isTranslateVanilla()
                        ? "value.universal_translator.enabled"
                        : "value.universal_translator.disabled")));
        universalTranslator$target.setMessage(new TranslationTextComponent(
                "screen.universal_translator.home.target",
                new StringTextComponent(TargetLanguage.displayName(state.getTargetLanguage()))));
    }
}
