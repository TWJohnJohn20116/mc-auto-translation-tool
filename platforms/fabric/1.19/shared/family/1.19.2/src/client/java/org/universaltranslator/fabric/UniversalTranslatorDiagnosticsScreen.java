package org.universaltranslator.fabric;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;

import java.util.List;
import java.util.Collections;

/** Secret-free runtime diagnostics that update while the screen is open. */
final class UniversalTranslatorDiagnosticsScreen extends Screen {
    private final Screen parent;
    private String exportStatus = "";
    private boolean exportFailed;

    UniversalTranslatorDiagnosticsScreen(Screen parent) {
        super(Text.translatable("screen.universal_translator.diagnostics.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int totalWidth = Math.max(180, Math.min(320, this.width - 24));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (this.width - totalWidth) / 2;
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.universal_translator.diagnostics.back"), button -> close())
                .dimensions(left, this.height - 28, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(
                Text.translatable("screen.universal_translator.diagnostics.export"), button -> exportLog())
                .dimensions(left + buttonWidth + gap, this.height - 28, buttonWidth, 20).build());
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        fill(matrices, 0, 0, width, height, 0xE510151C);
        fill(matrices, Math.max(5, width / 2 - 190), 8,
                Math.min(width - 5, width / 2 + 190), height - 34, 0xD51A232E);
        drawCenteredText(matrices, textRenderer, title, width / 2, 18, 0xFFFFFF);
        List<String> lines = safeLines();
        int left = Math.max(10, (width - Math.min(360, width - 20)) / 2);
        int y = 43;
        for (String line : lines) {
            drawTextWithShadow(matrices, textRenderer, Text.literal(line), left, y, 0xD0D0D0);
            y += 17;
        }
        drawCenteredText(matrices, textRenderer,
                Text.translatable("screen.universal_translator.diagnostics.note"),
                width / 2, Math.min(y + 7, height - 58), 0x909090);
        if (!exportStatus.isEmpty()) {
            drawCenteredText(matrices, textRenderer, Text.literal(exportStatus),
                    width / 2, height - 43, exportFailed ? 0xFF5555 : 0x55FF88);
        }
        super.render(matrices, mouseX, mouseY, delta);
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
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return Text.translatable(key, arguments).getString();
    }
}
