package org.universaltranslator.forge.mixin;

import net.minecraft.client.gui.screen.MainMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.button.Button;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TranslationTextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.forge.ForgeMixinDiagnostics;
import org.universaltranslator.forge.ForgeTranslationRuntime;
import org.universaltranslator.forge.HomeButtonGeometry;

/** Adds a single title-screen entry that opens translation settings. */
@Mixin(MainMenuScreen.class)
abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(ITextComponent title) {
        super(title);
    }

    // Optional: a convenience entry point, not part of the render pipeline.
    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void universalTranslator$addQuickSettings(CallbackInfo callback) {
        ForgeMixinDiagnostics.optionalHookActive(
                ForgeMixinDiagnostics.TITLE_SCREEN_ENTRY, "title-screen-entry");
        int x = HomeButtonGeometry.x(this.width);
        addButton(new Button(
                x, HomeButtonGeometry.MARGIN_Y, HomeButtonGeometry.WIDTH, HomeButtonGeometry.HEIGHT,
                new TranslationTextComponent("screen.universal_translator.home.open"),
                button -> ForgeTranslationRuntime.openSettingsScreen((Screen) (Object) this)));
    }
}
