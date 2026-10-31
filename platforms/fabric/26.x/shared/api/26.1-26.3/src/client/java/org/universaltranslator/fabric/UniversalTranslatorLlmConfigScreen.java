package org.universaltranslator.fabric;

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
    /** Height of a single catalog row in the model picker overlay. */
    private static final int LIST_ROW_HEIGHT = ModelListPaging.ROW_HEIGHT;
    /** Y of the first catalog row; the picker title sits above it. */
    private static final int LIST_TOP = ModelListPaging.LIST_TOP;

    private final UniversalTranslatorConfigScreen parent;
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
    private volatile boolean fetching;
    // Written by the worker that fetches the catalog and read by the render thread, so both need
    // the same visibility the list itself already had.
    private volatile boolean modelListOpen;
    private volatile int modelPage;

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
        ScreenWorkerPool.execute(() -> {
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
            // The focus guard now runs in extractRenderState while the overlay is open: touching a
            // child widget from this thread raced init() (every resize rebuilds the three fields)
            // and dropped focus on instances that were already gone.
            setStatus("", false);
        });
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
            releaseFieldFocus();
            renderModelList(graphics, mouseX, mouseY);
        } else {
            restoreFieldEditing();
        }
    }

    /**
     * Keeps the form from collecting characters while the picker covers it.
     *
     * <p>Applied on every frame the overlay is open rather than once when the list arrives: a
     * resize rebuilds the three fields in {@code init()}, and a one-shot call would leave the fresh
     * instances accepting input, so the characters typed over the overlay went into the hidden API
     * key field again.
     *
     * <p>Clearing a field's own focus needs an accessor the game renames at runtime, so the
     * reflective call this used to make found nothing and did not guard anything. The editability
     * switch exists on every generation and stops the characters at the field itself;
     * {@link #restoreFieldEditing()} hands it back when the picker closes.
     */
    private void releaseFieldFocus() {
        setFieldEditing(endpoint, false);
        setFieldEditing(model, false);
        setFieldEditing(apiKey, false);
    }

    /** Gives the form its editing back once the picker is gone. */
    private void restoreFieldEditing() {
        setFieldEditing(endpoint, true);
        setFieldEditing(model, true);
        setFieldEditing(apiKey, true);
    }

    private static void setFieldEditing(EditBox field, boolean editable) {
        if (field != null) {
            field.setEditable(editable);
        }
    }

    /** Draws the fetched catalog over the form so a served model can be picked instead of typed. */
    private void renderModelList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // Snapshot the list once: the worker replaces it wholesale, so reading size() and get()
        // through the volatile field separately can mix two different lists and throw on the
        // render thread.
        List<String> models = fetchedModels;
        int rows = ModelListPaging.rows(this.height);
        // The overlay survives a resize, which lowers the page count, so clamp before drawing.
        modelPage = ModelListPaging.clampPage(modelPage, models.size(), rows);
        graphics.fill(0, 0, this.width, this.height, 0xFF101010);
        graphics.centeredText(this.font,
                Component.translatable("screen.universal_translator.llm.select_model", models.size()),
                this.width / 2, 20, 0xFFFFFF);
        int first = modelPage * rows;
        for (int row = 0; row < rows && first + row < models.size(); row++) {
            int rowY = LIST_TOP + row * LIST_ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + LIST_ROW_HEIGHT;
            graphics.text(this.font,
                    Component.literal((hovered ? "> " : "  ") + models.get(first + row)),
                    24, rowY, hovered ? 0xFFFFD060 : 0xFFE0E0E0);
        }
        int pages = ModelListPaging.pageCount(models.size(), rows);
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
            // `|| super` keeps the "did the overlay consume this click" contract: a click it did
            // not take still reaches the form. The overlay used to swallow every click, including
            // the ones it did nothing with.
            return handleModelListClick(event.x(), event.y())
                    || super.mouseClicked(event, doubleClick);
        }
        return super.mouseClicked(event, doubleClick);
    }

    /** @return true when the overlay consumed the click, false when it declined to. */
    private boolean handleModelListClick(double mouseX, double mouseY) {
        List<String> models = fetchedModels;
        int rows = ModelListPaging.rows(this.height);
        modelPage = ModelListPaging.clampPage(modelPage, models.size(), rows);
        if (mouseY >= this.height - 54 && mouseY < this.height - 38) {
            int pages = ModelListPaging.pageCount(models.size(), rows);
            if (pages > 1) {
                modelPage = mouseX < this.width / 2.0
                        ? (modelPage + pages - 1) % pages : (modelPage + 1) % pages;
            }
            return true;
        }
        if (mouseY >= this.height - 38 && mouseY < this.height - 20) {
            modelListOpen = false;
            return true;
        }
        if (mouseY < LIST_TOP) {
            // Above the rows: close the picker and let the click through.
            modelListOpen = false;
            return false;
        }
        int row = ModelListPaging.rowAt(mouseY);
        int index = ModelListPaging.index(modelPage, rows, row);
        if (row < 0 || row >= rows || index >= models.size()) {
            // Past the last row: close rather than swallow, so the player cannot get stuck behind
            // an overlay whose only exit was an 18-pixel strip at the bottom.
            modelListOpen = false;
            return false;
        }
        model.setValue(models.get(index));
        modelListOpen = false;
        setStatus("", false);
        return true;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            FabricScreenOpener.open(this.minecraft, parent);
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
