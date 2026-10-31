package org.universaltranslator.forge.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.forge.ForgeMixinDiagnostics;
import org.universaltranslator.forge.ForgeTranslationRuntime;
import org.universaltranslator.forge.HomeButtonGeometry;

/** Adds a single title-screen entry that opens translation settings. */
@Mixin(TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    // Optional: a convenience entry point, not part of the render pipeline. require = 0 is explicit
    // because the shared injector config sets defaultRequire: 0; the first run is reported so a
    // skipped hook shows up in the debug log.
    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void universalTranslator$addQuickSettings(CallbackInfo callback) {
        ForgeMixinDiagnostics.optionalHookActive(
                ForgeMixinDiagnostics.TITLE_SCREEN_ENTRY, "title-screen-entry");
        int x = HomeButtonGeometry.x(this.width);
        addRenderableWidget(new Button(
                x, HomeButtonGeometry.MARGIN_Y, HomeButtonGeometry.WIDTH, HomeButtonGeometry.HEIGHT,
                Component.translatable("screen.universal_translator.home.open"),
                button -> ForgeTranslationRuntime.openSettingsScreen((Screen) (Object) this)));
    }
}
