package org.universaltranslator.forge.mixin;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.forge.ForgeTranslationRuntime;

/** Adds a single title-screen entry that opens translation settings. */
@Mixin(TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void universalTranslator$addQuickSettings(CallbackInfo callback) {
        int x = Math.max(4, this.width - 136);
        addRenderableWidget(new Button(
                x, 6, 132, 20, new TranslatableComponent("screen.universal_translator.home.open"),
                button -> ForgeTranslationRuntime.openSettingsScreen((Screen) (Object) this)));
    }
}
