package org.universaltranslator.forge;

import org.universaltranslator.core.ModelListPaging;
import org.universaltranslator.core.UiLimits;
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
    // The picker geometry and the paging arithmetic are shared with the engine tab's picker; see
    // ModelListPaging. Copying the numbers here is what let the two pickers drift apart.
    /** Height of a single catalog row in the model picker overlay. */
    private static final int LIST_ROW_HEIGHT = ModelListPaging.ROW_HEIGHT;
    /** Y of the first catalog row; the picker title sits above it. */
    private static final int LIST_TOP = ModelListPaging.LIST_TOP;

    private final UniversalTranslatorConfigScreenBase parent;
    private String initialEndpoint;
    private String initialModel;
    private String initialApiKey = "";
    private final boolean hasStoredKey;
    private EditBox endpoint;
    private EditBox model;
    private EditBox apiKey;
    private volatile String status = "";
    private volatile boolean statusIsError;
    private volatile List<String> fetchedModels = Collections.emptyList();
    /** Catalog handed over by the worker; applied on the render thread by applyPendingModelList. */
    private volatile List<String> pendingModelList;
    private volatile boolean fetching;
    /** Written by the catalog worker, read and written by the render thread. */
    private volatile boolean modelListOpen;
    private volatile int modelPage;

    UniversalTranslatorLlmConfigScreen(
            UniversalTranslatorConfigScreenBase parent,
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
        // init() also runs on every resize (window drag, F11, GUI scale). Re-applying the
        // constructor snapshot silently discarded whatever had been typed, and the API key field had
        // no value at all - so a pasted key vanished on the first resize.
        if (endpoint != null) { initialEndpoint = endpoint.getValue(); }
        if (model != null) { initialModel = model.getValue(); }
        if (apiKey != null) { initialApiKey = apiKey.getValue(); }
int formWidth = Math.max(180, Math.min(360, this.width - 20));
        int left = (this.width - formWidth) / 2;
        int top = Math.max(42, (this.height - 174) / 2);
        endpoint = addRenderableWidget(new EditBox(
                this.font, left, top, formWidth, 20, Component.translatable("screen.universal_translator.llm.endpoint")));
        endpoint.setMaxLength(UiLimits.ENDPOINT_MAX_CHARS);
        endpoint.setValue(initialEndpoint);
        model = addRenderableWidget(new EditBox(
                this.font, left, top + 36, formWidth, 20, Component.translatable("screen.universal_translator.llm.model")));
        model.setMaxLength(UiLimits.MODEL_ID_MAX_CHARS);
        model.setValue(initialModel);
        apiKey = addRenderableWidget(new EditBox(
                this.font, left, top + 72, formWidth, 20, Component.translatable("screen.universal_translator.llm.api_key")));
        apiKey.setMaxLength(UiLimits.SECRET_MAX_CHARS);
        apiKey.setValue(initialApiKey);
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
        applyPendingModelList();
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
            OpenAiModelCatalog.Catalog catalog = null;
            String failure = "";
            try {
                catalog = OpenAiModelCatalog.fetchCatalog(endpointValue, keyValue);
            } catch (Exception error) {
                failure = describe(error);
            } finally {
                // Released in a finally: an Error is not an Exception, and the flag left set made
                // the fetch button silently do nothing for the rest of the screen's life.
                fetching = false;
            }
            if (!failure.isEmpty()) {
                setStatus(tr("screen.universal_translator.llm.fetch_failed", failure), true);
                return;
            }
            if (catalog == null) {
                // fetchCatalog answers null for a body it cannot parse instead of throwing.
                setStatus(tr("screen.universal_translator.engine.test_not_json"), true);
                return;
            }
            // A login page or a proxy error page also parses as "no models". The engine tab's
            // picker already says the answer was not JSON; this picker has to say the same thing.
            if (!catalog.jsonBody()) {
                setStatus(tr("screen.universal_translator.engine.test_not_json"), true);
                return;
            }
            List<String> models = catalog.models();
            if (models.isEmpty()) {
                setStatus(tr("screen.universal_translator.llm.fetch_empty"), true);
                return;
            }
            // Published for the render thread to apply in one step; see applyPendingModelList().
            pendingModelList = models;
            setStatus("", false);
        }, "universal-translator-model-list");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Applies a catalog the worker just published, on the render thread.
     *
     * <p>The worker used to write the list, the page index and the overlay flag itself. Those are
     * three separate writes to state the render thread reads as a whole, so a frame could pair the
     * new list with the previous page number. Applying it here also lets the keyboard focus be
     * dropped before the overlay covers the fields.
     */
    private void applyPendingModelList() {
        List<String> pending = pendingModelList;
        if (pending == null) {
            return;
        }
        pendingModelList = null;
        fetchedModels = pending;
        modelPage = 0;
        modelListOpen = true;
        releaseFieldFocusForModelList();
    }

    /**
     * Drops keyboard focus from the form fields before the picker covers them.
     *
     * <p>The picker is a painted rectangle, not a screen, so nothing intercepts typing while it is
     * open: whatever still holds focus collects the characters typed over the overlay, and Save then
     * writes them into the endpoint, the model or the API key. Focus is not restored afterwards on
     * purpose — the player clicks the field they want.
     */
    private void releaseFieldFocusForModelList() {
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

    private static String describe(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    /** Draws the fetched catalog over the form so a served model can be picked instead of typed. */
    private void renderModelList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // One read of the volatile list for the whole frame: the worker can replace it between the
        // loop bound and the get(), and the mismatch threw out of the render loop.
        List<String> models = fetchedModels;
        graphics.fill(0, 0, this.width, this.height, 0xFF101010);
        graphics.centeredText(this.font,
                Component.translatable("screen.universal_translator.llm.select_model", models.size()),
                this.width / 2, 20, 0xFFFFFF);
        int rows = rowsPerPage();
        // A resize lowers the row count while the picker stays open, so the page index can point
        // past the last page; clamping here is what keeps the overlay and its page counter honest.
        modelPage = ModelListPaging.clampPage(modelPage, models.size(), rows);
        int first = modelPage * rows;
        for (int row = 0; row < rows && first + row < models.size(); row++) {
            int rowY = LIST_TOP + row * LIST_ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + LIST_ROW_HEIGHT;
            graphics.text(this.font, Component.literal((hovered ? "> " : "  ") + models.get(first + row)),
                    24, rowY, hovered ? 0xFFFFD060 : 0xFFE0E0E0);
        }
        int pages = pageCount(models, rows);
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
        List<String> models = fetchedModels;
        if (mouseY >= this.height - 54 && mouseY < this.height - 38) {
            int pages = pageCount(models, rowsPerPage());
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
        modelPage = ModelListPaging.clampPage(modelPage, models.size(), rows);
        int row = ModelListPaging.rowAt(mouseY);
        int index = ModelListPaging.index(modelPage, rows, row);
        // row < 0 as well: rowAt answers a negative value above the list top, and a negative index
        // would pass the size check only because index(-1) happens to land below it today.
        if (row < 0 || row >= rows || index >= models.size()) {
            return;
        }
        model.setValue(models.get(index));
        modelListOpen = false;
        setStatus("", false);
    }

    private int rowsPerPage() {
        return ModelListPaging.rows(this.height);
    }

    private int pageCount(List<String> models, int rows) {
        return ModelListPaging.pageCount(models.size(), rows);
    }

    /**
     * ESC closes the picker before the screen.
     *
     * <p>Without this override the overlay had no keyboard exit and ESC dropped the whole form,
     * including an endpoint, a model and a key the player had just typed.
     */
    @Override
    public boolean shouldCloseOnEsc() {
        if (modelListOpen) {
            modelListOpen = false;
            return false;
        }
        return super.shouldCloseOnEsc();
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
