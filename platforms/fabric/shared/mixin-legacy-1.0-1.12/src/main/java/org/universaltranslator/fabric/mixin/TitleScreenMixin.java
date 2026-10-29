package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.fabric.HomeButtonPlacement;
import org.universaltranslator.fabric.UniversalTranslatorFabricClient;
import org.universaltranslator.fabric.OrnitheClientAccess;

@Mixin(TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    @Unique
    private static final int UT_OPEN = 31700;

    /**
     * Both spellings of the screen's init hook.
     *
     * <p>This layer covers 1.0-1.12, where the hook is {@code initGui()}; {@code init()} only exists
     * from 1.13 on, and this layer is never compiled against 1.13. Listing only the newer name with
     * {@code require = 0} meant the whole layer's title-screen entry silently did not exist — the
     * button was never added, and nothing failed loudly enough to say so.
     */
    @Inject(method = {"initGui()V", "init()V"}, at = @At("TAIL"), require = 0)
    private void universalTranslator$addQuickSettings(CallbackInfo callback) {
        this.buttons.add(new ButtonWidget(UT_OPEN,
                HomeButtonPlacement.x(this.width), HomeButtonPlacement.MARGIN_Y,
                HomeButtonPlacement.WIDTH, HomeButtonPlacement.HEIGHT,
                OrnitheClientAccess.tr("screen.universal_translator.home.open")));
    }

    /**
     * Both spellings of the button callback: {@code actionPerformed} up to 1.12, {@code buttonClicked}
     * from 1.13 on.
     */
    @Inject(
            method = {
                    "actionPerformed(Lnet/minecraft/client/gui/widget/ButtonWidget;)V",
                    "buttonClicked(Lnet/minecraft/client/gui/widget/ButtonWidget;)V"
            },
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
