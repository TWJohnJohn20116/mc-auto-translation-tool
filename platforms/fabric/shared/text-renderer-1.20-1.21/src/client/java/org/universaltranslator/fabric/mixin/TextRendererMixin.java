package org.universaltranslator.fabric.mixin;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.universaltranslator.fabric.RenderedTextBridge;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures world-space text such as nameplates, holograms, signs and display entities.
 *
 * <p>The measure hook ({@code getWidth(String)}) is {@code require = 1}: GUI layout asks it about
 * every label it places, and without it a translated line is drawn at the width of the original, so
 * the feature looks broken in a way that is hard to attribute. The world-text hooks are
 * {@code require = 0}: their descriptors differ between the 1.20, 1.21 and 26.x generations (the
 * matrix type, the layer enum, the extra {@code boolean}), and losing one of them costs a surface
 * rather than the launch.
 *
 * <p>Each optional hook reports through {@link RenderedTextBridge#surface} the first time it fires,
 * so the debug log can answer "did this version bind it at all", which the silence could not.
 */
@Mixin(TextRenderer.class)
abstract class TextRendererMixin {
    private static final AtomicBoolean PREPARED_STRING_SURFACE = new AtomicBoolean();
    private static final AtomicBoolean PREPARED_ORDERED_SURFACE = new AtomicBoolean();
    private static final AtomicBoolean MEASURED_STRING_SURFACE = new AtomicBoolean();
    private static final AtomicBoolean MEASURED_VISITABLE_SURFACE = new AtomicBoolean();
    private static final AtomicBoolean MEASURED_ORDERED_SURFACE = new AtomicBoolean();

    @ModifyVariable(
            method = "drawInternal(Ljava/lang/String;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;IIZ)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private String universalTranslator$translatePreparedString(String text) {
        RenderedTextBridge.surface("text-renderer-string", PREPARED_STRING_SURFACE);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "drawInternal(Lnet/minecraft/text/OrderedText;FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/render/VertexConsumerProvider;Lnet/minecraft/client/font/TextRenderer$TextLayerType;II)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private OrderedText universalTranslator$translatePreparedOrderedText(OrderedText text) {
        RenderedTextBridge.surface("text-renderer-ordered", PREPARED_ORDERED_SURFACE);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(method = "getWidth(Ljava/lang/String;)I", at = @At("HEAD"), argsOnly = true, require = 1)
    private String universalTranslator$translateMeasuredString(String text) {
        RenderedTextBridge.surface("text-renderer-width-string", MEASURED_STRING_SURFACE);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "getWidth(Lnet/minecraft/text/StringVisitable;)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private StringVisitable universalTranslator$translateMeasuredText(StringVisitable text) {
        RenderedTextBridge.surface("text-renderer-width-visitable", MEASURED_VISITABLE_SURFACE);
        return RenderedTextBridge.translate(text);
    }

    @ModifyVariable(
            method = "getWidth(Lnet/minecraft/text/OrderedText;)I",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private OrderedText universalTranslator$translateMeasuredOrderedText(OrderedText text) {
        RenderedTextBridge.surface("text-renderer-width-ordered", MEASURED_ORDERED_SURFACE);
        return RenderedTextBridge.translate(text);
    }
}
