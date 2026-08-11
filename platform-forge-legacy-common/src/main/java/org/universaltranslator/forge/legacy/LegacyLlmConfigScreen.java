package org.universaltranslator.forge.legacy;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;

import java.io.IOException;

/** OpenAI-compatible LLM settings shared by Forge 1.8.9 and 1.12.2. */
final class LegacyLlmConfigScreen extends GuiScreen {
    private static final int SAVE = 1;
    private static final int CANCEL = 2;

    private final LegacyConfigScreen parent;
    private final String initialEndpoint;
    private final String initialModel;
    private final boolean hasStoredKey;
    private FontRenderer renderer;
    private GuiTextField endpoint;
    private GuiTextField model;
    private GuiTextField apiKey;
    private String status = "";

    LegacyLlmConfigScreen(
            LegacyConfigScreen parent,
            String endpoint,
            String model,
            boolean hasStoredKey
    ) {
        this.parent = parent;
        this.initialEndpoint = endpoint;
        this.initialModel = model;
        this.hasStoredKey = hasStoredKey;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        renderer = LegacyVersionAccess.fontRenderer();
        int fieldWidth = Math.max(180, Math.min(360, width - 20));
        int left = (width - fieldWidth) / 2;
        int top = Math.max(42, (height - 150) / 2);
        endpoint = new GuiTextField(10, renderer, left, top, fieldWidth, 20);
        endpoint.setMaxStringLength(512);
        endpoint.setText(initialEndpoint);
        model = new GuiTextField(11, renderer, left, top + 36, fieldWidth, 20);
        model.setMaxStringLength(128);
        model.setText(initialModel);
        apiKey = new GuiTextField(12, renderer, left, top + 72, fieldWidth, 20);
        apiKey.setMaxStringLength(512);
        int gap = 8;
        int buttonWidth = (fieldWidth - gap) / 2;
        buttonList.add(new GuiButton(SAVE, left, top + 108, buttonWidth, 20,
                tr("screen.universal_translator.llm.save")));
        buttonList.add(new GuiButton(
                CANCEL, left + buttonWidth + gap, top + 108, buttonWidth, 20, tr("gui.cancel")));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == CANCEL) {
            mc.displayGuiScreen(parent);
            return;
        }
        if (button.id != SAVE) {
            return;
        }
        String endpointValue = endpoint.getText().trim();
        String modelValue = model.getText().trim();
        if (endpointValue.isEmpty() || modelValue.isEmpty()) {
            status = tr("error.universal_translator.llm_required");
            return;
        }
        String enteredKey = apiKey.getText().trim();
        String keyValue = enteredKey.isEmpty()
                ? parent.llmApiKey() : ("-".equals(enteredKey) ? "" : enteredKey);
        parent.applyLlmSettings(endpointValue, modelValue, keyValue);
        mc.displayGuiScreen(parent);
    }

    @Override
    public void updateScreen() {
        endpoint.updateCursorCounter();
        model.updateCursorCounter();
        apiKey.updateCursorCounter();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (endpoint.textboxKeyTyped(typedChar, keyCode)
                || model.textboxKeyTyped(typedChar, keyCode)
                || apiKey.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        endpoint.mouseClicked(mouseX, mouseY, mouseButton);
        model.mouseClicked(mouseX, mouseY, mouseButton);
        apiKey.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int fieldWidth = Math.max(180, Math.min(360, width - 20));
        int left = (width - fieldWidth) / 2;
        int top = Math.max(42, (height - 150) / 2);
        drawCenteredString(renderer, tr("screen.universal_translator.llm.title"), width / 2, 18, 0xFFFFFF);
        drawString(renderer, tr("screen.universal_translator.llm.endpoint_hint"), left, top - 11, 0xA0A0A0);
        drawString(renderer, tr("screen.universal_translator.llm.model_hint"), left, top + 25, 0xA0A0A0);
        drawString(renderer,
                tr(hasStoredKey
                        ? "screen.universal_translator.llm.key_saved_hint"
                        : "screen.universal_translator.llm.key_empty_hint"),
                left, top + 61, 0xA0A0A0);
        endpoint.drawTextBox();
        model.drawTextBox();
        apiKey.drawTextBox();
        if (!status.isEmpty()) {
            drawCenteredString(renderer, status, width / 2, top + 134, 0xFF5555);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return I18n.format(key, arguments);
    }
}
