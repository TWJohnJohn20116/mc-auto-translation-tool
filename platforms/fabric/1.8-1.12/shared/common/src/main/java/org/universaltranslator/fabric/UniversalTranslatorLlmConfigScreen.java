package org.universaltranslator.fabric;

import org.universaltranslator.core.ModelListPaging;
import org.universaltranslator.core.UiLimits;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.resource.language.I18n;
import org.universaltranslator.core.provider.OpenAiModelCatalog;

/**
 * OpenAI-compatible LLM settings for the 1.8.9-1.12.2 Fabric builds.
 *
 * <p>It used to claim it was shared with Forge 1.8.9 and 1.12.2. It is not: those build from
 * {@code platforms/forge/shared/legacy-common} and their own {@code Legacy*Screen} classes, so the
 * comment sent readers to the wrong file (audit S2-095).
 */
final class UniversalTranslatorLlmConfigScreen extends Screen {
    /** Height of a single catalog row in the model picker overlay. */
    private static final int LIST_ROW_HEIGHT = 12;
    /** Y of the first catalog row; the picker title sits above it. */
    private static final int LIST_TOP = 46;
    /** Room kept under the rows for the paging, back, and hint lines. */
    private static final int LIST_FOOTER_HEIGHT = 62;
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
    /**
     * A fetched catalog waiting for the client thread to open the picker with it.
     *
     * <p>The worker used to assign {@code modelPage} and {@code modelListOpen} itself, so the overlay
     * could come up while {@code fetchedModels} and the page index disagreed; applying the result from
     * {@link #tick()} keeps both on the client thread.
     */
    private volatile List<String> pendingModels;
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
        renderer = OrnitheClientAccess.textRenderer();
        int fieldWidth = Math.max(180, Math.min(360, width - 20));
        int left = (width - fieldWidth) / 2;
        int top = Math.max(42, (height - 174) / 2);
        endpoint = new TextFieldWidget(10, renderer, left, top, fieldWidth, 20);
        endpoint.setMaxLength(UiLimits.ENDPOINT_MAX_CHARS);
        endpoint.setText(initialEndpoint);
        model = new TextFieldWidget(11, renderer, left, top + 36, fieldWidth, 20);
        model.setMaxLength(UiLimits.MODEL_ID_MAX_CHARS);
        model.setText(initialModel);
        apiKey = new TextFieldWidget(12, renderer, left, top + 72, fieldWidth, 20);
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
        ScreenWorkers.execute(() -> {
            List<String> models = Collections.emptyList();
            String failure = "";
            try {
                models = OpenAiModelCatalog.fetch(endpointValue, keyValue);
            } catch (Exception error) {
                failure = describe(error);
            } finally {
                // Released in a finally: a LinkageError / NoClassDefFoundError from a provider class
                // is not an Exception, and releasing the guard at the end of the lambda body left the
                // fetch button returning immediately for the rest of the screen's life with no
                // message — the only way out was closing and reopening the screen.
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
            // Handed to the client thread instead of touching modelPage/modelListOpen here.
            pendingModels = models;
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
    public void tick() {
        endpoint.tick();
        model.tick();
        apiKey.tick();
        applyPendingModels();
    }

    /** Opens the picker once the worker has published a catalog; client thread only. */
    private void applyPendingModels() {
        List<String> pending = pendingModels;
        if (pending == null) {
            return;
        }
        pendingModels = null;
        fetchedModels = pending;
        modelPage = 0;
        modelListOpen = true;
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
    public void render(int mouseX, int mouseY, float partialTicks) {
        renderBackground();
        int fieldWidth = Math.max(180, Math.min(360, width - 20));
        int left = (width - fieldWidth) / 2;
        int top = Math.max(42, (height - 174) / 2);
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
            drawCenteredString(renderer, status, width / 2, top + 158,
                    statusIsError ? 0xFF5555 : 0xFFE0E0E0);
        }
        super.render(mouseX, mouseY, partialTicks);
        if (modelListOpen) {
            renderModelList(mouseX, mouseY);
        }
    }

    /** Draws the fetched catalog over the form so a served model can be picked instead of typed. */
    private void renderModelList(int mouseX, int mouseY) {
        fill(0, 0, width, height, 0xFF101010);
        drawCenteredString(renderer, tr("screen.universal_translator.llm.select_model", fetchedModels.size()),
                width / 2, 20, 0xFFFFFF);
        int rows = rowsPerPage();
        // The window can shrink after the page was picked (resize, lower GUI scale), so the page index
        // is clamped before use; otherwise the header could read "3/1" and the list drew nothing.
        modelPage = ModelListPaging.clampPage(modelPage, fetchedModels.size(), rows);
        int first = modelPage * rows;
        for (int row = 0; row < rows && first + row < fetchedModels.size(); row++) {
            int rowY = LIST_TOP + row * LIST_ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + LIST_ROW_HEIGHT;
            drawString(renderer, (hovered ? "> " : "  ") + fetchedModels.get(first + row),
                    24, rowY, hovered ? 0xFFFFD060 : 0xFFE0E0E0);
        }
        int pages = pageCount(rows);
        drawCenteredString(renderer, "< " + (modelPage + 1) + "/" + pages + " >",
                width / 2, height - 46, 0xFFFFFF);
        drawCenteredString(renderer, tr("screen.universal_translator.llm.select_back"),
                width / 2, height - 30, 0xFFFFD060);
        drawCenteredString(renderer, tr("screen.universal_translator.llm.select_hint"),
                width / 2, height - 16, 0xA0A0A0);
    }

    private int rowsPerPage() {
        return Math.max(3, (this.height - LIST_TOP - LIST_FOOTER_HEIGHT) / LIST_ROW_HEIGHT);
    }

    private int pageCount(int rows) {
        return Math.max(1, (fetchedModels.size() + rows - 1) / rows);
    }

    @Override
    public boolean shouldPauseGame() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return I18n.translate(key, arguments);
    }
}

