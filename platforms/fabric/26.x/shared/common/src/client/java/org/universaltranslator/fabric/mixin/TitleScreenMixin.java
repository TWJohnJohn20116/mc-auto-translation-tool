package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.fabric.UniversalTranslatorFabricClient;
import org.universaltranslator.fabric.HomeButtonPlacement;

/** Adds a single title-screen entry that opens translation settings. */
@Mixin(TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void universalTranslator$addQuickSettings(CallbackInfo callback) {
        addRenderableWidget(Button.builder(
                Component.translatable("screen.universal_translator.home.open"),
                button -> UniversalTranslatorFabricClient.openSettingsScreen((Screen) (Object) this))
                .bounds(HomeButtonPlacement.x(this.width), HomeButtonPlacement.MARGIN_Y,
                        HomeButtonPlacement.WIDTH, HomeButtonPlacement.HEIGHT).build());
    }
}
