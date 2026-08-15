package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.HomeQuickSettingsState;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.fabric.ButtonWidget;
import org.universaltranslator.fabric.FabricTranslationRuntime;

/** Adds compact, immediately applied translation controls to Minecraft's title screen. */
@Mixin(TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    @Unique private ButtonWidget universalTranslator$enabled;
    @Unique private ButtonWidget universalTranslator$vanilla;
    @Unique private ButtonWidget universalTranslator$target;

    protected TitleScreenMixin(Text title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void universalTranslator$addQuickSettings(CallbackInfo callback) {
        int x = Math.max(4, this.width - 136);
        universalTranslator$enabled = addDrawableChild(ButtonWidget.builder(
                new LiteralText(""), button -> universalTranslator$change(0))
                .dimensions(x, 6, 132, 20).build());
        universalTranslator$vanilla = addDrawableChild(ButtonWidget.builder(
                new LiteralText(""), button -> universalTranslator$change(1))
                .dimensions(x, 29, 132, 20).build());
        universalTranslator$target = addDrawableChild(ButtonWidget.builder(
                new LiteralText(""), button -> universalTranslator$change(2))
                .dimensions(x, 52, 132, 20).build());
        universalTranslator$refresh(FabricTranslationRuntime.homeSettings());
    }

    @Unique
    private void universalTranslator$change(int action) {
        try {
            HomeQuickSettingsState state;
            if (action == 0) {
                state = FabricTranslationRuntime.toggleHomeEnabled();
            } else if (action == 1) {
                state = FabricTranslationRuntime.toggleHomeVanilla();
            } else {
                state = FabricTranslationRuntime.cycleHomeTargetLanguage();
            }
            universalTranslator$refresh(state);
        } catch (Exception exception) {
            System.err.println("[MC Auto Translation Tool] Could not update title-screen setting: " + exception);
            universalTranslator$refresh(FabricTranslationRuntime.homeSettings());
        }
    }

    @Unique
    private void universalTranslator$refresh(HomeQuickSettingsState state) {
        universalTranslator$enabled.setMessage(new TranslatableText(
                "screen.universal_translator.home.enabled",
                new TranslatableText(state.isEnabled()
                        ? "value.universal_translator.enabled"
                        : "value.universal_translator.disabled")));
        universalTranslator$vanilla.setMessage(new TranslatableText(
                "screen.universal_translator.home.vanilla",
                new TranslatableText(state.isTranslateVanilla()
                        ? "value.universal_translator.enabled"
                        : "value.universal_translator.disabled")));
        universalTranslator$target.setMessage(new TranslatableText(
                "screen.universal_translator.home.target",
                new LiteralText(TargetLanguage.displayName(state.getTargetLanguage()))));
    }
}
