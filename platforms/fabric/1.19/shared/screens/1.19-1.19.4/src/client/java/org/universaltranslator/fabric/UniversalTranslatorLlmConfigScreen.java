package org.universaltranslator.fabric;

import org.universaltranslator.core.ModelListPaging;
import org.universaltranslator.core.UiLimits;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.universaltranslator.core.provider.OpenAiModelCatalog;
import org.universaltranslator.core.FocusHelper;

/** Local-only editor for an OpenAI-compatible hosted or loopback LLM endpoint. */
final class UniversalTranslatorLlmConfigScreen extends UniversalTranslatorScreenBase {
    /** Height of a single catalog row in the model picker overlay. */
    private static final int LIST_ROW_HEIGHT = ModelListPaging.ROW_HEIGHT;
    /** Y of the first catalog row; the picker title sits above it. */
    private static final int LIST_TOP = ModelListPaging.LIST_TOP;
    private static final int ESCAPE_KEY = 256;

    private final UniversalTranslatorSettingsScreen parent;
    private String initialEndpoint;
    private String initialModel;
    private String initialApiKey = "";
    private final boolean hasStoredKey;
    private TextFieldWidget endpoint;
    private TextFieldWidget model;
    private TextFieldWidget apiKey;
    private volatile String status = "";
    private volatile boolean statusIsError;
    private volatile List<String> fetchedModels = Collections.emptyList();
    private volatile boolean fetching;
    // Written by the worker that fetches the catalog and read by the render thread, so both need
    // the visibility the list itself already had.
    private volatile boolean modelListOpen;
    private volatile int modelPage;

    UniversalTranslatorLlmConfigScreen(
            UniversalTranslatorSettingsScreen parent,
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
        // init() also runs on every resize (window drag, F11, GUI scale). Re-applying the
        // constructor snapshot silently discarded whatever had been typed, and the API key field had
        // no value at all - so a pasted key vanished on the first resize.
        if (endpoint != null) { initialEndpoint = endpoint.getText(); }
        if (model != null) { initialModel = model.getText(); }
        if (apiKey != null) { initialApiKey = apiKey.getText(); }
int width = Math.max(180, Math.min(360, this.width - 20));
        int left = (this.width - width) / 2;
        int top = Math.max(42, (this.height - 174) / 2);
        endpoint = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, top, width, 20, Text.translatable("screen.universal_translator.llm.endpoint")));
        endpoint.setMaxLength(UiLimits.ENDPOINT_MAX_CHARS);
        endpoint.setText(initialEndpoint);
        model = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, top + 36, width, 20, Text.translatable("screen.universal_translator.llm.model")));
        model.setMaxLength(UiLimits.MODEL_ID_MAX_CHARS);
        model.setText(initialModel);
        apiKey = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, top + 72, width, 20, Text.translatable("screen.universal_translator.llm.api_key")));
        apiKey.setMaxLength(UiLimits.SECRET_MAX_CHARS);
        apiKey.setText(initialApiKey);
        int gap = 8;
        int buttonWidth = (width - gap) / 2;
        addDrawableChild(of(Text.translatable("screen.universal_translator.llm.save"), button -> save())
                .dimensions(left, top + 108, buttonWidth, 20).build());
        addDrawableChild(of(Text.translatable("gui.cancel"), button -> close())
                .dimensions(left + buttonWidth + gap, top + 108, buttonWidth, 20).build());
        addDrawableChild(of(
                        Text.translatable("screen.universal_translator.llm.fetch_models"), button -> fetchModels())
                .dimensions(left, top + 132, width, 20).build());
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
        close();
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
            } finally {
                // In a finally block so a LinkageError or OutOfMemoryError from the provider class
                // cannot leave the guard stuck at true and silence the button for the session.
                fetching = false;
            }
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
                    this.width / 2, top + 158, statusIsError ? 0xFF5555 : 0xFFE0E0E0);
        }
        super.render(matrices, mouseX, mouseY, delta);
        if (modelListOpen) {
            releaseFieldFocus();
            renderModelList(matrices, mouseX, mouseY);
        }
    }

    /**
     * Drops focus from the form while the picker covers it.
     *
     * <p>{@code charTyped} and {@code keyPressed} already swallow the keystrokes here, so this is
     * the second line of defence: a field that is not focused cannot be typed into even if a later
     * version routes characters through a different callback.
     */
    private void releaseFieldFocus() {
        if (endpoint != null) {
            FocusHelper.clear(endpoint);
        }
        if (model != null) {
            FocusHelper.clear(model);
        }
        if (apiKey != null) {
            FocusHelper.clear(apiKey);
        }
    }

    /** Draws the fetched catalog over the form so a served model can be picked instead of typed. */
    private void renderModelList(MatrixStack matrices, int mouseX, int mouseY) {
        // Snapshot the list once: the worker replaces it wholesale, so reading size() and get()
        // through the volatile field separately can mix two different lists and throw on the
        // render thread.
        List<String> models = fetchedModels;
        fill(matrices, 0, 0, this.width, this.height, 0xFF101010);
        drawCenteredText(matrices, this.textRenderer,
                Text.translatable("screen.universal_translator.llm.select_model", models.size()),
                this.width / 2, 20, 0xFFFFFF);
        int rows = rowsPerPage();
        modelPage = ModelListPaging.clampPage(modelPage, models.size(), rows);
        int first = modelPage * rows;
        for (int row = 0; row < rows && first + row < models.size(); row++) {
            int rowY = LIST_TOP + row * LIST_ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + LIST_ROW_HEIGHT;
            drawTextWithShadow(matrices, this.textRenderer,
                    Text.literal((hovered ? "> " : "  ") + models.get(first + row)),
                    24, rowY, hovered ? 0xFFFFD060 : 0xFFE0E0E0);
        }
        int pages = ModelListPaging.pageCount(models.size(), rows);
        drawCenteredText(matrices, this.textRenderer,
                Text.literal("< " + (modelPage + 1) + "/" + pages + " >"), this.width / 2, this.height - 46, 0xFFFFFF);
        drawCenteredText(matrices, this.textRenderer,
                Text.translatable("screen.universal_translator.llm.select_back"), this.width / 2, this.height - 30, 0xFFFFD060);
        drawCenteredText(matrices, this.textRenderer,
                Text.translatable("screen.universal_translator.llm.select_hint"), this.width / 2, this.height - 16, 0xA0A0A0);
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
        List<String> models = fetchedModels;
        int rows = rowsPerPage();
        modelPage = ModelListPaging.clampPage(modelPage, models.size(), rows);
        if (mouseY >= this.height - 54 && mouseY < this.height - 38) {
            int pages = ModelListPaging.pageCount(models.size(), rows);
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
        int row = ModelListPaging.rowAt(mouseY);
        int index = ModelListPaging.index(modelPage, rows, row);
        if (row >= rows || index >= models.size()) {
            return;
        }
        model.setText(models.get(index));
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
        return ModelListPaging.rows(this.height);
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
