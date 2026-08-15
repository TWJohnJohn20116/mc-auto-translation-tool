package org.universaltranslator.fabric;

import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;

/** OpenAI-compatible LLM settings shared by Forge 1.8.9 and 1.12.2. */
final class UniversalTranslatorLlmConfigScreen extends Screen {
    private static final int SAVE = 1;
    private static final int CANCEL = 2;

    private final UniversalTranslatorConfigScreen parent;
    private final String initialEndpoint;
    private final String initialModel;
    private final boolean hasStoredKey;
    private TextRenderer renderer;
    private TextFieldWidget endpoint;
    private TextFieldWidget model;
    private TextFieldWidget apiKey;
    private String status = "";

    UniversalTranslatorLlmConfigScreen(
            UniversalTranslatorConfigScreen parent,
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
    public void init() {
        buttons.clear();
        renderer = OrnitheClientAccess.textRenderer();
        int fieldWidth = Math.max(180, Math.min(360, width - 20));
        int left = (width - fieldWidth) / 2;
        int top = Math.max(42, (height - 150) / 2);
        endpoint = new TextFieldWidget(renderer, left, top, fieldWidth, 20);
        endpoint.setMaxLength(512);
        endpoint.setText(initialEndpoint);
        model = new TextFieldWidget(renderer, left, top + 36, fieldWidth, 20);
        model.setMaxLength(128);
        model.setText(initialModel);
        apiKey = new TextFieldWidget(renderer, left, top + 72, fieldWidth, 20);
        apiKey.setMaxLength(512);
        int gap = 8;
        int buttonWidth = (fieldWidth - gap) / 2;
        buttons.add(new ButtonWidget(SAVE, left, top + 108, buttonWidth, 20,
                tr("screen.universal_translator.llm.save")));
        buttons.add(new ButtonWidget(
                CANCEL, left + buttonWidth + gap, top + 108, buttonWidth, 20, tr("gui.cancel")));
    }

    @Override
    protected void buttonClicked(ButtonWidget button) {
        if (button.id == CANCEL) {
            OrnitheClientAccess.openScreen(parent);
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
        OrnitheClientAccess.openScreen(parent);
    }

    @Override
    public void tick() {
        endpoint.tick();
        model.tick();
        apiKey.tick();
    }

    @Override
    protected void keyPressed(char typedChar, int keyCode) {
        if (endpoint.keyPressed(typedChar, keyCode)
                || model.keyPressed(typedChar, keyCode)
                || apiKey.keyPressed(typedChar, keyCode)) {
            return;
        }
        super.keyPressed(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        endpoint.mouseClicked(mouseX, mouseY, mouseButton);
        model.mouseClicked(mouseX, mouseY, mouseButton);
        apiKey.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        renderBackground();
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
        endpoint.render();
        model.render();
        apiKey.render();
        if (!status.isEmpty()) {
            drawCenteredString(renderer, status, width / 2, top + 134, 0xFF5555);
        }
        super.render(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean shouldPauseGame() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return OrnitheClientAccess.tr(key, arguments);
    }
}

