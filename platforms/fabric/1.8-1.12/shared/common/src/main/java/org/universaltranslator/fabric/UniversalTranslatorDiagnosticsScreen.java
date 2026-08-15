package org.universaltranslator.fabric;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.resource.language.I18n;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;

import java.io.IOException;
import java.util.List;
import java.util.Collections;

/** Secret-free runtime diagnostics shared by Forge 1.8.9 and 1.12.2. */
final class UniversalTranslatorDiagnosticsScreen extends Screen {
    private static final int BACK = 1;
    private static final int EXPORT = 2;
    private final Screen parent;
    private TextRenderer renderer;
    private String exportStatus = "";
    private boolean exportFailed;

    UniversalTranslatorDiagnosticsScreen(Screen parent) {
        this.parent = parent;
    }

    @Override
    public void init() {
        buttons.clear();
        renderer = OrnitheClientAccess.textRenderer();
        int totalWidth = Math.max(180, Math.min(320, width - 24));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (width - totalWidth) / 2;
        buttons.add(new ButtonWidget(BACK, left, height - 28,
                buttonWidth, 20, tr("screen.universal_translator.diagnostics.back")));
        buttons.add(new ButtonWidget(EXPORT, left + buttonWidth + gap, height - 28,
                buttonWidth, 20, tr("screen.universal_translator.diagnostics.export")));
    }

    @Override
    protected void buttonClicked(ButtonWidget button) {
        if (button.id == BACK) {
            OrnitheClientAccess.openScreen(parent);
        } else if (button.id == EXPORT) {
            exportLog();
        }
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        renderBackground();
        fill(Math.max(5, width / 2 - 190), 8,
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
        super.render(mouseX, mouseY, partialTicks);
    }

    private List<String> safeLines() {
        try {
            TranslationDiagnosticsSnapshot snapshot = FabricTranslationRuntime.diagnostics();
            return snapshot == null
                    ? Collections.singletonList(tr("screen.universal_translator.diagnostics.unavailable"))
                    : snapshot.localizedLines(UniversalTranslatorDiagnosticsScreen::tr);
        } catch (RuntimeException ignored) {
            return Collections.singletonList(tr("screen.universal_translator.diagnostics.unavailable"));
        }
    }

    private void exportLog() {
        try {
            FabricTranslationRuntime.exportDiagnostics(safeLines());
            exportStatus = tr("screen.universal_translator.diagnostics.exported");
            exportFailed = false;
        } catch (Exception ignored) {
            exportStatus = tr("screen.universal_translator.diagnostics.export_failed");
            exportFailed = true;
        }
    }

    @Override
    public boolean shouldPauseGame() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return I18n.translate(key, arguments);
    }
}

