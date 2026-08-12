package org.universaltranslator.fabric;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Local-only editor for an OpenAI-compatible hosted or loopback LLM endpoint. */
final class UniversalTranslatorLlmConfigScreen extends Screen {
    private final UniversalTranslatorConfigScreen parent;
    private final String initialEndpoint;
    private final String initialModel;
    private final boolean hasStoredKey;
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
        super(Text.translatable("screen.universal_translator.llm.title"));
        this.parent = parent;
        this.initialEndpoint = endpoint;
        this.initialModel = model;
        this.hasStoredKey = hasStoredKey;
    }

    @Override
    protected void init() {
        int width = Math.max(180, Math.min(360, this.width - 20));
        int left = (this.width - width) / 2;
        int top = Math.max(42, (this.height - 150) / 2);
        endpoint = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, top, width, 20, Text.translatable("screen.universal_translator.llm.endpoint")));
        endpoint.setMaxLength(512);
        endpoint.setText(initialEndpoint);
        model = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, top + 36, width, 20, Text.translatable("screen.universal_translator.llm.model")));
        model.setMaxLength(128);
        model.setText(initialModel);
        apiKey = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, top + 72, width, 20, Text.translatable("screen.universal_translator.llm.api_key")));
        apiKey.setMaxLength(512);
        int gap = 8;
        int buttonWidth = (width - gap) / 2;
        addDrawableChild(ButtonWidget.builder(Text.translatable("screen.universal_translator.llm.save"), button -> save())
                .dimensions(left, top + 108, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.translatable("gui.cancel"), button -> close())
                .dimensions(left + buttonWidth + gap, top + 108, buttonWidth, 20).build());
    }

    private void save() {
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
        close();
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        int width = Math.max(180, Math.min(360, this.width - 20));
        int left = (this.width - width) / 2;
        int top = Math.max(42, (this.height - 150) / 2);
        drawCenteredText(matrices, this.textRenderer, this.title, this.width / 2, 18, 0xFFFFFF);
        drawTextWithShadow(matrices, this.textRenderer,
                Text.translatable("screen.universal_translator.llm.endpoint_hint"), left, top - 11, 0xA0A0A0);
        drawTextWithShadow(matrices, this.textRenderer, Text.translatable("screen.universal_translator.llm.model_hint"),
                left, top + 25, 0xA0A0A0);
        drawTextWithShadow(matrices, this.textRenderer,
                Text.translatable(hasStoredKey
                        ? "screen.universal_translator.llm.key_saved_hint"
                        : "screen.universal_translator.llm.key_empty_hint"),
                left, top + 61, 0xA0A0A0);
        if (!status.isEmpty()) {
            drawCenteredText(matrices, this.textRenderer, Text.literal(status),
                    this.width / 2, top + 134, 0xFF5555);
        }
        super.render(matrices, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
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
