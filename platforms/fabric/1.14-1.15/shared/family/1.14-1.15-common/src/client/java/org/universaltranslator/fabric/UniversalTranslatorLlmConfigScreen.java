package org.universaltranslator.fabric;

import org.universaltranslator.core.UiLimits;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.text.LiteralText;
import org.universaltranslator.core.ModelListPaging;
import org.universaltranslator.core.provider.OpenAiModelCatalog;

/** Local-only editor for an OpenAI-compatible hosted or loopback LLM endpoint. */
final class UniversalTranslatorLlmConfigScreen extends Screen {
    /** Height of a single catalog row in the model picker overlay. */
    private static final int LIST_ROW_HEIGHT = ModelListPaging.ROW_HEIGHT;
    /** Y of the first catalog row; the picker title sits above it. */
    private static final int LIST_TOP = ModelListPaging.LIST_TOP;
    /** Room kept under the rows for the paging, back, and hint lines. */
    private static final int LIST_FOOTER_HEIGHT = ModelListPaging.FOOTER_HEIGHT;
    private static final int ESCAPE_KEY = 256;

    /**
     * The screen that opened this one. Typed against the shared body rather than the concrete
     * settings screen so this file does not have to name a per-generation class.
     */
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
    /** Handed over by the fetch worker; applied on the client thread by {@link #tick()}. */
    private volatile List<String> pendingModels;
    private volatile String pendingFailure = "";
    private boolean modelListOpen;
    private int modelPage;

    UniversalTranslatorLlmConfigScreen(
            UniversalTranslatorSettingsScreen parent,
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
        // init() also runs on every resize (window drag, F11, GUI scale). Re-applying the
        // constructor snapshot silently discarded whatever had been typed, and the API key field had
        // no value at all - so a pasted key vanished on the first resize.
        if (endpoint != null) { initialEndpoint = endpoint.getText(); }
        if (model != null) { initialModel = model.getText(); }
        if (apiKey != null) { initialApiKey = apiKey.getText(); }
        modelPage = 0;
int width = Math.max(180, Math.min(360, this.width - 20));
        int left = (this.width - width) / 2;
        int top = Math.max(42, (this.height - 174) / 2);
        endpoint = addDrawableChild(new TextFieldWidget(
                this.font, left, top, width, 20, tr("screen.universal_translator.llm.endpoint")));
        endpoint.setMaxLength(UiLimits.ENDPOINT_MAX_CHARS);
        endpoint.setText(initialEndpoint);
        model = addDrawableChild(new TextFieldWidget(
                this.font, left, top + 36, width, 20, tr("screen.universal_translator.llm.model")));
        model.setMaxLength(UiLimits.MODEL_ID_MAX_CHARS);
        model.setText(initialModel);
        apiKey = addDrawableChild(new TextFieldWidget(
                this.font, left, top + 72, width, 20, tr("screen.universal_translator.llm.api_key")));
        apiKey.setMaxLength(UiLimits.SECRET_MAX_CHARS);
        apiKey.setText(initialApiKey);
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

    private <T extends net.minecraft.client.gui.widget.AbstractButtonWidget> T addDrawableChild(T child) {
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
            List<String> models = null;
            String failure = "";
            try {
                models = OpenAiModelCatalog.fetch(endpointValue, keyValue);
            } catch (Throwable error) {
                // A Throwable on purpose: the commonest failure on this range is a LinkageError, and
                // missing it left "fetching" set for the lifetime of the screen.
                failure = describe(error);
            } finally {
                fetching = false;
            }
            if (models == null && failure.isEmpty()) {
                // fetch answers null when the body was not parseable; reading it straight away used
                // to NPE on the worker thread, where the player never saw anything.
                failure = tr("screen.universal_translator.engine.test_not_json");
            }
            if (!failure.isEmpty()) {
                pendingFailure = failure;
                return;
            }
            pendingModels = models;
        }, "universal-translator-model-list");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Applies the catalog the worker fetched, on the client thread.
     *
     * <p>The worker wrote the list, the page and the overlay flag itself, so the render thread could
     * see the overlay opened before its rows were published and draw an empty page.
     */
    private void applyPendingModelList() {
        List<String> models = pendingModels;
        String failure = pendingFailure;
        if (models == null && failure.isEmpty()) {
            return;
        }
        pendingModels = null;
        pendingFailure = "";
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
        releaseFieldFocus();
        setStatus("", false);
    }

    /**
     * Takes the focus off every field while the catalog covers the form, so typing cannot land in a
     * field the player cannot see.
     */
    private void releaseFieldFocus() {
        if (endpoint != null) {
            endpoint.setFocused(false);
        }
        if (model != null) {
            model.setFocused(false);
        }
        if (apiKey != null) {
            apiKey.setFocused(false);
        }
    }

    private void setStatus(String message, boolean error) {
        status = message;
        statusIsError = error;
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    @Override
    public void tick() {
        super.tick();
        applyPendingModelList();
    }

    @Override
    public void render(int mouseX, int mouseY, float delta) {
        // Screen.render only draws the children, so without this the world or the previous screen
        // stayed visible behind the form.
        renderBackground();
        int width = Math.max(180, Math.min(360, this.width - 20));
        int left = (this.width - width) / 2;
        int top = Math.max(42, (this.height - 174) / 2);
        drawCenteredString(this.font, this.title.getString(), this.width / 2, 18, 0xFFFFFF);
        drawString(this.font, new TranslatableText("screen.universal_translator.llm.endpoint_hint").getString(), left, top - 11, 0xA0A0A0);
        drawString(this.font, new TranslatableText("screen.universal_translator.llm.model_hint").getString(),
                left, top + 25, 0xA0A0A0);
        drawString(this.font, new TranslatableText(hasStoredKey
                        ? "screen.universal_translator.llm.key_saved_hint"
                        : "screen.universal_translator.llm.key_empty_hint").getString(),
                left, top + 61, 0xA0A0A0);
        if (!status.isEmpty()) {
            drawCenteredString(this.font, new LiteralText(status).getString(),
                    this.width / 2, top + 158, statusIsError ? 0xFF5555 : 0xFFE0E0E0);
        }
        super.render(mouseX, mouseY, delta);
        if (modelListOpen) {
            renderModelList(mouseX, mouseY);
        }
    }

    /** Draws the fetched catalog over the form so a served model can be picked instead of typed. */
    private void renderModelList(int mouseX, int mouseY) {
        // A local snapshot: the worker republished the whole list, so the loop condition and the
        // get() below could otherwise read two different lists and run off the short one.
        List<String> models = fetchedModels;
        int rows = rowsPerPage();
        modelPage = ModelListPaging.clampPage(modelPage, models.size(), rows);
        fill(0, 0, this.width, this.height, 0xFF101010);
        drawCenteredString(this.font,
                new TranslatableText("screen.universal_translator.llm.select_model", models.size()).getString(),
                this.width / 2, 20, 0xFFFFFFFF);
        int first = modelPage * rows;
        for (int row = 0; row < rows && first + row < models.size(); row++) {
            int rowY = LIST_TOP + row * LIST_ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + LIST_ROW_HEIGHT;
            drawString(this.font, (hovered ? "> " : "  ") + models.get(first + row),
                    24, rowY, hovered ? 0xFFFFD060 : 0xFFE0E0E0);
        }
        int pages = ModelListPaging.pageCount(models.size(), rows);
        drawCenteredString(this.font, "< " + (modelPage + 1) + "/" + pages + " >",
                this.width / 2, this.height - 46, 0xFFFFFFFF);
        drawCenteredString(this.font, new TranslatableText("screen.universal_translator.llm.select_back").getString(),
                this.width / 2, this.height - 30, 0xFFFFD060);
        drawCenteredString(this.font, new TranslatableText("screen.universal_translator.llm.select_hint").getString(),
                this.width / 2, this.height - 16, 0xFFA0A0A0);
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
        if (mouseY >= this.height - 48 && mouseY < this.height - 36) {
            int pages = ModelListPaging.pageCount(models.size(), rows);
            if (pages > 1) {
                modelPage = mouseX < this.width / 2.0
                        ? (modelPage + pages - 1) % pages : (modelPage + 1) % pages;
            }
            return;
        }
        if (mouseY >= this.height - 34 && mouseY < this.height - 18) {
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
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.openScreen(parent);
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
