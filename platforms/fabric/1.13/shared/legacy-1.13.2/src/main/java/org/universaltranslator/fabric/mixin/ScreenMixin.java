package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.SignEditScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.TextKind;
import org.universaltranslator.fabric.RenderedTextBridge;
import org.universaltranslator.fabric.TranslationRenderContext;

import java.util.List;

@Mixin(Screen.class)
abstract class ScreenMixin {
    @ModifyVariable(
            method = "renderTooltip(Ljava/util/List;II)V",
            at = @At("HEAD"),
            argsOnly = true,
            require = 0)
    private List<String> universalTranslator$translateTooltip(List<String> lines) {
        return RenderedTextBridge.translateTooltipLines(lines);
    }

    @Inject(method = "renderTooltip(Ljava/util/List;II)V", at = @At("HEAD"), require = 0)
    private void universalTranslator$pushTooltip(List<String> lines, int x, int y, CallbackInfo callback) {
        TranslationRenderContext.push(TextKind.TOOLTIP);
    }

    @Inject(method = "renderTooltip(Ljava/util/List;II)V", at = @At("RETURN"), require = 0)
    private void universalTranslator$popTooltip(List<String> lines, int x, int y, CallbackInfo callback) {
        TranslationRenderContext.pop();
    }

    @Inject(method = "render(IIF)V", at = @At("HEAD"), require = 0)
    private void universalTranslator$pushSignInput(int mouseX, int mouseY, float delta, CallbackInfo callback) {
        if ((Object) this instanceof SignEditScreen) {
            TranslationRenderContext.pushTextInput();
        }
    }

    @Inject(method = "render(IIF)V", at = @At("RETURN"), require = 0)
    private void universalTranslator$popSignInput(int mouseX, int mouseY, float delta, CallbackInfo callback) {
        if ((Object) this instanceof SignEditScreen) {
            TranslationRenderContext.popTextInput();
        }
    }
}
