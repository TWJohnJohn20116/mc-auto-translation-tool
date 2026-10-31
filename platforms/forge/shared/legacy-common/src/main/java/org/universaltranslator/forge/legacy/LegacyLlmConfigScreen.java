package org.universaltranslator.forge.legacy;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import org.universaltranslator.core.ModelListPaging;
import org.universaltranslator.core.UiLimits;
import org.universaltranslator.core.provider.OpenAiModelCatalog;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/** OpenAI-compatible LLM settings shared by Forge 1.8.9 and 1.12.2. */
final class LegacyLlmConfigScreen extends GuiScreen {
    private static final int SAVE = 1;
    private static final int CANCEL = 2;
    private static final int FETCH = 3;
    /** Height of a single catalog row in the model picker overlay. */
    private static final int LIST_ROW_HEIGHT = ModelListPaging.ROW_HEIGHT;
    /** Y of the first catalog row; the picker title sits above it. */
    private static final int LIST_TOP = ModelListPaging.LIST_TOP;
    private static final int ESCAPE_KEY = 1;

    private final LegacyConfigScreen parent;
    private String initialEndpoint;
    private String initialModel;
    private String initialApiKey = "";
    private final boolean hasStoredKey;
    private FontRenderer renderer;
    private GuiTextField endpoint;
    private GuiTextField model;
    private GuiTextField apiKey;
    private volatile String status = "";
    private volatile boolean statusIsError;
    private volatile List<String> fetchedModels = Collections.emptyList();
    private volatile boolean fetching;
    private volatile boolean modelListOpen;
    private volatile int modelPage;

    LegacyLlmConfigScreen(
            LegacyConfigScreen parent,
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
    public void initGui() {
        buttonList.clear();
        // initGui() also runs on every resize (window drag, F11, GUI scale). Re-applying the
        // constructor snapshot silently discarded whatever had been typed, and the API key field had
        // no value at all - so a pasted key vanished on the first resize and Save then wrote the
        // previously stored key back.
        if (endpoint != null) {
            initialEndpoint = endpoint.getText();
        }
        if (model != null) {
            initialModel = model.getText();
        }
        if (apiKey != null) {
            initialApiKey = apiKey.getText();
        }
        renderer = LegacyVersionAccess.fontRenderer();
        int fieldWidth = Math.max(180, Math.min(360, width - 20));
        int left = (width - fieldWidth) / 2;
        int top = Math.max(42, (height - 174) / 2);
        endpoint = new GuiTextField(10, renderer, left, top, fieldWidth, 20);
        endpoint.setMaxStringLength(UiLimits.ENDPOINT_MAX_CHARS);
        endpoint.setText(initialEndpoint);
        model = new GuiTextField(11, renderer, left, top + 36, fieldWidth, 20);
        model.setMaxStringLength(UiLimits.MODEL_ID_MAX_CHARS);
        model.setText(initialModel);
        apiKey = new GuiTextField(12, renderer, left, top + 72, fieldWidth, 20);
        apiKey.setMaxStringLength(UiLimits.SECRET_MAX_CHARS);
        apiKey.setText(initialApiKey);
        int gap = 8;
        int buttonWidth = (fieldWidth - gap) / 2;
        buttonList.add(new GuiButton(SAVE, left, top + 108, buttonWidth, 20,
                tr("screen.universal_translator.llm.save")));
        buttonList.add(new GuiButton(
                CANCEL, left + buttonWidth + gap, top + 108, buttonWidth, 20, tr("gui.cancel")));
        buttonList.add(new GuiButton(FETCH, left, top + 132, fieldWidth, 20,
                tr("screen.universal_translator.llm.fetch_models")));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == CANCEL) {
            mc.displayGuiScreen(parent);
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
        try {
            // The same policy the connection test applies. Saving without it wrote an arbitrary
            // scheme, host and port into the endpoint every later translation request goes to.
            org.universaltranslator.core.net.EndpointPolicy.requireSafeEndpoint(endpointValue);
        } catch (RuntimeException rejected) {
            setStatus(describe(rejected), true);
            return;
        }
        String enteredKey = apiKey.getText().trim();
        String keyValue = enteredKey.isEmpty()
                ? parent.llmApiKey() : ("-".equals(enteredKey) ? "" : enteredKey);
        parent.applyLlmSettings(endpointValue, modelValue, keyValue);
        mc.displayGuiScreen(parent);
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
        LegacyScreenWorkers.execute(() -> {
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
            releaseFieldFocus();
            setStatus("", false);
        });
    }

    private void setStatus(String message, boolean error) {
        status = message;
        statusIsError = error;
    }

    /**
     * Drops the keyboard focus of every field while the catalog overlay owns the screen.
     *
     * <p>The overlay covers the form, but the fields underneath kept the focus, so a keystroke typed
     * into a field the player could not see and the next Save wrote it.
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

    private static String describe(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    /** Draws the fetched catalog over the form so a served model can be picked instead of typed. */
    private void renderModelList(int mouseX, int mouseY) {
        // Same snapshot rule as LegacyConfigScreen: the fetch worker replaces the volatile list
        // wholesale, so reading it twice (bound, then element) could throw
        // IndexOutOfBoundsException out of the render loop.
        List<String> models = fetchedModels;
        drawRect(0, 0, width, height, 0xFF101010);
        drawCenteredString(renderer, tr("screen.universal_translator.llm.select_model", models.size()),
                width / 2, 20, 0xFFFFFF);
        int rows = ModelListPaging.rows(height);
        modelPage = ModelListPaging.clampPage(modelPage, models.size(), rows);
        int first = modelPage * rows;
        for (int row = 0; row < rows && first + row < models.size(); row++) {
            int rowY = LIST_TOP + row * LIST_ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + LIST_ROW_HEIGHT;
            drawString(renderer, (hovered ? "> " : "  ") + models.get(first + row),
                    24, rowY, hovered ? 0xFFFFD060 : 0xFFE0E0E0);
        }
        int pages = ModelListPaging.pageCount(models.size(), rows);
        drawCenteredString(renderer, "< " + (modelPage + 1) + "/" + pages + " >",
                width / 2, height - 46, 0xFFFFFF);
        drawCenteredString(renderer, tr("screen.universal_translator.llm.select_back"),
                width / 2, height - 30, 0xFFFFD060);
        drawCenteredString(renderer, tr("screen.universal_translator.llm.select_hint"),
                width / 2, height - 16, 0xA0A0A0);
    }

    private void handleModelListClick(double mouseX, double mouseY) {
        List<String> models = fetchedModels;
        int rows = ModelListPaging.rows(height);
        if (mouseY >= height - 54 && mouseY < height - 38) {
            int pages = ModelListPaging.pageCount(models.size(), rows);
            if (pages > 1) {
                modelPage = mouseX < width / 2.0
                        ? (modelPage + pages - 1) % pages : (modelPage + 1) % pages;
            }
            return;
        }
        if (mouseY >= height - 38 && mouseY < height - 20) {
            modelListOpen = false;
            return;
        }
        if (mouseY < LIST_TOP) {
            return;
        }
        modelPage = ModelListPaging.clampPage(modelPage, models.size(), rows);
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
    public void updateScreen() {
        endpoint.updateCursorCounter();
        model.updateCursorCounter();
        apiKey.updateCursorCounter();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (modelListOpen) {
            if (keyCode == ESCAPE_KEY) {
                modelListOpen = false;
            }
            return;
        }
        if (endpoint.textboxKeyTyped(typedChar, keyCode)
                || model.textboxKeyTyped(typedChar, keyCode)
                || apiKey.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (modelListOpen) {
            handleModelListClick(mouseX, mouseY);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        endpoint.mouseClicked(mouseX, mouseY, mouseButton);
        model.mouseClicked(mouseX, mouseY, mouseButton);
        apiKey.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
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
        endpoint.drawTextBox();
        model.drawTextBox();
        apiKey.drawTextBox();
        if (!status.isEmpty()) {
            drawCenteredString(renderer, status, width / 2, top + 158,
                    statusIsError ? 0xFF5555 : 0xFFE0E0E0);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (modelListOpen) {
            renderModelList(mouseX, mouseY);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return I18n.format(key, arguments);
    }
}
