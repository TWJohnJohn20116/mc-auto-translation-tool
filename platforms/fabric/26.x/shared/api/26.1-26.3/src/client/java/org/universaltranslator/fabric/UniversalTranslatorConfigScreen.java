package org.universaltranslator.fabric;

import org.universaltranslator.core.ModelListPaging;
import java.net.URI;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.universaltranslator.core.HudIndicatorColor;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HudIndicatorSettings;
import org.universaltranslator.core.HudIndicatorVisibility;
import org.universaltranslator.core.TranslationDisplayMode;
import org.universaltranslator.core.OfflineModel;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.core.TranslationPrompt;
import org.universaltranslator.core.TranslationQuality;
import org.universaltranslator.core.TranslationStatusLocalizer;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationProviderCatalog;
import org.universaltranslator.core.SettingsUiAnimation;
import org.universaltranslator.core.SettingsScreenLayout;
import org.universaltranslator.core.SettingsSelectionList;
import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.JsonStrings;
import org.universaltranslator.core.provider.OpenAiModelCatalog;
import org.universaltranslator.core.provider.ProviderProbe;
import org.universaltranslator.core.UiTextKeys;
import org.universaltranslator.core.UiLimits;

/** Minimal dependency-free settings screen, opened with U by default. */
final class UniversalTranslatorConfigScreen extends Screen {
    /** The preview panel is a miniature of Minecraft's 320x240 minimum logical screen. */
    private static final int HUD_PREVIEW_SCREEN_WIDTH = 320;
    private static final int HUD_PREVIEW_SCREEN_HEIGHT = 240;
    /** Tallest the preview panel may be. It shrinks on short windows so it never hits Save. */
    private static final int HUD_DRAG_PREVIEW_HEIGHT = 48;
    private static final int HUD_DRAG_HANDLE_SIZE = 8;
    /** Height of one row in the engine tab's model picker overlay. */
    private static final int MODEL_ROW_HEIGHT = ModelListPaging.ROW_HEIGHT;
    /** Y of the first model row; the picker title sits above it. */
    private static final int MODEL_LIST_TOP = ModelListPaging.LIST_TOP;
    /** Room kept under the model rows for the paging, back, and hint lines. */
    private static final int MODEL_LIST_FOOTER_HEIGHT = ModelListPaging.FOOTER_HEIGHT;
    private static final String LIBRETRANSLATE_PROVIDER = "libretranslate";
    private static final String TRANSLATE_PATH = "/translate";
    private static final String LANGUAGES_PATH = "/languages";

    private enum Tab {
        GENERAL,
        SCOPES,
        ENGINE,
        OUTGOING,
        HUD
    }

    private final Screen parent;
    private final FabricConfig original;
    private boolean enabled;
    private boolean translateChat;
    private boolean translateOther;
    private boolean translateVanilla;
    private boolean translateOutgoing;
    private boolean translatePlayerNames;
    private boolean animatedUi;
    private boolean hudIndicator;
    private boolean diskCache;
    private boolean offlineAutoDownload;
    private OfflineModel offlineModel;
    private boolean apiFallback;
    private TranslationDisplayMode displayMode;
    private boolean translateEnglishOnly;
    private TranslationTextColor translatedTextColor;
    private HudIndicatorCorner hudIndicatorCorner;
    private int hudIndicatorSize;
    private int hudIndicatorMargin;
    private HudIndicatorColor hudIndicatorColor;
    private HudIndicatorContent hudIndicatorContent;
    private HudIndicatorVisibility hudIndicatorVisibility;
    private int hudIndicatorOffsetX;
    private int hudIndicatorOffsetY;
    private boolean hudDragging;
    private double hudDragLastX;
    private double hudDragLastY;
    private String provider;
    private String llmEndpoint;
    private String llmApiKey;
    private String llmModel;
    private String targetLanguage;
    private String outgoingTargetLanguage;

    private Tab activeTab = Tab.GENERAL;
    private Button tabGeneralButton;
    private Button tabScopesButton;
    private Button tabEngineButton;
    private Button tabOutgoingButton;
    private Button tabHudButton;
    private Button llmConfigButton;
    private Button modelPickButton;
    private Button fetchModelsButton;
    private Button testConnectionButton;
    private Button checkSettingsButton;

    /** Selected quality mode; the shared Save writes it and Apply commits it immediately. */
    private TranslationQuality quality;
    private Button qualityButton;
    /** Worker threads used by the translation pipeline; the shared Save writes it. */
    private int workers;
    private Button workersButton;
    /** Sentences grouped into one translation request; the shared Save writes it. */
    private int batchSize;
    private Button batchSizeButton;
    private EditBox customPrompt;
    private Button applyPromptButton;
    private EditBox endpoint;
    private EditBox blockedKeywords;
    private Button enabledButton;
    private Button uiStyleButton;
    private Button hudIndicatorButton;
    private Button hudCornerButton;
    private Button hudSizeButton;
    private Button hudMarginButton;
    private Button hudColorButton;
    private Button hudContentButton;
    private Button hudVisibilityButton;
    private Button chatButton;
    private Button otherButton;
    private Button vanillaButton;
    private Button playerNamesButton;
    private Button cacheButton;
    private Button providerButton;
    private Button displayButton;
    private Button downloadButton;
    private Button modelButton;
    private Button fallbackButton;
    private Button diagnosticsButton;
    private Button mixedTextButton;
    private Button colorButton;
    private Button outgoingButton;
    private Button targetLanguageButton;
    private Button outgoingTargetLanguageButton;
    private String status = "";
    /** Result of the engine tab's connection test; written by its worker thread. */
    private volatile String testStatus = "";
    private volatile boolean testStatusIsError;
    // Cleared by the test worker and read by the next click on the render thread: without the
    // barrier the button can stay dead for the session even though the worker finished.
    private volatile boolean testing;
    private volatile boolean fetchingModels;
    private volatile List<String> fetchedModels = Collections.emptyList();
    private volatile boolean modelListOpen;
    private volatile int modelPage;
    /** Last computed layout, reused until the window size changes. */
    private Layout cachedLayout;
    private int cachedLayoutWidth = -1;
    private int cachedLayoutHeight = -1;
    /**
     * The localized runtime status, refreshed at most five times a second.
     *
     * <p>Building it concatenates the provider status and the last failure, and localizing walks a
     * dozen prefixes plus a language-table lookup before anything is drawn. The screen re-renders
     * sixty times a second whether or not the status changed. {@code null} means "not computed yet".
     */
    private static final long STATUS_CACHE_MILLIS = 200L;
    private String cachedLocalizedStatus;
    private boolean cachedLocalizedStatusIsFailure;
    private long cachedLocalizedStatusAt;
    private long animationStartedNanos = System.nanoTime();
    private SettingsSelectionList.Kind openSelection = SettingsSelectionList.Kind.NONE;

    UniversalTranslatorConfigScreen(Screen parent, FabricConfig config) {
        super(Component.translatable("screen.universal_translator.settings.title"));
        this.parent = parent;
        this.original = config;
        this.enabled = config.enabled;
        this.translateChat = config.translateChat;
        this.translateOther = config.translateOther;
        this.translateVanilla = config.translateVanilla;
        this.translateOutgoing = config.translateOutgoing;
        this.translatePlayerNames = config.translatePlayerNames;
        this.animatedUi = config.animatedUi;
        this.hudIndicator = config.hudIndicator;
        this.diskCache = config.diskCache;
        this.offlineAutoDownload = config.offlineAutoDownload;
        this.offlineModel = config.offlineModel;
        this.apiFallback = config.apiFallback;
        this.displayMode = config.displayMode;
        this.translateEnglishOnly = config.translateEnglishOnly;
        this.translatedTextColor = config.translatedTextColor;
        this.hudIndicatorCorner = config.hudIndicatorCorner;
        this.hudIndicatorSize = config.hudIndicatorSize;
        this.hudIndicatorMargin = config.hudIndicatorMargin;
        this.hudIndicatorColor = config.hudIndicatorColor;
        this.hudIndicatorContent = config.hudIndicatorContent;
        this.hudIndicatorVisibility = config.hudIndicatorVisibility;
        this.hudIndicatorOffsetX = config.hudIndicatorOffsetX;
        this.hudIndicatorOffsetY = config.hudIndicatorOffsetY;
        this.provider = config.provider;
        this.llmEndpoint = config.editorEndpoint(config.provider);
        this.llmApiKey = config.editorApiKey(config.provider);
        this.llmModel = config.editorModel(config.provider);
        this.quality = config.translationQuality();
        this.workers = config.translationWorkers();
        this.batchSize = config.batchSize();
    }

    @Override
    protected void init() {
        if (targetLanguage == null) {
            targetLanguage = TargetLanguage.canonicalize(original.targetLanguage);
        }
        String endpointValue = endpoint == null ? original.endpoint : endpoint.getValue();
        String blockedKeywordsValue = blockedKeywords == null
                ? original.blockedKeywords : blockedKeywords.getValue();
        if (outgoingTargetLanguage == null) {
            outgoingTargetLanguage = TargetLanguage.canonicalize(original.outgoingTargetLanguage);
        }
        Layout layout = layout();
        int left = layout.left;

        // Navigation Tabs (positioned right below header at tabY)
        tabGeneralButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            activeTab = Tab.GENERAL;
            updateTabVisibility();
        }).bounds(layout.tabX(0), layout.tabY, layout.tabWidth, 20).build());

        tabScopesButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            activeTab = Tab.SCOPES;
            updateTabVisibility();
        }).bounds(layout.tabX(1), layout.tabY, layout.tabWidth, 20).build());

        tabEngineButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            activeTab = Tab.ENGINE;
            updateTabVisibility();
        }).bounds(layout.tabX(2), layout.tabY, layout.tabWidth, 20).build());

        tabOutgoingButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            activeTab = Tab.OUTGOING;
            updateTabVisibility();
        }).bounds(layout.tabX(3), layout.tabY, layout.tabWidth, 20).build());

        tabHudButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            activeTab = Tab.HUD;
            updateTabVisibility();
        }).bounds(layout.tabX(4), layout.tabY, layout.tabWidth, 20).build());

        // --- Tab 1: General (常規) ---
        enabledButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            enabled = !enabled;
            refreshLabels();
        }).bounds(left, layout.contentRow(0), layout.buttonWidth, 20).build());

        targetLanguageButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            openSelection = SettingsSelectionList.Kind.TARGET_LANGUAGE;
        }).bounds(layout.right, layout.contentRow(0), layout.buttonWidth, 20).build());

        displayButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            displayMode = displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                    ? TranslationDisplayMode.TRANSLATED_ONLY
                    : TranslationDisplayMode.ORIGINAL_AND_TRANSLATED;
            refreshLabels();
        }).bounds(left, layout.contentRow(1), layout.buttonWidth, 20).build());

        colorButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            translatedTextColor = translatedTextColor.next();
            refreshLabels();
        }).bounds(layout.right, layout.contentRow(1), layout.buttonWidth, 20).build());

        cacheButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            diskCache = !diskCache;
            refreshLabels();
        }).bounds(left, layout.contentRow(2), layout.buttonWidth, 20).build());

        uiStyleButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            animatedUi = !animatedUi;
            animationStartedNanos = System.nanoTime();
            refreshLabels();
        }).bounds(layout.right, layout.contentRow(2), layout.buttonWidth, 20).build());

        diagnosticsButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.universal_translator.diagnostics.title"), button -> {
            if (minecraft != null) {
                FabricScreenOpener.open(minecraft, new UniversalTranslatorDiagnosticsScreen(this));
            }
        }).bounds(left, layout.contentRow(3), layout.totalWidth, 20).build());

        // --- Tab 2: Scopes (範圍) ---
        chatButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            translateChat = !translateChat;
            refreshLabels();
        }).bounds(left, layout.contentRow(0), layout.buttonWidth, 20).build());

        otherButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            translateOther = !translateOther;
            refreshLabels();
        }).bounds(layout.right, layout.contentRow(0), layout.buttonWidth, 20).build());

        vanillaButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            translateVanilla = !translateVanilla;
            refreshLabels();
        }).bounds(left, layout.contentRow(1), layout.buttonWidth, 20).build());

        playerNamesButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            translatePlayerNames = !translatePlayerNames;
            refreshLabels();
        }).bounds(layout.right, layout.contentRow(1), layout.buttonWidth, 20).build());

        mixedTextButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            translateEnglishOnly = !translateEnglishOnly;
            refreshLabels();
        }).bounds(left, layout.contentRow(2), layout.totalWidth, 20).build());

        blockedKeywords = addRenderableWidget(new EditBox(
                this.font, left, layout.contentRow(3), layout.totalWidth, 20,
                Component.translatable("screen.universal_translator.blocked_keywords")));
        blockedKeywords.setMaxLength(UiLimits.KEYWORDS_MAX_CHARS);
        blockedKeywords.setValue(blockedKeywordsValue);
        blockedKeywords.setHint(Component.translatable(
                "screen.universal_translator.blocked_keywords_hint",
                UiLimits.KEYWORDS_MAX_ENTRIES, UiLimits.KEYWORD_MAX_CHARS));

        // --- Tab 3: Engine (引擎) ---
        // The engine rows are centred in the free band (see engineRow) so the tab does not sit in
        // the top third of an otherwise empty panel, and every row is reachable without moving the
        // shared Save/Cancel row that the other tabs and the HUD preview depend on.
        providerButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            openSelection = SettingsSelectionList.Kind.PROVIDER;
        }).bounds(left, engineRow(0), layout.totalWidth, 20).build());

        modelButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            offlineModel = offlineModel.next();
            refreshLabels();
        }).bounds(left, engineRow(1), layout.buttonWidth, 20).build());

        downloadButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            offlineAutoDownload = !offlineAutoDownload;
            refreshLabels();
        }).bounds(layout.right, engineRow(1), layout.buttonWidth, 20).build());

        modelPickButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            if (fetchedModels.isEmpty()) {
                fetchModels();
            } else {
                modelListOpen = true;
                releaseFieldFocusForModelList();
            }
        }).bounds(left, engineRow(1), layout.buttonWidth, 20).build());

        fetchModelsButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.universal_translator.llm.fetch_models"), button -> fetchModels())
                .bounds(layout.right, engineRow(1), layout.buttonWidth, 20).build());

        llmConfigButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.universal_translator.option.llm_settings"), button -> {
            if (this.minecraft != null) {
                FabricScreenOpener.open(this.minecraft, new UniversalTranslatorLlmConfigScreen(
                        this, llmEndpoint, llmModel, !llmApiKey.isEmpty()));
            }
        }).bounds(left, engineRow(2), layout.buttonWidth, 20).build());

        testConnectionButton = addRenderableWidget(Button.builder(Component.empty(), button -> testConnection())
                .bounds(layout.right, engineRow(2), layout.buttonWidth, 20).build());

        checkSettingsButton = addRenderableWidget(Button.builder(Component.empty(), button -> testConnection())
                .bounds(left, engineRow(2), layout.totalWidth, 20).build());

        fallbackButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            apiFallback = !apiFallback;
            refreshLabels();
        }).bounds(left, engineRow(2), layout.totalWidth, 20).build());

        endpoint = addRenderableWidget(new EditBox(
                this.font, left, engineRow(1), layout.totalWidth, 20,
                Component.translatable("screen.universal_translator.endpoint")));
        endpoint.setMaxLength(UiLimits.ENDPOINT_MAX_CHARS);
        endpoint.setValue(endpointValue);

        // Quality and the custom system prompt are engine settings, so they belong on this tab
        // rather than on the diagnostics screen. They take the two rows under the existing three,
        // which leaves every other engine control exactly where it was; the prompt shares its row
        // with its own Apply button.
        this.qualityButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            quality = quality.next();
            refreshQualityLabel();
        }).bounds(left, engineRow(3), layout.totalWidth, 20).build());
        this.workersButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            workers = cycleChoice(workers, 1, 2, 4, 6, 8, 12, 16);
            refreshWorkersLabel();
        }).bounds(left, engineRow(5), layout.buttonWidth, 20).build());

        this.batchSizeButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            batchSize = cycleChoice(batchSize, 1, 2, 4, 8, 12, 16);
            refreshBatchLabel();
        }).bounds(layout.right, engineRow(5), layout.buttonWidth, 20).build());

        String promptValue = customPrompt == null
                ? TranslationPrompt.toEditorText(
                        TranslationPrompt.clampForEditor(original.customSystemPrompt()))
                : customPrompt.getValue();
        int promptApplyWidth = Math.max(48, layout.buttonWidth - 24);
        this.customPrompt = addRenderableWidget(new EditBox(this.font, left, engineRow(4),
                layout.totalWidth - promptApplyWidth - 8,
                20, Component.translatable("screen.universal_translator.diagnostics.prompt")));
        this.customPrompt.setMaxLength(UiLimits.PROMPT_EDITOR_MAX_CHARS);
        this.customPrompt.setValue(promptValue);
        this.customPrompt.setHint(Component.translatable(
                "screen.universal_translator.diagnostics.prompt_hint"));
        this.applyPromptButton = addRenderableWidget(Button.builder(
                Component.translatable("screen.universal_translator.diagnostics.prompt_apply"),
                button -> applyPromptSettings()).bounds(layout.right + layout.buttonWidth - promptApplyWidth, engineRow(4),
                        promptApplyWidth, 20).build());

        // --- Tab 4: Outgoing (傳送) ---
        outgoingButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            translateOutgoing = !translateOutgoing;
            refreshLabels();
        }).bounds(left, layout.contentRow(0), layout.buttonWidth, 20).build());

        outgoingTargetLanguageButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            openSelection = SettingsSelectionList.Kind.OUTGOING_LANGUAGE;
        }).bounds(layout.right, layout.contentRow(0), layout.buttonWidth, 20).build());

        // --- Tab 5: HUD (抬頭顯示) ---
        hudIndicatorButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            hudIndicator = !hudIndicator;
            refreshLabels();
        }).bounds(left, layout.contentRow(0), layout.buttonWidth, 20).build());

        hudCornerButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            hudIndicatorCorner = HudIndicatorCorner.values()[
                    (hudIndicatorCorner.ordinal() + 1) % HudIndicatorCorner.values().length];
            refreshLabels();
        }).bounds(layout.right, layout.contentRow(0), layout.buttonWidth, 20).build());

        hudSizeButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            hudIndicatorSize = hudIndicatorSize >= HudIndicatorSettings.MAX_SIZE
                    ? HudIndicatorSettings.MIN_SIZE : hudIndicatorSize + 1;
            refreshLabels();
        }).bounds(left, layout.contentRow(1), layout.buttonWidth, 20).build());

        hudMarginButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            hudIndicatorMargin = hudIndicatorMargin >= HudIndicatorSettings.MAX_MARGIN
                    ? HudIndicatorSettings.MIN_MARGIN : hudIndicatorMargin + 1;
            refreshLabels();
        }).bounds(layout.right, layout.contentRow(1), layout.buttonWidth, 20).build());

        hudColorButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            hudIndicatorColor = HudIndicatorColor.values()[
                    (hudIndicatorColor.ordinal() + 1) % HudIndicatorColor.values().length];
            refreshLabels();
        }).bounds(left, layout.contentRow(2), layout.buttonWidth, 20).build());

        hudContentButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            hudIndicatorContent = HudIndicatorContent.values()[
                    (hudIndicatorContent.ordinal() + 1) % HudIndicatorContent.values().length];
            refreshLabels();
        }).bounds(layout.right, layout.contentRow(2), layout.buttonWidth, 20).build());

        hudVisibilityButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            hudIndicatorVisibility = HudIndicatorVisibility.values()[
                    (hudIndicatorVisibility.ordinal() + 1) % HudIndicatorVisibility.values().length];
            refreshLabels();
        }).bounds(left, layout.contentRow(3), layout.buttonWidth, 20).build());

        // --- Bottom Action Row ---
        addRenderableWidget(Button.builder(Component.translatable("screen.universal_translator.save"), button -> saveAndApply())
                .bounds(left, layout.saveY, layout.buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), button -> onClose())
                .bounds(layout.right, layout.saveY, layout.buttonWidth, 20).build());

        refreshLabels();
        updateTabVisibility();
        // A resize or a screen rebuild mid-drag must not leave the handle stuck to the pointer.
        hudDragging = false;
    }

    /**
     * Re-renders the quality button from the pending selection.
     *
     * <p>Like the other engine rows the button only changes what the shared Save writes; the Apply
     * button beside the prompt is there for users who want the change to take effect without
     * closing the screen.
     */
    private void refreshQualityLabel() {
        if (qualityButton == null) {
            return;
        }
        qualityButton.setMessage(Component.translatable(
                "screen.universal_translator.option.quality",
                tr("value.universal_translator.quality." + quality.configName())));
    }

    private void refreshWorkersLabel() {
        if (workersButton == null) {
            return;
        }
        workersButton.setMessage(Component.translatable(
                "screen.universal_translator.option.workers", Integer.toString(workers)));
    }

    private void refreshBatchLabel() {
        if (batchSizeButton == null) {
            return;
        }
        batchSizeButton.setMessage(Component.translatable(
                "screen.universal_translator.option.batch_size", Integer.toString(batchSize)));
    }

    /** Cycles an engine setting through the values the engine tab offers. */
    private static int cycleChoice(int current, int... choices) {
        for (int index = 0; index < choices.length; index++) {
            if (choices[index] == current) {
                return choices[(index + 1) % choices.length];
            }
        }
        return choices[0];
    }

    /**
     * Applies the quality mode and the custom prompt to the running configuration without closing
     * the screen.
     *
     * <p>The provider is rebuilt through exactly the path Save uses, so the new prompt is used by
     * the next request and the cache key moves with it. A failure leaves the previous configuration
     * running and reports it on the engine tab's own status line.
     */
    private void applyPromptSettings() {
        refreshQualityLabel();
        try {
            String prompt = customPrompt == null
                    ? original.customSystemPrompt()
                    : TranslationPrompt.clampEditorText(customPrompt.getValue());
            FabricConfig updated = writeBackBase().withPromptSettings(quality, prompt);
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            FabricTranslationRuntime.initialize(updated);
            // Same writer as the F8 toggles, so an older queued snapshot cannot land after this one,
            // and the message below only announces a write that is known to be on disk.
            if (FabricTranslationRuntimeBase.saveInBackgroundAndWait(updated)) {
                setTestStatus(tr("screen.universal_translator.diagnostics.settings_saved"), false);
            } else {
                setTestStatus(tr("screen.universal_translator.diagnostics.settings_failed",
                        updated.configFile().getFileName().toString()), true);
            }
        } catch (Exception exception) {
            setTestStatus(tr("screen.universal_translator.diagnostics.settings_failed",
                    describe(exception)), true);
        }
    }

    private void updateTabVisibility() {
        boolean isGeneral = activeTab == Tab.GENERAL;
        boolean isScopes = activeTab == Tab.SCOPES;
        boolean isEngine = activeTab == Tab.ENGINE;
        boolean isOutgoing = activeTab == Tab.OUTGOING;
        boolean isHud = activeTab == Tab.HUD;

        // Tab 1: General
        enabledButton.visible = isGeneral;
        targetLanguageButton.visible = isGeneral;
        displayButton.visible = isGeneral;
        colorButton.visible = isGeneral;
        cacheButton.visible = isGeneral;
        uiStyleButton.visible = isGeneral;
        diagnosticsButton.visible = isGeneral;

        // Tab 2: Scopes
        chatButton.visible = isScopes;
        otherButton.visible = isScopes;
        vanillaButton.visible = isScopes;
        playerNamesButton.visible = isScopes;
        mixedTextButton.visible = isScopes;
        blockedKeywords.visible = isScopes;

        // Tab 3: Engine
        providerButton.visible = isEngine;
        boolean offline = isOffline();
        boolean llm = isLlm();
        boolean libre = LIBRETRANSLATE_PROVIDER.equalsIgnoreCase(provider);
        modelButton.visible = isEngine && offline;
        downloadButton.visible = isEngine && offline;
        fallbackButton.visible = isEngine && offline;
        llmConfigButton.visible = isEngine && llm;
        modelPickButton.visible = isEngine && llm;
        fetchModelsButton.visible = isEngine && llm;
        // The endpoint box edits the LibreTranslate endpoint key. Showing it for every online
        // provider let a DeepSeek user edit a value DeepSeek never reads.
        endpoint.visible = isEngine && libre;
        testConnectionButton.visible = isEngine && llm;
        qualityButton.visible = isEngine;
        workersButton.visible = isEngine;
        batchSizeButton.visible = isEngine;
        customPrompt.visible = isEngine;
        applyPromptButton.visible = isEngine;
        checkSettingsButton.visible = isEngine && !offline && !llm;
        if (!isEngine) {
            modelListOpen = false;
        }

        // Tab 4: Outgoing
        outgoingButton.visible = isOutgoing;
        outgoingTargetLanguageButton.visible = isOutgoing;
        outgoingTargetLanguageButton.active = translateOutgoing;

        // Tab 5: HUD
        hudIndicatorButton.visible = isHud;
        hudCornerButton.visible = isHud;
        hudSizeButton.visible = isHud;
        hudMarginButton.visible = isHud;
        hudColorButton.visible = isHud;
        hudContentButton.visible = isHud;
        hudVisibilityButton.visible = isHud;

        refreshTabButtons();
    }

    private void refreshTabButtons() {
        if (tabGeneralButton == null) return;
        tabGeneralButton.setMessage(tabTitle("screen.universal_translator.tab.general", activeTab == Tab.GENERAL));
        tabScopesButton.setMessage(tabTitle("screen.universal_translator.tab.scopes", activeTab == Tab.SCOPES));
        tabEngineButton.setMessage(tabTitle("screen.universal_translator.tab.engine", activeTab == Tab.ENGINE));
        tabOutgoingButton.setMessage(tabTitle("screen.universal_translator.tab.outgoing", activeTab == Tab.OUTGOING));
        tabHudButton.setMessage(tabTitle("screen.universal_translator.tab.hud", activeTab == Tab.HUD));
    }

    private Component tabTitle(String key, boolean active) {
        String label = tr(key);
        return Component.literal(active ? "§b§l[ " + label + " ]" : "§7" + label);
    }

    private void refreshLabels() {
        uiStyleButton.setMessage(Component.translatable("screen.universal_translator.option.ui_style",
                tr(animatedUi ? "value.universal_translator.ui_animated"
                        : "value.universal_translator.ui_classic")));
        hudIndicatorButton.setMessage(Component.translatable(
                "screen.universal_translator.option.hud_indicator", onOff(hudIndicator)));
        hudCornerButton.setMessage(Component.translatable(
                "screen.universal_translator.option.hud_corner", hudCornerLabel(hudIndicatorCorner)));
        hudSizeButton.setMessage(Component.translatable(
                "screen.universal_translator.option.hud_size", hudIndicatorSize));
        hudMarginButton.setMessage(Component.translatable(
                "screen.universal_translator.option.hud_margin", hudIndicatorMargin));
        hudColorButton.setMessage(Component.translatable(
                "screen.universal_translator.option.hud_color", hudColorLabel(hudIndicatorColor)));
        hudContentButton.setMessage(Component.translatable(
                "screen.universal_translator.option.hud_content", hudContentLabel(hudIndicatorContent)));
        hudVisibilityButton.setMessage(Component.translatable(
                "screen.universal_translator.option.hud_visibility", hudVisibilityLabel(hudIndicatorVisibility)));
        hudCornerButton.active = hudIndicator;
        hudSizeButton.active = hudIndicator;
        hudMarginButton.active = hudIndicator;
        hudColorButton.active = hudIndicator;
        hudContentButton.active = hudIndicator;
        hudVisibilityButton.active = hudIndicator;
        enabledButton.setMessage(Component.translatable("screen.universal_translator.option.automatic", onOff(enabled)));
        chatButton.setMessage(Component.translatable("screen.universal_translator.option.chat", onOff(translateChat)));
        otherButton.setMessage(Component.translatable("screen.universal_translator.option.other", onOff(translateOther)));
        vanillaButton.setMessage(Component.translatable("screen.universal_translator.option.vanilla", onOff(translateVanilla)));
        playerNamesButton.setMessage(Component.translatable(
                "screen.universal_translator.option.player_names", onOff(translatePlayerNames)));
        cacheButton.setMessage(Component.translatable("screen.universal_translator.option.cache", onOff(diskCache)));
        providerButton.setMessage(Component.translatable("screen.universal_translator.option.provider", providerLabel()));
        String modelValue = llmModel == null || llmModel.trim().isEmpty()
                ? tr("screen.universal_translator.engine.key_unset") : llmModel.trim();
        modelPickButton.setMessage(Component.translatable("screen.universal_translator.engine.model", modelValue));
        testConnectionButton.setMessage(Component.translatable("screen.universal_translator.engine.test"));
        checkSettingsButton.setMessage(Component.translatable(LIBRETRANSLATE_PROVIDER.equalsIgnoreCase(provider)
                ? "screen.universal_translator.engine.test"
                : "screen.universal_translator.engine.check"));
        displayButton.setMessage(Component.translatable("screen.universal_translator.option.display",
                tr(displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                        ? "value.universal_translator.display_bilingual"
                        : "value.universal_translator.display_translated")));
        mixedTextButton.setMessage(Component.translatable("screen.universal_translator.option.mixed", onOff(translateEnglishOnly)));
        colorButton.setMessage(Component.translatable("screen.universal_translator.option.color", colorLabel(translatedTextColor)));
        downloadButton.setMessage(Component.translatable("screen.universal_translator.option.download", onOff(offlineAutoDownload)));
        modelButton.setMessage(Component.translatable("screen.universal_translator.option.model", offlineModel.displayName()));
        fallbackButton.setMessage(Component.translatable("screen.universal_translator.option.fallback", onOff(apiFallback)));
        refreshQualityLabel();
        refreshWorkersLabel();
        refreshBatchLabel();
        outgoingButton.setMessage(Component.translatable("screen.universal_translator.option.outgoing", onOff(translateOutgoing)));
        targetLanguageButton.setMessage(Component.translatable("screen.universal_translator.option.target_preset",
                TargetLanguage.displayName(targetLanguage)));
        outgoingTargetLanguageButton.setMessage(Component.translatable(
                "screen.universal_translator.option.outgoing_target",
                TargetLanguage.displayName(outgoingTargetLanguage)));
        outgoingTargetLanguageButton.active = translateOutgoing;
        refreshTabButtons();
    }

    private static String onOff(boolean value) {
        return tr(UiTextKeys.onOff(value));
    }

    private static boolean isFailureStatus(String value) {
        return TranslationStatusLocalizer.isFailure(value);
    }

    /** The runtime status as drawn: localized, with the colour decision, cached briefly. */
    private String cachedLocalizedStatus() {
        long now = System.currentTimeMillis();
        String cached = cachedLocalizedStatus;
        if (cached != null && now - cachedLocalizedStatusAt < STATUS_CACHE_MILLIS) {
            return cached;
        }
        String raw = FabricTranslationRuntime.status();
        String localized = TranslationStatusLocalizer.localize(raw,
                UniversalTranslatorConfigScreen::tr);
        cachedLocalizedStatus = localized;
        cachedLocalizedStatusIsFailure = isFailureStatus(raw);
        cachedLocalizedStatusAt = now;
        return localized;
    }

    private void saveAndApply() {
        boolean runtimeChanged = false;
        FabricConfig base = writeBackBase();
        try {
            FabricConfig updated = base.withAllSettings(
                    enabled,
                    translateChat,
                    translateOther,
                    translateVanilla,
                    translateOutgoing,
                    translatePlayerNames,
                    blockedKeywords.getValue(),
                    targetLanguage,
                    outgoingTargetLanguage,
                    displayMode,
                    translateEnglishOnly,
                    translatedTextColor,
                    provider,
                    endpoint.getValue(),
                    llmEndpoint,
                    llmApiKey,
                    llmModel,
                    offlineAutoDownload,
                    offlineModel,
                    apiFallback,
                    diskCache,
                    animatedUi,
                    new HudIndicatorSettings(hudIndicator, hudIndicatorCorner,
                            hudIndicatorSize, hudIndicatorMargin, hudIndicatorColor,
                            hudIndicatorContent, hudIndicatorVisibility,
                            hudIndicatorOffsetX, hudIndicatorOffsetY),
                    // Save writes the engine tab's prompt controls too, so applying the prompt
                    // and then saving cannot silently restore the value the screen was opened
                    // with. One call for all three groups: each of withSettings,
                    // withPromptSettings and withTranslationTuning re-read the config file from
                    // disk, so a chained build cost four reads and one write per button press.
                    quality,
                    TranslationPrompt.clampEditorText(
                            customPrompt == null ? "" : customPrompt.getValue()),
                    workers,
                    batchSize);
            if (updated.enabled && "tencent-hunyuan".equalsIgnoreCase(updated.provider)
                    && (updated.tencentSecretId.isEmpty() || updated.tencentSecretKey.isEmpty())) {
                throw new IllegalArgumentException(tr("error.universal_translator.tencent_credentials"));
            }
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            runtimeChanged = true;
            FabricTranslationRuntime.initialize(updated);
            // Same writer as the F8 toggles: two threads writing this file let an older queued
            // snapshot land after the newer one and silently revert what the player just saved.
            // Only leave the screen once the file is known to be on disk: a save still queued past
            // the bounded wait used to close the screen and hide that it had not landed.
            if (FabricTranslationRuntimeBase.saveInBackgroundAndWait(updated)) {
                // Drawn once before the screen goes away; closing straight from this callback meant
                // the success line never appeared anywhere, so Save gave no feedback unless it failed.
                status = tr("status.universal_translator.saved");
                closeAfterSave();
            } else {
                status = tr("status.universal_translator.save_failed",
                        updated.configFile().getFileName());
            }
        } catch (Exception exception) {
            if (runtimeChanged) {
                try {
                    FabricTranslationRuntime.initialize(base);
                } catch (Exception restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
            }
            status = tr("status.universal_translator.save_failed", exception.getMessage());
        }
    }

    /**
     * The configuration the screen's edits are applied on top of.
     *
     * <p>{@code original} is the snapshot taken when the screen opened; the runtime's live
     * configuration is what it holds now. Applying edits to the snapshot discarded anything changed
     * in between: pressing F8 while the screen is open toggles {@code enabled} through the runtime
     * and saves it, and the next Save wrote the value the screen was opened with back over it.
     */
    private FabricConfig writeBackBase() {
        FabricConfig live = FabricTranslationRuntime.diagnosticsConfig();
        return live == null ? original : live;
    }

    /** Leaves the screen after the success line has had one frame to be drawn. */
    private void closeAfterSave() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) {
            onClose();
            return;
        }
        client.execute(this::onClose);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            FabricScreenOpener.open(minecraft, parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        syncFieldEditing();
        Layout layout = layout();
        long now = System.nanoTime();
        float opening = 1.0F;
        if (animatedUi) {
            opening = SettingsUiAnimation.openProgress(animationStartedNanos, now);
            int center = this.width / 2;
            int half = SettingsUiAnimation.expandingHalfWidth(
                    layout.totalWidth / 2 + 12, opening);
            int panelLeft = center - half;
            int panelRight = center + half;
            int panelBottom = Math.min(this.height - 4, layout.saveY + 42);
            graphics.fill(0, 0, this.width, this.height, 0x76070B10);
            graphics.fill(panelLeft - 2, 2, panelRight + 2, panelBottom + 2, 0x70101820);
            graphics.fill(panelLeft, 4, panelRight, panelBottom, 0xD41A232E);
            graphics.fill(panelLeft, 4, panelRight, 5, 0xCC55D6FF);
            graphics.fill(panelLeft, 32, panelRight, 33, 0x6655D6FF);
            int sweep = SettingsUiAnimation.sweepX(panelLeft, Math.max(panelLeft, panelRight - 26), now);
            graphics.fill(sweep, 32, Math.min(panelRight, sweep + 26), 34,
                    SettingsUiAnimation.pulseColor(now));

            // Animated Tab underline
            int tabIndex = activeTab.ordinal();
            int tabX = layout.tabX(tabIndex);
            graphics.fill(tabX, layout.tabY + 20, tabX + layout.tabWidth, layout.tabY + 22, 0xFF55D6FF);
        } else {
            // Static active tab indicator
            int tabIndex = activeTab.ordinal();
            int tabX = layout.tabX(tabIndex);
            graphics.fill(tabX, layout.tabY + 20, tabX + layout.tabWidth, layout.tabY + 22, 0xFF55D6FF);
        }
        graphics.centeredText(this.font, this.title, this.width / 2, 16,
                animatedUi ? SettingsUiAnimation.pulseColor(now) : 0xFFFFFFFF);
        String runtimeStatus = cachedLocalizedStatus();
        int belowSave = layout.saveY + 24;
        int messageY = belowSave <= this.height - 10 ? belowSave : SettingsScreenLayout.COMPACT_STATUS_Y;
        if (!status.isEmpty()) {
            graphics.centeredText(this.font, Component.literal(status),
                    this.width / 2, messageY, 0xFFFF5555);
        } else if (!testStatus.isEmpty()) {
            graphics.centeredText(this.font, Component.literal(testStatus),
                    this.width / 2, messageY, testStatusIsError ? 0xFFFF5555 : 0xFF55FF55);
        } else if (!runtimeStatus.isEmpty()) {
            graphics.centeredText(this.font, Component.literal(runtimeStatus),
                    this.width / 2, messageY,
                    cachedLocalizedStatusIsFailure ? 0xFFFF5555 : 0xFF55FF55);
        } else {
            if (activeTab == Tab.OUTGOING) {
                graphics.centeredText(this.font, Component.translatable("screen.universal_translator.info.outgoing_hint"),
                        this.width / 2, layout.contentRow(2) + 6, 0xFFA0A0A0);
            } else if (activeTab == Tab.GENERAL) {
                graphics.centeredText(this.font, Component.translatable("screen.universal_translator.info.keybind"),
                        this.width / 2, layout.contentRow(3) + 24, 0xFFA0A0A0);
            }
        }
        if (activeTab == Tab.ENGINE) {
            renderEngineInfo(graphics);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (animatedUi) {
            int overlayAlpha = SettingsUiAnimation.openingOverlayAlpha(opening);
            if (overlayAlpha > 0) {
                graphics.fill(0, 0, this.width, this.height, overlayAlpha << 24);
            }
        }
        if (activeTab == Tab.HUD) {
            drawHudDragPreview(graphics);
        }
        if (openSelection != SettingsSelectionList.Kind.NONE) {
            renderSelection(graphics, mouseX, mouseY);
        }
        if (activeTab == Tab.ENGINE && modelListOpen) {
            renderModelList(graphics, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Same guard as the shared pre-1.21.9 screen: only the left button drags the HUD handle or
        // picks a list row. The handlers below take coordinates alone and commit state, so without
        // this a right click dragged the indicator and submitted a selection.
        if (event.button() != 0) {
            return super.mouseClicked(event, doubleClick);
        }
        if (activeTab == Tab.ENGINE && modelListOpen) {
            handleModelListClick(event.x(), event.y());
            return true;
        }
        if (handleDragStart(event.x(), event.y())) {
            return true;
        }
        if (openSelection != SettingsSelectionList.Kind.NONE) {
            // The overlay is modal: the click that dismisses it is consumed here, otherwise it fell
            // through to the buttons underneath the dimmed area — including Save and Cancel.
            selectFromList(event.x(), event.y());
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    /**
     * ESC closes the open overlay instead of the screen.
     *
     * <p>This screen extends {@code Screen} directly (the 26.x generation does not sit on the
     * shared settings screen), so it needs the hook itself. The keyboard callback changed shape in
     * Minecraft 1.21.9, and this hook is the generation-independent half: it stops ESC from
     * dropping a whole session of unsaved changes.
     */
    @Override
    public boolean shouldCloseOnEsc() {
        // ESC closes the overlay first, and only then the screen. Every Screen generation
        // consults this hook exactly when ESC is pressed, so it is the one place that works
        // without knowing the generation's key callback shape (1.21.9 changed it, 26.x again).
        if (activeTab == Tab.ENGINE && modelListOpen) {
            modelListOpen = false;
            return false;
        }
        if (openSelection != SettingsSelectionList.Kind.NONE) {
            openSelection = SettingsSelectionList.Kind.NONE;
            return false;
        }
        return super.shouldCloseOnEsc();
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double deltaX, double deltaY) {
        return handleDragMove(event.x(), event.y())
                || super.mouseDragged(event, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        return handleDragEnd()
                || super.mouseReleased(event);
    }

    private boolean handleDragStart(double mouseX, double mouseY) {
        // A selection list keeps first claim on the click: without this, the drag would swallow
        // the click that is meant to close the list.
        if (openSelection != SettingsSelectionList.Kind.NONE) {
            return false;
        }
        if (activeTab != Tab.HUD) {
            return false;
        }
        if (!insideHudDragHandle(mouseX, mouseY)) {
            return false;
        }
        hudDragging = true;
        hudDragLastX = mouseX;
        hudDragLastY = mouseY;
        return true;
    }

    private boolean handleDragMove(double mouseX, double mouseY) {
        if (!hudDragging) {
            return false;
        }
        // One preview pixel is not one GUI pixel: the panel is a scaled-down screen, so undo the
        // scale before turning the pointer delta into a real offset.
        // One preview pixel is not one GUI pixel, so undo the scale before turning the pointer
        // delta into a real offset, and bound the offset rather than the drawn position: clamping
        // the drawing while the offset kept growing created a dead zone the user had to drag back
        // through. Both axes share one scale, so a diagonal drag moves the handle diagonally.
        double scale = hudScale();
        if (scale <= 0.0D) {
            return false;
        }
        hudIndicatorOffsetX = clampOffsetToPreview(hudIndicatorOffsetX
                + (int) Math.round((mouseX - hudDragLastX) / scale),
                hudAnchorX(), HUD_PREVIEW_SCREEN_WIDTH);
        hudIndicatorOffsetY = clampOffsetToPreview(hudIndicatorOffsetY
                + (int) Math.round((mouseY - hudDragLastY) / scale),
                hudAnchorY(), HUD_PREVIEW_SCREEN_HEIGHT);
        hudDragLastX = mouseX;
        hudDragLastY = mouseY;
        return true;
    }

    private boolean handleDragEnd() {
        if (!hudDragging) {
            return false;
        }
        hudDragging = false;
        return true;
    }

    /**
     * Bounds an offset so the indicator stays inside the previewed screen.
     *
     * <p>Clamping the offset instead of the drawn position is what removes the dead zone: while
     * only the drawing was clamped the offset kept growing, so the handle stopped following the
     * pointer and the user had to drag back through invisible distance before it moved again.
     * One offset unit is one pixel of the 320x240 virtual screen.
     */
    private int clampOffsetToPreview(int value, int anchor, int extent) {
        int lower = Math.max(HudIndicatorSettings.MIN_OFFSET, -anchor);
        int upper = Math.min(HudIndicatorSettings.MAX_OFFSET, extent - anchor - hudIndicatorSize);
        if (upper < lower) {
            upper = lower;
        }
        return Math.max(lower, Math.min(upper, value));
    }

    private int hudPreviewX() {
        return layout().left;
    }

    private int hudPreviewY() {
        return layout().contentRow(4);
    }

    private int hudPreviewW() {
        return layout().totalWidth;
    }

    /**
     * Panel height, capped by the space above the Save/Cancel row.
     *
     * <p>{@code contentRow(4)} does not shrink on very short windows because the row step has a
     * floor, so a fixed height would overlap Save. Clamping here keeps the panel clear of it.
     */
    private int hudPreviewH() {
        int available = layout().saveY - 4 - hudPreviewY();
        if (available < HUD_DRAG_HANDLE_SIZE) {
            // No room above Save: skip the preview entirely rather than draw a panel that
            // overlaps it, which is exactly what the cap above exists to prevent.
            return 0;
        }
        return Math.min(HUD_DRAG_PREVIEW_HEIGHT, available);
    }

    /**
     * Where the indicator sits on the real screen before the drag offset: the same anchor maths
     * the HUD mixin uses, so the preview cannot drift from what the player actually sees.
     */
    private int hudAnchorX() {
        return hudIndicatorCorner.isRight()
                ? HUD_PREVIEW_SCREEN_WIDTH - hudIndicatorMargin - hudIndicatorSize
                : hudIndicatorMargin;
    }

    private int hudAnchorY() {
        return hudIndicatorCorner.isBottom()
                ? HUD_PREVIEW_SCREEN_HEIGHT - hudIndicatorMargin - hudIndicatorSize
                : hudIndicatorMargin;
    }

    /**
     * One scale for both axes.
     *
     * <p>The panel is wide and short, so the horizontal and vertical factors used to differ by a
     * factor of five: a 320x240 screen was drawn at the panel width across and at 0.2x down, which
     * put the bottom corners in the wrong place before any drag and made the pointer delta mean a
     * different number of pixels on each axis. The smaller of the two fits the whole screen.
     */
    private double hudScale() {
        int width = hudPreviewW();
        int height = hudPreviewH();
        if (width <= 0 || height <= 0) {
            return 0.0D;
        }
        return Math.min(width / (double) HUD_PREVIEW_SCREEN_WIDTH,
                height / (double) HUD_PREVIEW_SCREEN_HEIGHT);
    }

    /** Left edge of the letterboxed screen inside the panel. */
    private int hudContentX() {
        int scaled = (int) Math.round(HUD_PREVIEW_SCREEN_WIDTH * hudScale());
        return hudPreviewX() + Math.max(0, (hudPreviewW() - scaled) / 2);
    }

    /** Top edge of the letterboxed screen inside the panel. */
    private int hudContentY() {
        int scaled = (int) Math.round(HUD_PREVIEW_SCREEN_HEIGHT * hudScale());
        return hudPreviewY() + Math.max(0, (hudPreviewH() - scaled) / 2);
    }

    private int hudHandleX() {
        int px = hudPreviewX();
        int pw = hudPreviewW();
        int x = hudContentX() + (int) Math.round((hudAnchorX() + hudIndicatorOffsetX) * hudScale());
        return Math.max(px, Math.min(px + pw - HUD_DRAG_HANDLE_SIZE, x));
    }

    private int hudHandleY() {
        int py = hudPreviewY();
        int ph = hudPreviewH();
        int y = hudContentY() + (int) Math.round((hudAnchorY() + hudIndicatorOffsetY) * hudScale());
        return Math.max(py, Math.min(py + ph - HUD_DRAG_HANDLE_SIZE, y));
    }

    private boolean insideHudDragHandle(double mouseX, double mouseY) {
        if (hudPreviewH() <= 0) {
            return false;
        }
        int hx = hudHandleX();
        int hy = hudHandleY();
        return mouseX >= hx && mouseX < hx + HUD_DRAG_HANDLE_SIZE
                && mouseY >= hy && mouseY < hy + HUD_DRAG_HANDLE_SIZE;
    }

    private void drawHudDragPreview(GuiGraphicsExtractor graphics) {
        int px = hudPreviewX();
        int py = hudPreviewY();
        int pw = hudPreviewW();
        int ph = hudPreviewH();
        if (ph <= 0) {
            return;
        }
        graphics.fill(px, py, px + pw, py + ph, 0x40000000);
        // A corner marker on the anchor edge the indicator hugs, so the four corner options are
        // visible in the preview instead of only in the button label.
        int hx = hudHandleX();
        int hy = hudHandleY();
        graphics.fill(hx, hy, hx + HUD_DRAG_HANDLE_SIZE, hy + HUD_DRAG_HANDLE_SIZE,
                hudDragging ? 0xFFFFAA00 : 0xFF00AA00);
    }

    private void renderSelection(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        String[] values = SettingsSelectionList.values(openSelection);
        SettingsSelectionList.Layout list = SettingsSelectionList.layout(width, height, values.length);
        graphics.fill(0, 0, width, height, 0xB0080B10);
        graphics.fill(list.panelLeft() - 1, list.panelTop - 1,
                list.panelRight() + 1, list.panelBottom + 1, 0xFF55D6FF);
        graphics.fill(list.panelLeft(), list.panelTop,
                list.panelRight(), list.panelBottom, 0xF018202A);
        graphics.centeredText(font, Component.translatable(selectionTitleKey()),
                width / 2, list.panelTop + 9, 0xFFFFFFFF);
        for (int index = 0; index < values.length; index++) {
            int x = list.x(index);
            int y = list.y(index);
            boolean hovered = mouseX >= x && mouseX < x + list.buttonWidth
                    && mouseY >= y && mouseY < y + list.buttonHeight;
            boolean selected = values[index].equalsIgnoreCase(selectionValue());
            graphics.fill(x, y, x + list.buttonWidth, y + list.buttonHeight,
                    hovered ? 0xFF3B6178 : selected ? 0xFF28533D : 0xFF303844);
            graphics.centeredText(font,
                    Component.literal(SettingsSelectionList.displayName(openSelection, values[index], key -> tr(key))),
                    x + list.buttonWidth / 2, y + Math.max(1, (list.buttonHeight - 8) / 2),
                    selected ? 0xFF55FF88 : 0xFFFFFFFF);
        }
    }

    private boolean selectFromList(double mouseX, double mouseY) {
        String[] values = SettingsSelectionList.values(openSelection);
        SettingsSelectionList.Layout list = SettingsSelectionList.layout(width, height, values.length);
        int selected = list.optionAt(mouseX, mouseY, values.length);
        if (selected >= 0) {
            if (openSelection == SettingsSelectionList.Kind.PROVIDER) {
                provider = values[selected];
                loadLlmSettings(provider);
                fetchedModels = Collections.emptyList();
                modelListOpen = false;
                setTestStatus("", false);
                updateTabVisibility();
            } else if (openSelection == SettingsSelectionList.Kind.TARGET_LANGUAGE) {
                targetLanguage = values[selected];
            } else {
                outgoingTargetLanguage = values[selected];
            }
            openSelection = SettingsSelectionList.Kind.NONE;
            refreshLabels();
            return true;
        } else if (!list.contains(mouseX, mouseY)) {
            // The overlay is modal: the click that dismisses it is consumed here. Returning false
            // reported the press as unhandled, so it fell through to the buttons underneath the
            // dimmed area — including Save and Cancel.
            openSelection = SettingsSelectionList.Kind.NONE;
            return true;
        }
        return true;
    }

    private String selectionValue() {
        if (openSelection == SettingsSelectionList.Kind.PROVIDER) return provider;
        if (openSelection == SettingsSelectionList.Kind.TARGET_LANGUAGE) return targetLanguage;
        return outgoingTargetLanguage;
    }

    private String selectionTitleKey() {
        if (openSelection == SettingsSelectionList.Kind.PROVIDER) return "screen.universal_translator.selection.provider";
        if (openSelection == SettingsSelectionList.Kind.TARGET_LANGUAGE) return "screen.universal_translator.selection.target_language";
        return "screen.universal_translator.selection.outgoing_language";
    }

    /**
     * Top of the engine tab's control block: three control rows plus the read-only summary and the
     * privacy hint, centred in the band between the tab bar and Save. The shared geometry is left
     * alone because Save/Cancel and the HUD preview depend on it.
     */
    private int engineBlockTop() {
        Layout layout = layout();
        int blockHeight = layout.contentRowStep * 5 + 20 + 46;
        int available = layout.saveY - 6 - layout.contentTop;
        // Centring used to clamp at contentTop, so on a short window the whole block - the privacy
// hint included - ran below the Save row and Save painted over it. Let the block start above
// the content area instead, but never over the tab bar.
int centred = layout.contentTop + (available - blockHeight) / 2;
return Math.max(SettingsScreenLayout.HEADER_BOTTOM + 2, centred);
    }

    private int engineRow(int index) {
        return engineBlockTop() + layout().contentRowStep * index;
    }

    /** Read-only engine facts plus the always-visible privacy hint. */
    private void renderEngineInfo(GuiGraphicsExtractor graphics) {
        int y = engineRow(5) + 24;
        if (isLlm()) {
            graphics.centeredText(this.font, Component.translatable(
                            "screen.universal_translator.engine.summary",
                            trimForDisplay(llmEndpoint),
                            tr(llmApiKey == null || llmApiKey.isEmpty()
                                    ? "screen.universal_translator.engine.key_unset"
                                    : "screen.universal_translator.engine.key_set")),
                    this.width / 2, y, 0xFFA0A0A0);
        } else if (!isOffline() && !LIBRETRANSLATE_PROVIDER.equalsIgnoreCase(provider)) {
            graphics.centeredText(this.font,
                    Component.translatable("screen.universal_translator.engine.config_file"),
                    this.width / 2, y, 0xFFA0A0A0);
        }
        graphics.centeredText(this.font, Component.translatable(isOffline()
                        ? "screen.universal_translator.info.offline"
                        : "screen.universal_translator.info.api"),
                this.width / 2, y + 18, 0xFFFFAA55);
    }

    /** Queries the configured LLM endpoint for its catalog and opens the picker. */
    private void fetchModels() {
        if (fetchingModels) {
            return;
        }
        final String endpointValue = llmEndpoint;
        final String keyValue = llmApiKey;
        fetchingModels = true;
        setTestStatus(tr("screen.universal_translator.llm.fetching"), false);
        ScreenWorkerPool.execute(() -> {
            OpenAiModelCatalog.Catalog catalog = null;
            String failure = "";
            try {
                catalog = OpenAiModelCatalog.fetchCatalog(provider, endpointValue, keyValue);
            } catch (Exception error) {
                failure = describe(error);
            } finally {
                // In a finally block so a LinkageError or OutOfMemoryError from the provider class
                // cannot leave the guard stuck at true and silence both buttons for the session.
                fetchingModels = false;
            }
            if (!failure.isEmpty()) {
                setTestStatus(tr("screen.universal_translator.llm.fetch_failed", failure), true);
                return;
            }
            if (catalog == null) {
                // fetchCatalog answers null for a body it cannot parse; it does not throw.
                setTestStatus(tr("screen.universal_translator.engine.test_not_json"), true);
                return;
            }
            if (!catalog.jsonBody()) {
                setTestStatus(tr("screen.universal_translator.engine.test_not_json"), true);
                return;
            }
            if (catalog.models().isEmpty()) {
                setTestStatus(tr("screen.universal_translator.llm.fetch_empty"), true);
                return;
            }
            fetchedModels = catalog.models();
            modelPage = 0;
            modelListOpen = true;
            releaseFieldFocusForModelList();
            setTestStatus("", false);
        });
    }

    /**
     * Drops keyboard focus from the engine tab's fields before the model picker covers them.
     *
     * <p>The picker is a painted rectangle, not a screen, so nothing intercepts typing while it is
     * open: whatever still holds focus collects the characters typed over the overlay, and the
     * next Save writes them into the config. The 1.21 screen has guarded this since it was written;
     * the 26.x copy was taken before that guard and lost it (C2-092).
     *
     * <p>Focus is not restored afterwards on purpose, matching 1.21: the player clicks the field
     * they want, which is also the only way to be sure of which field they end up in. Editing is
     * handed back as soon as the overlay closes, so nothing has to be clicked to type again.
     */
    private void releaseFieldFocusForModelList() {
        syncFieldEditing();
    }

    /**
     * Keeps the three fields from accepting characters while an overlay owns the keyboard, and hands
     * them back the moment it closes.
     *
     * <p>Clearing a field's own focus needs an accessor the game renames at runtime, so resolving it
     * by reflection found nothing and the guard was a no-op in every published client. The
     * editability switch exists on every generation, so the characters are stopped at the field
     * itself; re-applying it from {@link #extractRenderState} every frame is also what unfreezes the
     * fields for the paths that dismiss an overlay without calling the method above (ESC, a click
     * outside the list, a resize rebuilding the widgets).
     */
    private void syncFieldEditing() {
        boolean overlayOwnsKeyboard = openSelection != SettingsSelectionList.Kind.NONE
                || (activeTab == Tab.ENGINE && modelListOpen);
        setFieldEditing(endpoint, !overlayOwnsKeyboard);
        setFieldEditing(customPrompt, !overlayOwnsKeyboard);
        setFieldEditing(blockedKeywords, !overlayOwnsKeyboard);
    }

    private static void setFieldEditing(EditBox field, boolean editable) {
        if (field != null) {
            field.setEditable(editable);
        }
    }

    /**
     * Checks that the selected service is reachable: an LLM endpoint must answer with a model
     * catalog, LibreTranslate must answer on its languages endpoint, and every other service is
     * only checked for complete credentials because the screen owns no endpoint for it.
     */
    private void testConnection() {
        if (testing) {
            return;
        }
        final boolean llm = isLlm();
        final boolean libre = LIBRETRANSLATE_PROVIDER.equalsIgnoreCase(provider);
        final String endpointValue = llm ? llmEndpoint : endpoint.getValue().trim();
        final String keyValue = llmApiKey;
        testing = true;
        setTestStatus(tr("screen.universal_translator.engine.testing"), false);
        ScreenWorkerPool.execute(() -> {
            String message;
            boolean error;
            try {
                if (llm) {
                    // Probe the endpoint translations actually use. Reading /models can succeed
                    // while every translation fails on a wrong chat path or an unset model.
                    String model = llmModel == null ? "" : llmModel.trim();
                    if (model.isEmpty()) {
                        message = tr("screen.universal_translator.engine.test_need_model");
                        error = true;
                    } else {
                        ProviderProbe.probe(provider, endpointValue, keyValue, model,
                                original.editorApiVersion(provider));
                        message = tr("screen.universal_translator.engine.test_ok_probe", model);
                        error = false;
                    }
                } else if (libre) {
                    String url = libreLanguagesUrl(endpointValue);
                    EndpointPolicy.requireSafeEndpoint(url);
                    String body = new HttpJsonClient(5000, 15000).get(URI.create(url),
                            Collections.<String, String>emptyMap());
                    // The languages catalog is JSON; anything else means this is not LibreTranslate.
                    JsonStrings.parse(body);
                    message = tr("screen.universal_translator.engine.test_ok_libre");
                    error = false;
                } else {
                    // Validate the provider the screen is showing, not the one this snapshot was
                    // opened with: they differ until the form is saved, and answering for the old
                    // one told users the wrong thing about the service they had just switched to.
                    // Built on the running configuration for the same reason Save is.
                    writeBackBase().withProvider(provider).validateProviderConfiguration();
                    message = tr("screen.universal_translator.engine.test_ok_config");
                    error = false;
                }
            } catch (Exception failure) {
                message = describe(failure);
                error = true;
            }
            testing = false;
            setTestStatus(message, error);
        });
    }

    private void setTestStatus(String message, boolean error) {
        testStatus = message;
        testStatusIsError = error;
    }

    private static String describe(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    /** LibreTranslate exposes reachability on {@code /languages}, not on {@code /translate}. */
    private static String libreLanguagesUrl(String endpoint) {
        String base = endpoint == null ? "" : endpoint.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith(TRANSLATE_PATH)) {
            base = base.substring(0, base.length() - TRANSLATE_PATH.length());
        }
        return base + LANGUAGES_PATH;
    }

    /** Keeps the read-only endpoint line inside the panel; the editor still holds the full value. */
    private static String trimForDisplay(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= 44 ? trimmed : "\u2026" + trimmed.substring(trimmed.length() - 43);
    }

    /** Draws the fetched catalog over the settings so a served model can be picked. */
    private void renderModelList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        // Snapshot the list once: the worker replaces it wholesale, so reading size() and get()
        // through the volatile field separately can mix two different lists and throw on the
        // render thread.
        List<String> models = fetchedModels;
        graphics.fill(0, 0, this.width, this.height, 0xFF101010);
        graphics.centeredText(this.font,
                Component.translatable("screen.universal_translator.llm.select_model", models.size()),
                this.width / 2, 20, 0xFFFFFFFF);
        int rows = modelRowsPerPage();
        modelPage = ModelListPaging.clampPage(modelPage, models.size(), rows);
        int first = modelPage * rows;
        for (int row = 0; row < rows && first + row < models.size(); row++) {
            int rowY = MODEL_LIST_TOP + row * MODEL_ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + MODEL_ROW_HEIGHT;
            graphics.text(this.font,
                    Component.literal((hovered ? "> " : "  ") + models.get(first + row)),
                    24, rowY, hovered ? 0xFFFFD060 : 0xFFE0E0E0);
        }
        int pages = ModelListPaging.pageCount(models.size(), rows);
        graphics.centeredText(this.font, Component.literal("< " + (modelPage + 1) + "/" + pages + " >"),
                this.width / 2, this.height - 46, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("screen.universal_translator.llm.select_back"),
                this.width / 2, this.height - 30, 0xFFFFD060);
        graphics.centeredText(this.font, Component.translatable("screen.universal_translator.llm.select_hint"),
                this.width / 2, this.height - 16, 0xFFA0A0A0);
    }

    private void handleModelListClick(double mouseX, double mouseY) {
        List<String> models = fetchedModels;
        int rows = modelRowsPerPage();
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
        if (mouseY < MODEL_LIST_TOP) {
            return;
        }
        int row = (int) ((mouseY - MODEL_LIST_TOP) / MODEL_ROW_HEIGHT);
        int index = modelPage * rows + row;
        if (row >= rows || index >= models.size()) {
            return;
        }
        llmModel = models.get(index);
        modelListOpen = false;
        refreshLabels();
    }

    private int modelRowsPerPage() {
        return Math.max(3, (this.height - MODEL_LIST_TOP - MODEL_LIST_FOOTER_HEIGHT) / MODEL_ROW_HEIGHT);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private boolean isOffline() {
        return "offline".equalsIgnoreCase(provider);
    }

    private boolean isLlm() {
        return TranslationProviderCatalog.usesLlmEditor(provider);
    }

    private Component providerLabel() {
        return Component.translatable(TranslationProviderCatalog.displayName(provider, key -> tr(key)));
    }

    private void loadLlmSettings(String selectedProvider) {
        if (!TranslationProviderCatalog.usesLlmEditor(selectedProvider)) {
            return;
        }
        this.llmEndpoint = original.editorEndpoint(selectedProvider);
        this.llmApiKey = original.editorApiKey(selectedProvider);
        this.llmModel = original.editorModel(selectedProvider);
    }

    void applyLlmSettings(String endpoint, String model, String apiKey) {
        this.llmEndpoint = endpoint;
        this.llmModel = model;
        this.llmApiKey = apiKey;
    }

    String llmApiKey() {
        return llmApiKey;
    }

    private static Component colorLabel(TranslationTextColor color) {
        return Component.translatable("value.universal_translator.color." + color.configName().replace('-', '_'));
    }

    private static Component hudColorLabel(HudIndicatorColor color) {
        return Component.translatable("value.universal_translator.color." + color.configName().replace('-', '_'));
    }

    private static Component hudContentLabel(HudIndicatorContent content) {
        return Component.translatable(
                "value.universal_translator.hud_content." + content.configName().replace('-', '_'));
    }

    private static Component hudVisibilityLabel(HudIndicatorVisibility visibility) {
        return Component.translatable(
                "value.universal_translator.hud_visibility." + visibility.configName().replace('-', '_'));
    }

    private static Component hudCornerLabel(HudIndicatorCorner corner) {
        return Component.translatable(
                "value.universal_translator.hud_corner." + corner.configName().replace('-', '_'));
    }

    private static String tr(String key, Object... arguments) {
        return Component.translatable(key, arguments).getString();
    }

    private Layout layout() {
        // The HUD tab asks for the layout a dozen times per frame, once per accessor, and the
        // calculation allocates a fourteen-field object each time. Keyed by the only inputs it has.
        Layout cached = cachedLayout;
        if (cached != null && cachedLayoutWidth == width && cachedLayoutHeight == height) {
            return cached;
        }
        SettingsScreenLayout.Geometry geometry = SettingsScreenLayout.calculate(width, height, 5);
        Layout created = new Layout(geometry.left(), geometry.right(), geometry.totalWidth(), geometry.buttonWidth(),
                geometry.top(), geometry.rowStep(), geometry.targetY(), geometry.endpointY(), geometry.saveY(),
                geometry.tabY(), geometry.tabWidth(), geometry.tabGap(), geometry.contentTop(), geometry.contentRowStep());
        cachedLayout = created;
        cachedLayoutWidth = width;
        cachedLayoutHeight = height;
        return created;
    }

    private static final class Layout {
        private final int left, right, totalWidth, buttonWidth, top, rowStep, targetY, endpointY, saveY;
        private final int tabY, tabWidth, tabGap, contentTop, contentRowStep;

        private Layout(int left, int right, int totalWidth, int buttonWidth, int top,
                       int rowStep, int targetY, int endpointY, int saveY,
                       int tabY, int tabWidth, int tabGap, int contentTop, int contentRowStep) {
            this.left = left;
            this.right = right;
            this.totalWidth = totalWidth;
            this.buttonWidth = buttonWidth;
            this.top = top;
            this.rowStep = rowStep;
            this.targetY = targetY;
            this.endpointY = endpointY;
            this.saveY = saveY;
            this.tabY = tabY;
            this.tabWidth = tabWidth;
            this.tabGap = tabGap;
            this.contentTop = contentTop;
            this.contentRowStep = contentRowStep;
        }

        private int tabX(int index) {
            return left + index * (tabWidth + tabGap);
        }

        private int contentRow(int index) {
            return contentTop + contentRowStep * index;
        }

        private int row(int index) {
            return top + rowStep * index;
        }
    }
}
