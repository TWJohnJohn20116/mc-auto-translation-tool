package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.HomeQuickSettingsState;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.fabric.FabricTranslationRuntime;

/** Adds compact, immediately applied translation controls to Minecraft's title screen. */
@Mixin(TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    @Unique private Button universalTranslator$enabled;
    @Unique private Button universalTranslator$vanilla;
    @Unique private Button universalTranslator$target;

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void universalTranslator$addQuickSettings(CallbackInfo callback) {
        int x = Math.max(4, this.width - 136);
        universalTranslator$enabled = addRenderableWidget(Button.builder(
                Component.empty(), button -> universalTranslator$change(0))
                .bounds(x, 6, 132, 20).build());
        universalTranslator$vanilla = addRenderableWidget(Button.builder(
                Component.empty(), button -> universalTranslator$change(1))
                .bounds(x, 29, 132, 20).build());
        universalTranslator$target = addRenderableWidget(Button.builder(
                Component.empty(), button -> universalTranslator$change(2))
                .bounds(x, 52, 132, 20).build());
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
        universalTranslator$enabled.setMessage(Component.translatable(
                "screen.universal_translator.home.enabled",
                Component.translatable(state.isEnabled()
                        ? "value.universal_translator.enabled"
                        : "value.universal_translator.disabled")));
        universalTranslator$vanilla.setMessage(Component.translatable(
                "screen.universal_translator.home.vanilla",
                Component.translatable(state.isTranslateVanilla()
                        ? "value.universal_translator.enabled"
                        : "value.universal_translator.disabled")));
        universalTranslator$target.setMessage(Component.translatable(
                "screen.universal_translator.home.target",
                Component.literal(TargetLanguage.displayName(state.getTargetLanguage()))));
    }
}
