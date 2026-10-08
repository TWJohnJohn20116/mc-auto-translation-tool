package org.universaltranslator.fabric;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.text.LiteralText;
import org.universaltranslator.core.provider.OpenAiModelCatalog;

/** Local-only editor for an OpenAI-compatible hosted or loopback LLM endpoint. */
final class UniversalTranslatorLlmConfigScreen extends Screen {
    /** Height of a single catalog row in the model picker overlay. */
    private static final int LIST_ROW_HEIGHT = 12;
    /** Y of the first catalog row; the picker title sits above it. */
    private static final int LIST_TOP = 46;
    /** Room kept under the rows for the paging, back, and hint lines. */
    private static final int LIST_FOOTER_HEIGHT = 62;
    private static final int ESCAPE_KEY = 256;

    private final UniversalTranslatorConfigScreen parent;
    private final String initialEndpoint;
    private final String initialModel;
    private final boolean hasStoredKey;
    private TextFieldWidget endpoint;
    private TextFieldWidget model;
    private TextFieldWidget apiKey;
    private volatile String status = "";
    private volatile boolean statusIsError;
    private volatile List<String> fetchedModels = Collections.emptyList();
    private volatile boolean fetching;
    private boolean modelListOpen;
    private int modelPage;

    UniversalTranslatorLlmConfigScreen(
            UniversalTranslatorConfigScreen parent,
            String endpoint,
            String model,
            boolean hasStoredKey
    ) {
        super(new TranslatableText("screen.universal_translator.llm.title"));
        this.parent = parent;
        this.initialEndpoint = endpoint;
        this.initialModel = model;
        this.hasStoredKey = hasStoredKey;
    }

    @Override
    protected void init() {
        int width = Math.max(180, Math.min(360, this.width - 20));
        int left = (this.width - width) / 2;
        int top = Math.max(42, (this.height - 174) / 2);
        endpoint = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, top, width, 20, new TranslatableText("screen.universal_translator.llm.endpoint")));
        endpoint.setMaxLength(512);
        endpoint.setText(initialEndpoint);
        model = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, top + 36, width, 20, new TranslatableText("screen.universal_translator.llm.model")));
        model.setMaxLength(128);
        model.setText(initialModel);
        apiKey = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, top + 72, width, 20, new TranslatableText("screen.universal_translator.llm.api_key")));
        apiKey.setMaxLength(512);
        int gap = 8;
        int buttonWidth = (width - gap) / 2;
        addDrawableChild(ButtonWidget.builder(new TranslatableText("screen.universal_translator.llm.save"), button -> save())
                .dimensions(left, top + 108, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(new TranslatableText("gui.cancel"), button -> onClose())
                .dimensions(left + buttonWidth + gap, top + 108, buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(
                        new TranslatableText("screen.universal_translator.llm.fetch_models"), button -> fetchModels())
                .dimensions(left, top + 132, width, 20).build());
    }

    private <T extends net.minecraft.client.gui.widget.ClickableWidget> T addDrawableChild(T child) {
        return addButton(child);
    }

    private void save() {
        String endpointValue = endpoint.getText().trim();
        String modelValue = model.getText().trim();
        if (endpointValue.isEmpty() || modelValue.isEmpty()) {
            setStatus(tr("error.universal_translator.llm_required"), true);
            return;
        }
        String enteredKey = apiKey.getText().trim();
        String keyValue = enteredKey.isEmpty()
                ? parent.llmApiKey() : ("-".equals(enteredKey) ? "" : enteredKey);
        parent.applyLlmSettings(endpointValue, modelValue, keyValue);
        onClose();
    }

    /** Asks the configured endpoint for its model catalog without blocking the render thread. */
    private void fetchModels() {
        if (fetching) {
            return;
        }
        final String endpointValue = endpoint.getText().trim();
        if (endpointValue.isEmpty()) {
            setStatus(tr("error.universal_translator.llm_required"), true);
            return;
        }
        String enteredKey = apiKey.getText().trim();
        final String keyValue = enteredKey.isEmpty()
                ? parent.llmApiKey() : ("-".equals(enteredKey) ? "" : enteredKey);
        fetching = true;
        setStatus(tr("screen.universal_translator.llm.fetching"), false);
        Thread worker = new Thread(() -> {
            List<String> models = Collections.emptyList();
            String failure = "";
            try {
                models = OpenAiModelCatalog.fetch(endpointValue, keyValue);
            } catch (Exception error) {
                failure = describe(error);
            }
            fetching = false;
            if (!failure.isEmpty()) {
                setStatus(tr("screen.universal_translator.llm.fetch_failed", failure), true);
                return;
            }
            if (models.isEmpty()) {
                setStatus(tr("screen.universal_translator.llm.fetch_empty"), true);
                return;
            }
            fetchedModels = models;
            modelPage = 0;
            modelListOpen = true;
            setStatus("", false);
        }, "universal-translator-model-list");
        worker.setDaemon(true);
        worker.start();
    }

    private void setStatus(String message, boolean error) {
        status = message;
        statusIsError = error;
    }

    private static String describe(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        int width = Math.max(180, Math.min(360, this.width - 20));
        int left = (this.width - width) / 2;
        int top = Math.max(42, (this.height - 174) / 2);
        drawCenteredText(matrices, this.textRenderer, this.title, this.width / 2, 18, 0xFFFFFF);
        drawTextWithShadow(matrices, this.textRenderer,
                new TranslatableText("screen.universal_translator.llm.endpoint_hint"), left, top - 11, 0xA0A0A0);
        drawTextWithShadow(matrices, this.textRenderer, new TranslatableText("screen.universal_translator.llm.model_hint"),
                left, top + 25, 0xA0A0A0);
        drawTextWithShadow(matrices, this.textRenderer,
                new TranslatableText(hasStoredKey
                        ? "screen.universal_translator.llm.key_saved_hint"
                        : "screen.universal_translator.llm.key_empty_hint"),
                left, top + 61, 0xA0A0A0);
        if (!status.isEmpty()) {
            drawCenteredText(matrices, this.textRenderer, new LiteralText(status),
                    this.width / 2, top + 158, statusIsError ? 0xFF5555 : 0xFFE0E0E0);
        }
        super.render(matrices, mouseX, mouseY, delta);
        if (modelListOpen) {
            renderModelList(matrices, mouseX, mouseY);
        }
    }

    /** Draws the fetched catalog over the form so a served model can be picked instead of typed. */
    private void renderModelList(MatrixStack matrices, int mouseX, int mouseY) {
        fill(matrices, 0, 0, this.width, this.height, 0xFF101010);
        drawCenteredText(matrices, this.textRenderer,
                new TranslatableText("screen.universal_translator.llm.select_model", fetchedModels.size()),
                this.width / 2, 20, 0xFFFFFF);
        int rows = rowsPerPage();
        int first = modelPage * rows;
        for (int row = 0; row < rows && first + row < fetchedModels.size(); row++) {
            int rowY = LIST_TOP + row * LIST_ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + LIST_ROW_HEIGHT;
            drawTextWithShadow(matrices, this.textRenderer,
                    new LiteralText((hovered ? "> " : "  ") + fetchedModels.get(first + row)),
                    24, rowY, hovered ? 0xFFFFD060 : 0xFFE0E0E0);
        }
        int pages = pageCount(rows);
        drawCenteredText(matrices, this.textRenderer,
                new LiteralText("< " + (modelPage + 1) + "/" + pages + " >"), this.width / 2, this.height - 46, 0xFFFFFF);
        drawCenteredText(matrices, this.textRenderer,
                new TranslatableText("screen.universal_translator.llm.select_back"), this.width / 2, this.height - 30, 0xFFFFD060);
        drawCenteredText(matrices, this.textRenderer,
                new TranslatableText("screen.universal_translator.llm.select_hint"), this.width / 2, this.height - 16, 0xA0A0A0);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (modelListOpen) {
            handleModelListClick(mouseX, mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void handleModelListClick(double mouseX, double mouseY) {
        if (mouseY >= this.height - 54 && mouseY < this.height - 38) {
            int pages = pageCount(rowsPerPage());
            if (pages > 1) {
                modelPage = mouseX < this.width / 2.0
                        ? (modelPage + pages - 1) % pages : (modelPage + 1) % pages;
            }
            return;
        }
        if (mouseY >= this.height - 38 && mouseY < this.height - 20) {
            modelListOpen = false;
            return;
        }
        if (mouseY < LIST_TOP) {
            return;
        }
        int rows = rowsPerPage();
        int row = (int) ((mouseY - LIST_TOP) / LIST_ROW_HEIGHT);
        int index = modelPage * rows + row;
        if (row >= rows || index >= fetchedModels.size()) {
            return;
        }
        model.setText(fetchedModels.get(index));
        modelListOpen = false;
        setStatus("", false);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (modelListOpen) {
            if (keyCode == ESCAPE_KEY) {
                modelListOpen = false;
            }
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        return modelListOpen || super.charTyped(chr, modifiers);
    }

    private int rowsPerPage() {
        return Math.max(3, (this.height - LIST_TOP - LIST_FOOTER_HEIGHT) / LIST_ROW_HEIGHT);
    }

    private int pageCount(int rows) {
        return Math.max(1, (fetchedModels.size() + rows - 1) / rows);
    }

    @Override
    public void onClose() {
        if (this.client != null) {
            this.client.openScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return new TranslatableText(key, arguments).getString();
    }
}
