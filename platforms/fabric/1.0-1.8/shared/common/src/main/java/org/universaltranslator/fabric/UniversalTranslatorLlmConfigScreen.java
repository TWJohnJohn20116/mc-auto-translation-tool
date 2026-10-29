package org.universaltranslator.fabric;

import org.universaltranslator.core.UiLimits;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.universaltranslator.core.ModelListPaging;
import org.universaltranslator.core.provider.OpenAiModelCatalog;
import org.universaltranslator.core.FocusHelper;

/**
 * OpenAI-compatible LLM settings for Fabric 1.3.1-1.8.8; 1.8.9-1.12.2 use the same-named file under
 * {@code platforms/fabric/1.8-1.12/shared/common}. The Forge legacy screens are separate classes in
 * {@code platforms/forge/shared/legacy-common}.
 */
final class UniversalTranslatorLlmConfigScreen extends Screen {
    /** Height of a single catalog row in the model picker overlay. */
    private static final int LIST_ROW_HEIGHT = ModelListPaging.ROW_HEIGHT;
    /** Y of the first catalog row; the picker title sits above it. */
    private static final int LIST_TOP = ModelListPaging.LIST_TOP;
    /** Room kept under the rows for the paging, back, and hint lines. */
    private static final int LIST_FOOTER_HEIGHT = ModelListPaging.FOOTER_HEIGHT;
    private static final int ESCAPE_KEY = 1;

    private static final int SAVE = 1;
    private static final int CANCEL = 2;
    private static final int FETCH = 3;

    private final UniversalTranslatorConfigScreen parent;
    private String initialEndpoint;
    private String initialModel;
    private String initialApiKey = "";
    private final boolean hasStoredKey;
    private TextRenderer renderer;
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
        // init() also runs on every resize (window drag, F11, GUI scale). Re-applying the
        // constructor snapshot silently discarded whatever had been typed, and the API key field had
        // no value at all - so a pasted key vanished on the first resize.
        if (endpoint != null) { initialEndpoint = endpoint.getText(); }
        if (model != null) { initialModel = model.getText(); }
        if (apiKey != null) { initialApiKey = apiKey.getText(); }
        buttons.clear();
        modelPage = 0;
        renderer = OrnitheClientAccess.textRenderer();
        int fieldWidth = Math.max(180, Math.min(360, width - 20));
        int left = (width - fieldWidth) / 2;
        int top = Math.max(42, (height - 174) / 2);
        endpoint = new TextFieldWidget(renderer, left, top, fieldWidth, 20);
        endpoint.setMaxLength(UiLimits.ENDPOINT_MAX_CHARS);
        endpoint.setText(initialEndpoint);
        model = new TextFieldWidget(renderer, left, top + 36, fieldWidth, 20);
        model.setMaxLength(UiLimits.MODEL_ID_MAX_CHARS);
        model.setText(initialModel);
        apiKey = new TextFieldWidget(renderer, left, top + 72, fieldWidth, 20);
        apiKey.setMaxLength(UiLimits.SECRET_MAX_CHARS);
        apiKey.setText(initialApiKey);
        int gap = 8;
        int buttonWidth = (fieldWidth - gap) / 2;
        buttons.add(new ButtonWidget(SAVE, left, top + 108, buttonWidth, 20,
                tr("screen.universal_translator.llm.save")));
        buttons.add(new ButtonWidget(
                CANCEL, left + buttonWidth + gap, top + 108, buttonWidth, 20, tr("gui.cancel")));
        buttons.add(new ButtonWidget(FETCH, left, top + 132, fieldWidth, 20,
                tr("screen.universal_translator.llm.fetch_models")));
    }

    @Override
    protected void buttonClicked(ButtonWidget button) {
        if (button.id == CANCEL) {
            OrnitheClientAccess.openScreen(parent);
            return;
        }
        if (button.id == FETCH) {
            fetchModels();
            return;
        }
        if (button.id != SAVE) {
            return;
        }
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
        OrnitheClientAccess.openScreen(parent);
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
                // A Throwable on purpose: the commonest failure on these mappings is a LinkageError,
                // and missing it left "fetching" set for the lifetime of the screen, so the button
                // did nothing at all afterwards with no message.
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
     * Takes the focus off every field while the catalog covers the form.
     *
     * <p>{@code charTyped} is delivered to the focused widget only, so a screen that merely stopped
     * drawing its fields still sent the typing into a field the player could not see.
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
        endpoint.tick();
        model.tick();
        apiKey.tick();
        applyPendingModelList();
    }

    @Override
    protected void keyPressed(char typedChar, int keyCode) {
        if (modelListOpen) {
            if (keyCode == ESCAPE_KEY) {
                modelListOpen = false;
            }
            return;
        }
        if (endpoint.keyPressed(typedChar, keyCode)
                || model.keyPressed(typedChar, keyCode)
                || apiKey.keyPressed(typedChar, keyCode)) {
            return;
        }
        super.keyPressed(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (modelListOpen) {
            handleModelListClick(mouseX, mouseY);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        endpoint.mouseClicked(mouseX, mouseY, mouseButton);
        model.mouseClicked(mouseX, mouseY, mouseButton);
        apiKey.mouseClicked(mouseX, mouseY, mouseButton);
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
    public void render(int mouseX, int mouseY, float partialTicks) {
        renderBackground();
        int fieldWidth = Math.max(180, Math.min(360, width - 20));
        int left = (width - fieldWidth) / 2;
        int top = Math.max(42, (height - 174) / 2);
        drawCenteredString(renderer, tr("screen.universal_translator.llm.title"), width / 2, 18, 0xFFFFFFFF);
        drawString(renderer, tr("screen.universal_translator.llm.endpoint_hint"), left, top - 11, 0xFFA0A0A0);
        drawString(renderer, tr("screen.universal_translator.llm.model_hint"), left, top + 25, 0xFFA0A0A0);
        drawString(renderer,
                tr(hasStoredKey
                        ? "screen.universal_translator.llm.key_saved_hint"
                        : "screen.universal_translator.llm.key_empty_hint"),
                left, top + 61, 0xFFA0A0A0);
        endpoint.render();
        model.render();
        apiKey.render();
        if (!status.isEmpty()) {
            // Explicit alpha on every colour: 1.3.1-1.5.2 has no "0 alpha means opaque" fallback in
            // the font renderer, so the bare RGB forms drew the whole page invisible there.
            drawCenteredString(renderer, status, width / 2, top + 158,
                    statusIsError ? 0xFFFF5555 : 0xFFE0E0E0);
        }
        super.render(mouseX, mouseY, partialTicks);
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
        fill(0, 0, width, height, 0xFF101010);
        drawCenteredString(renderer, tr("screen.universal_translator.llm.select_model", models.size()),
                width / 2, 20, 0xFFFFFFFF);
        int first = modelPage * rows;
        for (int row = 0; row < rows && first + row < models.size(); row++) {
            int rowY = LIST_TOP + row * LIST_ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + LIST_ROW_HEIGHT;
            drawString(renderer, (hovered ? "> " : "  ") + models.get(first + row),
                    24, rowY, hovered ? 0xFFFFD060 : 0xFFE0E0E0);
        }
        int pages = ModelListPaging.pageCount(models.size(), rows);
        drawCenteredString(renderer, "< " + (modelPage + 1) + "/" + pages + " >",
                width / 2, height - 46, 0xFFFFFFFF);
        drawCenteredString(renderer, tr("screen.universal_translator.llm.select_back"),
                width / 2, height - 30, 0xFFFFD060);
        drawCenteredString(renderer, tr("screen.universal_translator.llm.select_hint"),
                width / 2, height - 16, 0xFFA0A0A0);
    }

    private int rowsPerPage() {
        return ModelListPaging.rows(this.height);
    }

    @Override
    public boolean shouldPauseGame() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return OrnitheClientAccess.tr(key, arguments);
    }
}

