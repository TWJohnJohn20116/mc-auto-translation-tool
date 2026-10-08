package org.universaltranslator.forge.legacy;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
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
    private static final int LIST_ROW_HEIGHT = 12;
    /** Y of the first catalog row; the picker title sits above it. */
    private static final int LIST_TOP = 46;
    /** Room kept under the rows for the paging, back, and hint lines. */
    private static final int LIST_FOOTER_HEIGHT = 62;
    private static final int ESCAPE_KEY = 1;

    private final LegacyConfigScreen parent;
    private final String initialEndpoint;
    private final String initialModel;
    private final boolean hasStoredKey;
    private FontRenderer renderer;
    private GuiTextField endpoint;
    private GuiTextField model;
    private GuiTextField apiKey;
    private volatile String status = "";
    private volatile boolean statusIsError;
    private volatile List<String> fetchedModels = Collections.emptyList();
    private volatile boolean fetching;
    private boolean modelListOpen;
    private int modelPage;

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
        renderer = LegacyVersionAccess.fontRenderer();
        int fieldWidth = Math.max(180, Math.min(360, width - 20));
        int left = (width - fieldWidth) / 2;
        int top = Math.max(42, (height - 174) / 2);
        endpoint = new GuiTextField(10, renderer, left, top, fieldWidth, 20);
        endpoint.setMaxStringLength(512);
        endpoint.setText(initialEndpoint);
        model = new GuiTextField(11, renderer, left, top + 36, fieldWidth, 20);
        model.setMaxStringLength(128);
        model.setText(initialModel);
        apiKey = new GuiTextField(12, renderer, left, top + 72, fieldWidth, 20);
        apiKey.setMaxStringLength(512);
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
    private void renderModelList(int mouseX, int mouseY) {
        drawRect(0, 0, width, height, 0xFF101010);
        drawCenteredString(renderer, tr("screen.universal_translator.llm.select_model", fetchedModels.size()),
                width / 2, 20, 0xFFFFFF);
        int rows = rowsPerPage();
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

    private void handleModelListClick(double mouseX, double mouseY) {
        if (mouseY >= height - 54 && mouseY < height - 38) {
            int pages = pageCount(rowsPerPage());
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

    private int rowsPerPage() {
        return Math.max(3, (height - LIST_TOP - LIST_FOOTER_HEIGHT) / LIST_ROW_HEIGHT);
    }

    private int pageCount(int rows) {
        return Math.max(1, (fetchedModels.size() + rows - 1) / rows);
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
