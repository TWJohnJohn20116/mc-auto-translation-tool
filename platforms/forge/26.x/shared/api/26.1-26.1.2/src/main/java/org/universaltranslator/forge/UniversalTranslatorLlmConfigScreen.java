package org.universaltranslator.forge;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.universaltranslator.core.provider.OpenAiModelCatalog;

import java.util.Collections;
import java.util.List;

/** Local-only editor for an OpenAI-compatible hosted or loopback LLM endpoint. */
final class UniversalTranslatorLlmConfigScreen extends Screen {
    /** Height of a single catalog row in the model picker overlay. */
    private static final int LIST_ROW_HEIGHT = 12;
    /** Y of the first catalog row; the picker title sits above it. */
    private static final int LIST_TOP = 46;
    /** Room kept under the rows for the paging, back, and hint lines. */
    private static final int LIST_FOOTER_HEIGHT = 62;

    private final UniversalTranslatorConfigScreen parent;
    private final String initialEndpoint;
    private final String initialModel;
    private final boolean hasStoredKey;
    private EditBox endpoint;
    private EditBox model;
    private EditBox apiKey;
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
        int top = Math.max(42, (this.height - 174) / 2);
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
        addRenderableWidget(Button.builder(
                        Component.translatable("screen.universal_translator.llm.fetch_models"), button -> fetchModels())
                .bounds(left, top + 132, formWidth, 20).build());
    }

    private void save() {
        String endpointValue = endpoint.getValue().trim();
        String modelValue = model.getValue().trim();
        if (endpointValue.isEmpty() || modelValue.isEmpty()) {
            setStatus(tr("error.universal_translator.llm_required"), true);
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
        int top = Math.max(42, (this.height - 174) / 2);
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
                    this.width / 2, top + 158, statusIsError ? 0xFF5555 : 0xFFE0E0E0);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (modelListOpen) {
            renderModelList(graphics, mouseX, mouseY);
        }
    }

    /** Asks the configured endpoint for its model catalog without blocking the render thread. */
    private void fetchModels() {
        if (fetching) {
            return;
        }
        final String endpointValue = endpoint.getValue().trim();
        if (endpointValue.isEmpty()) {
            setStatus(tr("error.universal_translator.llm_required"), true);
            return;
        }
        String enteredKey = apiKey.getValue().trim();
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

    /** Draws the fetched catalog over the form so a served model can be picked instead of typed. */
    private void renderModelList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.fill(0, 0, this.width, this.height, 0xFF101010);
        graphics.centeredText(this.font,
                Component.translatable("screen.universal_translator.llm.select_model", fetchedModels.size()),
                this.width / 2, 20, 0xFFFFFF);
        int rows = rowsPerPage();
        int first = modelPage * rows;
        for (int row = 0; row < rows && first + row < fetchedModels.size(); row++) {
            int rowY = LIST_TOP + row * LIST_ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + LIST_ROW_HEIGHT;
            graphics.text(this.font, Component.literal((hovered ? "> " : "  ") + fetchedModels.get(first + row)),
                    24, rowY, hovered ? 0xFFFFD060 : 0xFFE0E0E0);
        }
        int pages = pageCount(rows);
        graphics.centeredText(this.font, Component.literal("< " + (modelPage + 1) + "/" + pages + " >"),
                this.width / 2, this.height - 46, 0xFFFFFF);
        graphics.centeredText(this.font,
                Component.translatable("screen.universal_translator.llm.select_back"),
                this.width / 2, this.height - 30, 0xFFFFD060);
        graphics.centeredText(this.font,
                Component.translatable("screen.universal_translator.llm.select_hint"),
                this.width / 2, this.height - 16, 0xA0A0A0);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (modelListOpen) {
            handleModelListClick(event.x(), event.y());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
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
        model.setValue(fetchedModels.get(index));
        modelListOpen = false;
        setStatus("", false);
    }

    private int rowsPerPage() {
        return Math.max(3, (this.height - LIST_TOP - LIST_FOOTER_HEIGHT) / LIST_ROW_HEIGHT);
    }

    private int pageCount(int rows) {
        return Math.max(1, (fetchedModels.size() + rows - 1) / rows);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
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
