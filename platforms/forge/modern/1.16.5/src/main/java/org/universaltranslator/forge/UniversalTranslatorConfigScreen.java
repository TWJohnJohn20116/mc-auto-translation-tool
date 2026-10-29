package org.universaltranslator.forge;

import com.mojang.blaze3d.matrix.MatrixStack;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.text.ITextComponent;

/** Minecraft 1.16.5 render/input bridge for the shared settings screens. */
final class UniversalTranslatorConfigScreen extends UniversalTranslatorConfigScreenBase {
    UniversalTranslatorConfigScreen(Screen parent, ForgeConfig config) {
        super(parent, config);
    }

    @Override
    public void render(MatrixStack pose, int mouseX, int mouseY, float delta) {
        ForgeScreenCanvas canvas = canvas(pose);
        float opening = renderSettingsBefore(canvas);
        super.render(pose, mouseX, mouseY, delta);
        renderSettingsAfter(canvas, mouseX, mouseY, opening);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return handleDragStart(mouseX, mouseY)
                || handleSelectionClick(mouseX, mouseY)
                || super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (isOverlayOpen()) {
            // ESC closes the overlay, not the whole settings screen; every other key is swallowed so
            // it cannot reach a field hidden behind the overlay.
            handleSelectionKey(keyCode);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        // The overlay is a painted rectangle, not a screen, so the focused field behind it used to
        // collect every character typed over it and Save then wrote them into the configuration.
        return isOverlayOpen() || super.charTyped(chr, modifiers);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        return handleDragMove(mouseX, mouseY)
                || super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return handleDragEnd()
                || super.mouseReleased(mouseX, mouseY, button);
    }

    private ForgeScreenCanvas canvas(MatrixStack pose) {
        return CanvasFactory.create(pose, font);
    }
}

final class UniversalTranslatorDiagnosticsScreen extends UniversalTranslatorDiagnosticsScreenBase {
    UniversalTranslatorDiagnosticsScreen(Screen parent, ForgeConfig config) {
        super(parent, config);
    }

    @Override
    public void render(MatrixStack pose, int mouseX, int mouseY, float delta) {
        renderDiagnostics(CanvasFactory.create(pose, font));
        super.render(pose, mouseX, mouseY, delta);
    }
}

final class UniversalTranslatorLlmConfigScreen extends UniversalTranslatorLlmConfigScreenBase {
    UniversalTranslatorLlmConfigScreen(UniversalTranslatorConfigScreenBase parent,
                                       String endpoint, String model, boolean hasStoredKey) {
        super(parent, endpoint, model, hasStoredKey);
    }

    @Override
    public void render(MatrixStack pose, int mouseX, int mouseY, float delta) {
        renderLlm(CanvasFactory.create(pose, font));
        super.render(pose, mouseX, mouseY, delta);
    }
}

final class CanvasFactory {
    private CanvasFactory() {
    }

    static ForgeScreenCanvas create(MatrixStack pose, FontRenderer font) {
        return new ForgeScreenCanvas() {
            @Override
            public void fill(int left, int top, int right, int bottom, int color) {
                AbstractGui.fill(pose, left, top, right, bottom, color);
            }

            @Override
            public void centered(ITextComponent text, int centerX, int y, int color) {
                // The component overload, not text.getString(): flattening to a string dropped every
                // style the component carried, so a centred title and a left-aligned hint built from
                // the same component were drawn with different colours and weights.
                AbstractGui.drawCenteredString(pose, font, text, centerX, y, color);
            }

            @Override
            public void text(ITextComponent text, int x, int y, int color) {
                font.drawShadow(pose, text, (float) x, (float) y, color);
            }
        };
    }
}
