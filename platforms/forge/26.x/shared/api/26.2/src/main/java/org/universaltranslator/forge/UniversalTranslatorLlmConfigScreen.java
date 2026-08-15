package org.universaltranslator.forge;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Local-only editor for an OpenAI-compatible hosted or loopback LLM endpoint. */
final class UniversalTranslatorLlmConfigScreen extends Screen {
    private final UniversalTranslatorConfigScreen parent;
    private final String initialEndpoint;
    private final String initialModel;
    private final boolean hasStoredKey;
    private EditBox endpoint;
    private EditBox model;
    private EditBox apiKey;
    private String status = "";

    UniversalTranslatorLlmConfigScreen(
            UniversalTranslatorConfigScreen parent,
            String endpoint,
            String model,
            boolean hasStoredKey
    ) {
        super(Component.translatable("screen.universal_translator.llm.title"));
        this.parent = parent;
        this.initialEndpoint = endpoint;
        this.initialModel = model;
        this.hasStoredKey = hasStoredKey;
    }

    @Override
    protected void init() {
        int formWidth = Math.max(180, Math.min(360, this.width - 20));
        int left = (this.width - formWidth) / 2;
        int top = Math.max(42, (this.height - 150) / 2);
        endpoint = addRenderableWidget(new EditBox(
                this.font, left, top, formWidth, 20, Component.translatable("screen.universal_translator.llm.endpoint")));
        endpoint.setMaxLength(512);
        endpoint.setValue(initialEndpoint);
        model = addRenderableWidget(new EditBox(
                this.font, left, top + 36, formWidth, 20, Component.translatable("screen.universal_translator.llm.model")));
        model.setMaxLength(128);
        model.setValue(initialModel);
        apiKey = addRenderableWidget(new EditBox(
                this.font, left, top + 72, formWidth, 20, Component.translatable("screen.universal_translator.llm.api_key")));
        apiKey.setMaxLength(512);
        int gap = 8;
        int buttonWidth = (formWidth - gap) / 2;
        addRenderableWidget(Button.builder(Component.translatable("screen.universal_translator.llm.save"), button -> save())
                .bounds(left, top + 108, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(left + buttonWidth + gap, top + 108, buttonWidth, 20).build());
    }

    private void save() {
        String endpointValue = endpoint.getValue().trim();
        String modelValue = model.getValue().trim();
        if (endpointValue.isEmpty() || modelValue.isEmpty()) {
            status = tr("error.universal_translator.llm_required");
            return;
        }
        String enteredKey = apiKey.getValue().trim();
        String keyValue = enteredKey.isEmpty()
                ? parent.llmApiKey() : ("-".equals(enteredKey) ? "" : enteredKey);
        parent.applyLlmSettings(endpointValue, modelValue, keyValue);
        onClose();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int formWidth = Math.max(180, Math.min(360, this.width - 20));
        int left = (this.width - formWidth) / 2;
        int top = Math.max(42, (this.height - 150) / 2);
        graphics.centeredText(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        graphics.text(this.font, Component.translatable("screen.universal_translator.llm.endpoint_hint"),
                left, top - 11, 0xA0A0A0);
        graphics.text(this.font, Component.translatable("screen.universal_translator.llm.model_hint"), left, top + 25, 0xA0A0A0);
        graphics.text(this.font,
                Component.translatable(hasStoredKey
                        ? "screen.universal_translator.llm.key_saved_hint"
                        : "screen.universal_translator.llm.key_empty_hint"),
                left, top + 61, 0xA0A0A0);
        if (!status.isEmpty()) {
            graphics.centeredText(this.font, Component.literal(status),
                    this.width / 2, top + 134, 0xFF5555);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.gui.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return Component.translatable(key, arguments).getString();
    }
}
