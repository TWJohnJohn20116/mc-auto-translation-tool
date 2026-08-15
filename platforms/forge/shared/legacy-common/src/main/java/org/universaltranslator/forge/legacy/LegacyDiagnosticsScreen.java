package org.universaltranslator.forge.legacy;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;

import java.io.IOException;
import java.util.List;
import java.util.Collections;

/** Secret-free runtime diagnostics shared by Forge 1.8.9 and 1.12.2. */
final class LegacyDiagnosticsScreen extends GuiScreen {
    private static final int BACK = 1;
    private static final int EXPORT = 2;
    private final GuiScreen parent;
    private FontRenderer renderer;
    private String exportStatus = "";
    private boolean exportFailed;

    LegacyDiagnosticsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        renderer = LegacyVersionAccess.fontRenderer();
        int totalWidth = Math.max(180, Math.min(320, width - 24));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (width - totalWidth) / 2;
        buttonList.add(new GuiButton(BACK, left, height - 28,
                buttonWidth, 20, tr("screen.universal_translator.diagnostics.back")));
        buttonList.add(new GuiButton(EXPORT, left + buttonWidth + gap, height - 28,
                buttonWidth, 20, tr("screen.universal_translator.diagnostics.export")));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == BACK) {
            mc.displayGuiScreen(parent);
        } else if (button.id == EXPORT) {
            exportLog();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawRect(Math.max(5, width / 2 - 190), 8,
                Math.min(width - 5, width / 2 + 190), height - 34, 0xD51A232E);
        drawCenteredString(renderer, tr("screen.universal_translator.diagnostics.title"),
                width / 2, 18, 0xFFFFFF);
        List<String> lines = safeLines();
        int left = Math.max(10, (width - Math.min(360, width - 20)) / 2);
        int y = 43;
        for (String line : lines) {
            drawString(renderer, line, left, y, 0xD0D0D0);
            y += 17;
        }
        drawCenteredString(renderer, tr("screen.universal_translator.diagnostics.note"),
                width / 2, Math.min(y + 7, height - 58), 0x909090);
        if (!exportStatus.isEmpty()) {
            drawCenteredString(renderer, exportStatus, width / 2, height - 43,
                    exportFailed ? 0xFF5555 : 0x55FF88);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private List<String> safeLines() {
        try {
            TranslationDiagnosticsSnapshot snapshot = LegacyTranslationRuntime.diagnostics();
            return snapshot == null
                    ? Collections.singletonList(tr("screen.universal_translator.diagnostics.unavailable"))
                    : snapshot.localizedLines(LegacyDiagnosticsScreen::tr);
        } catch (RuntimeException ignored) {
            return Collections.singletonList(tr("screen.universal_translator.diagnostics.unavailable"));
        }
    }

    private void exportLog() {
        try {
            LegacyTranslationRuntime.exportDiagnostics(safeLines());
            exportStatus = tr("screen.universal_translator.diagnostics.exported");
            exportFailed = false;
        } catch (Exception ignored) {
            exportStatus = tr("screen.universal_translator.diagnostics.export_failed");
            exportFailed = true;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return I18n.format(key, arguments);
    }
}
