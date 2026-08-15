package org.universaltranslator.forge;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Minecraft 1.19.2 render/input bridge for the shared settings screens. */
final class UniversalTranslatorConfigScreen extends UniversalTranslatorConfigScreenBase {
    UniversalTranslatorConfigScreen(Screen parent, ForgeConfig config) {
        super(parent, config);
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float delta) {
        ForgeScreenCanvas canvas = canvas(pose);
        float opening = renderSettingsBefore(canvas);
        super.render(pose, mouseX, mouseY, delta);
        renderSettingsAfter(canvas, mouseX, mouseY, opening);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return handleSelectionClick(mouseX, mouseY)
                || super.mouseClicked(mouseX, mouseY, button);
    }

    private ForgeScreenCanvas canvas(PoseStack pose) {
        return CanvasFactory.create(pose, font);
    }
}

final class UniversalTranslatorDiagnosticsScreen extends UniversalTranslatorDiagnosticsScreenBase {
    UniversalTranslatorDiagnosticsScreen(Screen parent, ForgeConfig config) {
        super(parent, config);
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float delta) {
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
    public void render(PoseStack pose, int mouseX, int mouseY, float delta) {
        renderLlm(CanvasFactory.create(pose, font));
        super.render(pose, mouseX, mouseY, delta);
    }
}

final class CanvasFactory {
    private CanvasFactory() {
    }

    static ForgeScreenCanvas create(PoseStack pose, Font font) {
        return new ForgeScreenCanvas() {
            @Override
            public void fill(int left, int top, int right, int bottom, int color) {
                Screen.fill(pose, left, top, right, bottom, color);
            }

            @Override
            public void centered(Component text, int centerX, int y, int color) {
                Screen.drawCenteredString(pose, font, text, centerX, y, color);
            }

            @Override
            public void text(Component text, int x, int y, int color) {
                Screen.drawString(pose, font, text, x, y, color);
            }
        };
    }
}
