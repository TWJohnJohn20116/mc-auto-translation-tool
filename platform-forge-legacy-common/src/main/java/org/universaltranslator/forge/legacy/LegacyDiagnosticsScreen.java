package org.universaltranslator.forge.legacy;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;

import java.io.IOException;
import java.util.List;

/** Secret-free runtime diagnostics shared by Forge 1.8.9 and 1.12.2. */
final class LegacyDiagnosticsScreen extends GuiScreen {
    private static final int BACK = 1;
    private final GuiScreen parent;
    private FontRenderer renderer;

    LegacyDiagnosticsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        renderer = LegacyVersionAccess.fontRenderer();
        int buttonWidth = Math.max(120, Math.min(220, width - 40));
        buttonList.add(new GuiButton(BACK, (width - buttonWidth) / 2, height - 28,
                buttonWidth, 20, "返回设置"));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == BACK) {
            mc.displayGuiScreen(parent);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(renderer, "翻译诊断", width / 2, 18, 0xFFFFFF);
        TranslationDiagnosticsSnapshot snapshot = LegacyTranslationRuntime.diagnostics();
        List<String> lines = snapshot.displayLines();
        int left = Math.max(10, (width - Math.min(360, width - 20)) / 2);
        int y = 43;
        for (String line : lines) {
            drawString(renderer, line, left, y, 0xD0D0D0);
            y += 17;
        }
        drawCenteredString(renderer, "状态会实时更新；不显示端点或密钥。",
                width / 2, Math.min(y + 7, height - 43), 0x808080);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
