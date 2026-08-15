package org.universaltranslator.forge;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Minecraft 1.21.0-1.21.8 render/input bridge for the shared settings screens. */
final class UniversalTranslatorConfigScreen extends UniversalTranslatorConfigScreenBase {
    UniversalTranslatorConfigScreen(Screen parent, ForgeConfig config) {
        super(parent, config);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        ForgeScreenCanvas canvas = canvas(graphics);
        float opening = renderSettingsBefore(canvas);
        super.render(graphics, mouseX, mouseY, delta);
        renderSettingsAfter(canvas, mouseX, mouseY, opening);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return handleSelectionClick(mouseX, mouseY)
                || super.mouseClicked(mouseX, mouseY, button);
    }

    private ForgeScreenCanvas canvas(GuiGraphics graphics) {
        return new ForgeScreenCanvas() {
            @Override
            public void fill(int left, int top, int right, int bottom, int color) {
                graphics.fill(left, top, right, bottom, color);
            }

            @Override
            public void centered(Component text, int centerX, int y, int color) {
                graphics.drawCenteredString(font, text, centerX, y, color);
            }

            @Override
            public void text(Component text, int x, int y, int color) {
                graphics.drawString(font, text, x, y, color);
            }
        };
    }
}

final class UniversalTranslatorDiagnosticsScreen extends UniversalTranslatorDiagnosticsScreenBase {
    UniversalTranslatorDiagnosticsScreen(Screen parent, ForgeConfig config) {
        super(parent, config);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        ForgeScreenCanvas canvas = canvas(graphics);
        renderDiagnostics(canvas);
        super.render(graphics, mouseX, mouseY, delta);
    }

    private ForgeScreenCanvas canvas(GuiGraphics graphics) {
        return CanvasFactory.create(graphics, font);
    }
}

final class UniversalTranslatorLlmConfigScreen extends UniversalTranslatorLlmConfigScreenBase {
    UniversalTranslatorLlmConfigScreen(UniversalTranslatorConfigScreenBase parent,
                                       String endpoint, String model, boolean hasStoredKey) {
        super(parent, endpoint, model, hasStoredKey);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        ForgeScreenCanvas canvas = CanvasFactory.create(graphics, font);
        renderLlm(canvas);
        super.render(graphics, mouseX, mouseY, delta);
    }
}

final class CanvasFactory {
    private CanvasFactory() {
    }

    static ForgeScreenCanvas create(GuiGraphics graphics, net.minecraft.client.gui.Font font) {
        return new ForgeScreenCanvas() {
            @Override
            public void fill(int left, int top, int right, int bottom, int color) {
                graphics.fill(left, top, right, bottom, color);
            }

            @Override
            public void centered(Component text, int centerX, int y, int color) {
                graphics.drawCenteredString(font, text, centerX, y, color);
            }

            @Override
            public void text(Component text, int x, int y, int color) {
                graphics.drawString(font, text, x, y, color);
            }
        };
    }
}
