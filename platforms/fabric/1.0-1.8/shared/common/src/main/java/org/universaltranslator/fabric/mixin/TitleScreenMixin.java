package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.fabric.UniversalTranslatorFabricClient;
import org.universaltranslator.fabric.OrnitheClientAccess;

@Mixin(TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    @Unique
    private static final int UT_OPEN = 31700;

    @Inject(method = "init()V", at = @At("TAIL"), require = 0)
    private void universalTranslator$addQuickSettings(CallbackInfo callback) {
        int x = Math.max(4, this.width - 136);
        this.buttons.add(new ButtonWidget(UT_OPEN, x, 6, 132, 20,
                OrnitheClientAccess.tr("screen.universal_translator.home.open")));
    }

    @Inject(
            method = "buttonClicked(Lnet/minecraft/client/gui/widget/ButtonWidget;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0)
    private void universalTranslator$onButton(ButtonWidget button, CallbackInfo callback) {
        if (button.id != UT_OPEN) {
            return;
        }
        callback.cancel();
        UniversalTranslatorFabricClient.openSettingsScreen(this);
    }
}
