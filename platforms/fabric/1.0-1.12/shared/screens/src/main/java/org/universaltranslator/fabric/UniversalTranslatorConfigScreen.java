package org.universaltranslator.fabric;

import org.universaltranslator.core.ModelListPaging;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;

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
import org.universaltranslator.core.HudIndicatorColor;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HudIndicatorSettings;
import org.universaltranslator.core.HudIndicatorVisibility;
import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.JsonStrings;
import org.universaltranslator.core.provider.OpenAiModelCatalog;
import org.universaltranslator.core.provider.ProviderProbe;
import org.universaltranslator.core.UiTextKeys;
import org.universaltranslator.core.UiLimits;

/**
 * Dependency-free settings UI for the 1.0-1.12 Fabric builds (this file is mounted by
 * {@code 1.0-1.8/shared.gradle} and {@code 1.8-1.12/shared.gradle}).
 *
 * <p>It used to claim it was shared with Forge 1.8.9 and 1.12.2. It is not: those build from
 * {@code platforms/forge/shared/legacy-common} and their own {@code Legacy*Screen} classes, so the
 * comment sent readers to the wrong file (audit S2-095).
 */
final class UniversalTranslatorConfigScreen extends UniversalTranslatorConfigScreenBase {
    private enum Tab {
        GENERAL,
        SCOPES,
        ENGINE,
        OUTGOING,
        HUD
    }

    private static final int ENABLED = 1;
    private static final int CACHE = 2;
    private static final int CHAT = 3;
    private static final int OTHER = 4;
    private static final int SAVE = 5;
    private static final int CANCEL = 6;
    private static final int PROVIDER = 7;
    private static final int DISPLAY = 8;
    private static final int DOWNLOAD = 9;
    private static final int FALLBACK = 10;
    private static final int MIXED_TEXT = 11;
    private static final int COLOR = 12;
    private static final int OUTGOING = 13;
    private static final int MODEL = 14;
    private static final int DIAGNOSTICS = 15;
    private static final int TARGET_LANGUAGE = 16;
    private static final int VANILLA = 17;
    private static final int OUTGOING_TARGET_LANGUAGE = 18;
    private static final int PLAYER_NAMES = 19;
    private static final int UI_STYLE = 20;
    private static final int TAB_GENERAL = 101;
    private static final int TAB_SCOPES = 102;
    private static final int TAB_ENGINE = 103;
    private static final int TAB_OUTGOING = 104;
    private static final int LLM_SETTINGS = 105;
    private static final int TAB_HUD = 106;
    private static final int HUD_INDICATOR = 23;
    private static final int HUD_CORNER = 24;
    private static final int HUD_SIZE = 25;
    private static final int HUD_MARGIN = 26;
    private static final int HUD_COLOR = 27;
    private static final int HUD_CONTENT = 28;
    private static final int HUD_VISIBILITY = 29;
    private static final int MODEL_PICK = 30;
    private static final int FETCH_MODELS = 31;
    private static final int TEST_CONNECTION = 32;
    private static final int CHECK_SETTINGS = 33;
    private static final int QUALITY = 34;
    private static final int PROMPT_APPLY = 35;
    /** Field id for the prompt box; only the 1.8-1.12 family's TextFieldWidget needs one. */
    private static final int PROMPT = 36;
    private static final int WORKERS = 37;
    private static final int BATCH_SIZE = 38;
    private static final int HUD_PREVIEW_SCREEN_WIDTH = 320;
    private static final int HUD_PREVIEW_SCREEN_HEIGHT = 240;
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

    private final Screen parent;
    private final FabricConfig original;
    /** Last geometry handed out by {@link #layout()}, with the window size it was computed for. */
    private SettingsScreenLayout.Geometry cachedLayout;
    private int cachedWindowWidth = -1;
    private int cachedWindowHeight = -1;
    private boolean enabled;
    private boolean translateChat;
    private boolean translateOther;
    private boolean translateVanilla;
    private boolean translateOutgoing;
    private boolean translatePlayerNames;
    private boolean animatedUi;
    private boolean diskCache;
    private boolean offlineAutoDownload;
    private OfflineModel offlineModel;
    private boolean apiFallback;
    private TranslationDisplayMode displayMode;
    private boolean translateEnglishOnly;
    private TranslationTextColor translatedTextColor;
    private String provider;
    private String llmEndpoint;
    private String llmApiKey;
    private String llmModel;
    private String targetLanguage;
    private String outgoingTargetLanguage;
    private boolean hudIndicator;
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

    private Tab activeTab = Tab.GENERAL;
    private ButtonWidget tabGeneralButton;
    private ButtonWidget tabScopesButton;
    private ButtonWidget tabEngineButton;
    private ButtonWidget tabOutgoingButton;

    private ButtonWidget enabledButton;
    private ButtonWidget targetLanguageButton;
    private ButtonWidget displayButton;
    private ButtonWidget colorButton;
    private ButtonWidget cacheButton;
    private ButtonWidget uiStyleButton;
    private ButtonWidget diagnosticsButton;

    private ButtonWidget chatButton;
    private ButtonWidget otherButton;
    private ButtonWidget vanillaButton;
    private ButtonWidget playerNamesButton;
    private ButtonWidget mixedTextButton;
    private TextFieldWidget blockedKeywords;

    private ButtonWidget providerButton;
    private ButtonWidget modelButton;
    private ButtonWidget downloadButton;
    private ButtonWidget fallbackButton;
    private ButtonWidget llmSettingsButton;
    private ButtonWidget modelPickButton;
    private ButtonWidget fetchModelsButton;
    private ButtonWidget testConnectionButton;
    private ButtonWidget checkSettingsButton;
    private TextFieldWidget endpoint;
    /** Selected quality mode; the shared Save writes it and Apply commits it immediately. */
    private TranslationQuality quality;
    private ButtonWidget qualityButton;
    /** Worker threads used by the translation pipeline; the shared Save writes it. */
    private int workers;
    private ButtonWidget workersButton;
    /** Sentences grouped into one translation request; the shared Save writes it. */
    private int batchSize;
    private ButtonWidget batchSizeButton;
    private TextFieldWidget customPrompt;
    private ButtonWidget applyPromptButton;

    private ButtonWidget outgoingButton;
    private ButtonWidget outgoingTargetButton;

    private ButtonWidget saveButton;
    private ButtonWidget cancelButton;
    private ButtonWidget tabHudButton;
    private ButtonWidget hudIndicatorButton;
    private ButtonWidget hudCornerButton;
    private ButtonWidget hudSizeButton;
    private ButtonWidget hudMarginButton;
    private ButtonWidget hudColorButton;
    private ButtonWidget hudContentButton;
    private ButtonWidget hudVisibilityButton;

    private TextRenderer renderer;
    private String status = "";
    /** Result of the engine tab's connection test; written by its worker thread. */
    private volatile String testStatus = "";
    private volatile boolean testStatusIsError;
    private volatile boolean testing;
    private volatile boolean fetchingModels;
    private volatile List<String> fetchedModels = Collections.emptyList();
    private volatile boolean modelListOpen;
    /**
     * A fetched catalog waiting for the client thread to open the picker with it.
     *
     * <p>The worker used to write {@code modelPage} and {@code modelListOpen} itself, so the overlay
     * could be raised while {@code fetchedModels} and the page index disagreed and rendered an empty
     * list for a frame. Publishing the result and applying it from {@link #tick()} keeps every piece
     * of screen state on the client thread, which is also why {@code modelPage} needs no synchronising.
     */
    private volatile List<String> pendingModelList;
    private int modelPage;
    private long animationStartedNanos = System.nanoTime();
    private SettingsSelectionList.Kind openSelection = SettingsSelectionList.Kind.NONE;

    UniversalTranslatorConfigScreen(Screen parent, FabricConfig config) {
        this.parent = parent;
        this.original = config;
        this.enabled = config.enabled;
        this.translateChat = config.translateChat;
        this.translateOther = config.translateOther;
        this.translateVanilla = config.translateVanilla;
        this.translateOutgoing = config.translateOutgoing;
        this.translatePlayerNames = config.translatePlayerNames;
        this.animatedUi = config.animatedUi;
        this.diskCache = config.diskCache;
        this.offlineAutoDownload = config.offlineAutoDownload;
        this.offlineModel = config.offlineModel;
        this.apiFallback = config.apiFallback;
        this.displayMode = config.displayMode;
        this.translateEnglishOnly = config.translateEnglishOnly;
        this.translatedTextColor = config.translatedTextColor;
        this.provider = config.provider;
        this.llmEndpoint = config.editorEndpoint(config.provider);
        this.llmApiKey = config.editorApiKey(config.provider);
        this.llmModel = config.editorModel(config.provider);
        this.quality = config.translationQuality();
        this.workers = config.translationWorkers();
        this.batchSize = config.batchSize();
        HudIndicatorSettings hud = config.hudIndicatorSettings();
        this.hudIndicator = hud.isIndicator();
        this.hudIndicatorCorner = hud.getCorner();
        this.hudIndicatorSize = hud.getSize();
        this.hudIndicatorMargin = hud.getMargin();
        this.hudIndicatorColor = hud.getColor();
        this.hudIndicatorContent = hud.getContent();
        this.hudIndicatorVisibility = hud.getVisibility();
        this.hudIndicatorOffsetX = hud.getOffsetX();
        this.hudIndicatorOffsetY = hud.getOffsetY();
    }

    @Override
    public void init() {
        if (targetLanguage == null) {
            targetLanguage = TargetLanguage.canonicalize(original.targetLanguage);
        }
        String endpointValue = endpoint == null ? original.endpoint : endpoint.getText();
        String blockedKeywordsValue = blockedKeywords == null
                ? original.blockedKeywords : blockedKeywords.getText();
        if (outgoingTargetLanguage == null) {
            outgoingTargetLanguage = TargetLanguage.canonicalize(original.outgoingTargetLanguage);
        }
        buttons.clear();
        renderer = OrnitheClientAccess.textRenderer();
        SettingsScreenLayout.Geometry layout = SettingsScreenLayout.calculate(width, height, 5);
        int left = layout.left();
        int right = layout.right();
        int buttonWidth = layout.buttonWidth();
        int totalWidth = layout.totalWidth();

        // 5 Navigation Tabs
        tabGeneralButton = new ButtonWidget(TAB_GENERAL, layout.tabX(0), layout.tabY(), layout.tabWidth(), 20,
                tr("screen.universal_translator.tab.general"));
        tabScopesButton = new ButtonWidget(TAB_SCOPES, layout.tabX(1), layout.tabY(), layout.tabWidth(), 20,
                tr("screen.universal_translator.tab.scopes"));
        tabEngineButton = new ButtonWidget(TAB_ENGINE, layout.tabX(2), layout.tabY(), layout.tabWidth(), 20,
                tr("screen.universal_translator.tab.engine"));
        tabOutgoingButton = new ButtonWidget(TAB_OUTGOING, layout.tabX(3), layout.tabY(), layout.tabWidth(), 20,
                tr("screen.universal_translator.tab.outgoing"));

        buttons.add(tabGeneralButton);
        buttons.add(tabScopesButton);
        buttons.add(tabEngineButton);
        buttons.add(tabOutgoingButton);

        tabHudButton = new ButtonWidget(TAB_HUD, layout.tabX(4), layout.tabY(), layout.tabWidth(), 20,
                tr("screen.universal_translator.tab.hud"));
        buttons.add(tabHudButton);

        // Tab 1: General (常規)
        enabledButton = new ButtonWidget(ENABLED, left, layout.contentRow(0), buttonWidth, 20, "");
        targetLanguageButton = new ButtonWidget(TARGET_LANGUAGE, right, layout.contentRow(0), buttonWidth, 20, "");
        displayButton = new ButtonWidget(DISPLAY, left, layout.contentRow(1), buttonWidth, 20, "");
        colorButton = new ButtonWidget(COLOR, right, layout.contentRow(1), buttonWidth, 20, "");
        cacheButton = new ButtonWidget(CACHE, left, layout.contentRow(2), buttonWidth, 20, "");
        uiStyleButton = new ButtonWidget(UI_STYLE, right, layout.contentRow(2), buttonWidth, 20, "");
        diagnosticsButton = new ButtonWidget(DIAGNOSTICS, left, layout.contentRow(3), totalWidth, 20,
                tr("screen.universal_translator.diagnostics.title"));

        buttons.add(enabledButton);
        buttons.add(targetLanguageButton);
        buttons.add(displayButton);
        buttons.add(colorButton);
        buttons.add(cacheButton);
        buttons.add(uiStyleButton);
        buttons.add(diagnosticsButton);

        // Tab 2: Scopes (範圍)
        chatButton = new ButtonWidget(CHAT, left, layout.contentRow(0), buttonWidth, 20, "");
        otherButton = new ButtonWidget(OTHER, right, layout.contentRow(0), buttonWidth, 20, "");
        vanillaButton = new ButtonWidget(VANILLA, left, layout.contentRow(1), buttonWidth, 20, "");
        playerNamesButton = new ButtonWidget(PLAYER_NAMES, right, layout.contentRow(1), buttonWidth, 20, "");
        mixedTextButton = new ButtonWidget(MIXED_TEXT, left, layout.contentRow(2), totalWidth, 20, "");
        blockedKeywords = textField(22, renderer, left, layout.contentRow(3), totalWidth, 20);
        blockedKeywords.setMaxLength(UiLimits.KEYWORDS_MAX_CHARS);
        blockedKeywords.setText(blockedKeywordsValue);

        buttons.add(chatButton);
        buttons.add(otherButton);
        buttons.add(vanillaButton);
        buttons.add(playerNamesButton);
        buttons.add(mixedTextButton);

        // Tab 3: Engine (引擎)
        // The engine rows are centred in the free band (see engineRow) so the tab does not sit in
        // the top third of an otherwise empty panel, and every row is reachable without moving the
        // shared Save/Cancel row that the other tabs and the HUD preview depend on.
        providerButton = new ButtonWidget(PROVIDER, left, engineRow(0), totalWidth, 20, "");
        modelButton = new ButtonWidget(MODEL, left, engineRow(1), buttonWidth, 20, "");
        downloadButton = new ButtonWidget(DOWNLOAD, right, engineRow(1), buttonWidth, 20, "");
        fallbackButton = new ButtonWidget(FALLBACK, left, engineRow(2), totalWidth, 20, "");
        modelPickButton = new ButtonWidget(MODEL_PICK, left, engineRow(1), buttonWidth, 20, "");
        fetchModelsButton = new ButtonWidget(FETCH_MODELS, right, engineRow(1), buttonWidth, 20,
                tr("screen.universal_translator.llm.fetch_models"));
        llmSettingsButton = new ButtonWidget(LLM_SETTINGS, left, engineRow(2), buttonWidth, 20,
                tr("screen.universal_translator.option.llm_settings"));
        testConnectionButton = new ButtonWidget(TEST_CONNECTION, right, engineRow(2), buttonWidth, 20, "");
        checkSettingsButton = new ButtonWidget(CHECK_SETTINGS, left, engineRow(2), totalWidth, 20, "");
        endpoint = textField(21, renderer, left, engineRow(1), totalWidth, 20);
        endpoint.setMaxLength(UiLimits.ENDPOINT_MAX_CHARS);
        endpoint.setText(endpointValue);

        buttons.add(providerButton);
        buttons.add(modelButton);
        buttons.add(downloadButton);
        buttons.add(fallbackButton);
        buttons.add(modelPickButton);
        buttons.add(fetchModelsButton);
        buttons.add(llmSettingsButton);
        buttons.add(testConnectionButton);
        buttons.add(checkSettingsButton);

        // Quality and the custom system prompt are engine settings, so they belong on this tab
        // rather than on the diagnostics screen. They take the two rows under the existing three,
        // which leaves every other engine control exactly where it was; the prompt shares its row
        // with its own Apply button.
        qualityButton = new ButtonWidget(QUALITY, left, engineRow(3), totalWidth, 20, "");
        buttons.add(qualityButton);
        workersButton = new ButtonWidget(WORKERS, left, engineRow(5), buttonWidth, 20, "");
        buttons.add(workersButton);

        batchSizeButton = new ButtonWidget(BATCH_SIZE, right, engineRow(5), buttonWidth, 20, "");
        buttons.add(batchSizeButton);

        String promptValue = customPrompt == null
                ? TranslationPrompt.toEditorText(original.customSystemPrompt())
                : customPrompt.getText();
        int promptApplyWidth = Math.max(48, buttonWidth - 24);
        customPrompt = textField(PROMPT, renderer, left, engineRow(4),
                totalWidth - promptApplyWidth - 8, 20);
        customPrompt.setMaxLength(UiLimits.PROMPT_MAX_CHARS);
        customPrompt.setText(promptValue);
        applyPromptButton = new ButtonWidget(PROMPT_APPLY,
                right + buttonWidth - promptApplyWidth, engineRow(4), promptApplyWidth, 20,
                tr("screen.universal_translator.diagnostics.prompt_apply"));
        buttons.add(applyPromptButton);

        // Tab 4: Outgoing (傳送)
        outgoingButton = new ButtonWidget(OUTGOING, left, layout.contentRow(0), buttonWidth, 20, "");
        outgoingTargetButton = new ButtonWidget(OUTGOING_TARGET_LANGUAGE, right, layout.contentRow(0), buttonWidth, 20, "");

        buttons.add(outgoingButton);
        buttons.add(outgoingTargetButton);

        // Tab 5: HUD (抬頭顯示)
        hudIndicatorButton = new ButtonWidget(HUD_INDICATOR, left, layout.contentRow(0), buttonWidth, 20, "");
        hudCornerButton = new ButtonWidget(HUD_CORNER, right, layout.contentRow(0), buttonWidth, 20, "");
        hudSizeButton = new ButtonWidget(HUD_SIZE, left, layout.contentRow(1), buttonWidth, 20, "");
        hudMarginButton = new ButtonWidget(HUD_MARGIN, right, layout.contentRow(1), buttonWidth, 20, "");
        hudColorButton = new ButtonWidget(HUD_COLOR, left, layout.contentRow(2), buttonWidth, 20, "");
        hudContentButton = new ButtonWidget(HUD_CONTENT, right, layout.contentRow(2), buttonWidth, 20, "");
        hudVisibilityButton = new ButtonWidget(HUD_VISIBILITY, left, layout.contentRow(3), buttonWidth, 20, "");

        buttons.add(hudIndicatorButton);
        buttons.add(hudCornerButton);
        buttons.add(hudSizeButton);
        buttons.add(hudMarginButton);
        buttons.add(hudColorButton);
        buttons.add(hudContentButton);
        buttons.add(hudVisibilityButton);

        // Bottom Bar
        saveButton = new ButtonWidget(SAVE, left, layout.saveY(), buttonWidth, 20,
                tr("screen.universal_translator.save"));
        cancelButton = new ButtonWidget(CANCEL, right, layout.saveY(), buttonWidth, 20,
                tr("gui.cancel"));

        buttons.add(saveButton);
        buttons.add(cancelButton);

        updateTabVisibility();
        refreshLabels();
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
        qualityButton.message = tr("screen.universal_translator.option.quality",
                tr("value.universal_translator.quality." + quality.configName()));
    }

    private void refreshWorkersLabel() {
        if (workersButton == null) {
            return;
        }
        workersButton.message = tr("screen.universal_translator.option.workers",
                Integer.toString(workers));
    }

    private void refreshBatchLabel() {
        if (batchSizeButton == null) {
            return;
        }
        batchSizeButton.message = tr("screen.universal_translator.option.batch_size",
                Integer.toString(batchSize));
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
     * The configuration a write-back starts from: the one the runtime is actually running with.
     *
     * <p>Every write-back used to derive from {@link #original}, the snapshot taken when this screen
     * was constructed, so changing one field rewrote every other field to its value at screen-open
     * time. Pressing F8 while the settings screen was open — {@code toggleFromKey()} re-reads the file,
     * flips {@code enabled} and saves — and then pressing Apply silently put the old {@code enabled}
     * (or {@code debugLog}) back on disk, with nothing on screen saying so.
     *
     * <p>{@code diagnosticsConfig()} is the runtime's live config, i.e. the same source the diagnostics
     * screen uses for its own write-back. It answers with {@link #original} only before the first load,
     * when nothing else can have changed underneath.
     */
    private FabricConfig writeBackBase() {
        FabricConfig live = FabricTranslationRuntime.diagnosticsConfig();
        return live == null ? original : live;
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
        // Same shape as saveAndApply: the runtime is restored if the rebuild fails halfway, so the
        // promise in the javadoc above holds and the next Save cannot persist a half-applied state.
        boolean runtimeChanged = false;
        FabricConfig base = writeBackBase();
        try {
            String prompt = customPrompt == null
                    ? base.customSystemPrompt()
                    : TranslationPrompt.fromEditorText(customPrompt.getText());
            FabricConfig updated = base.withPromptSettings(quality, prompt);
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            runtimeChanged = true;
            FabricTranslationRuntime.initialize(updated);
            updated.save();
            setTestStatus(tr("screen.universal_translator.diagnostics.settings_saved"), false);
        } catch (Exception exception) {
            if (runtimeChanged) {
                try {
                    FabricTranslationRuntime.initialize(base);
                } catch (Exception restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
            }
            setTestStatus(tr("screen.universal_translator.diagnostics.settings_failed",
                    describe(exception)), true);
        }
    }

    private void updateTabVisibility() {
        tabGeneralButton.active = activeTab != Tab.GENERAL;
        tabScopesButton.active = activeTab != Tab.SCOPES;
        tabEngineButton.active = activeTab != Tab.ENGINE;
        tabOutgoingButton.active = activeTab != Tab.OUTGOING;
        tabHudButton.active = activeTab != Tab.HUD;
        // Leaving the tab mid-drag would otherwise keep moving the indicator on the next click.
        hudDragging = false;

        // General
        boolean isGen = activeTab == Tab.GENERAL;
        enabledButton.visible = isGen;
        targetLanguageButton.visible = isGen;
        displayButton.visible = isGen;
        colorButton.visible = isGen;
        cacheButton.visible = isGen;
        uiStyleButton.visible = isGen;
        diagnosticsButton.visible = isGen;

        // Scopes
        boolean isScope = activeTab == Tab.SCOPES;
        chatButton.visible = isScope;
        otherButton.visible = isScope;
        vanillaButton.visible = isScope;
        playerNamesButton.visible = isScope;
        mixedTextButton.visible = isScope;
        blockedKeywords.setVisible(isScope);

        // Engine
        boolean isEng = activeTab == Tab.ENGINE;
        providerButton.visible = isEng;
        boolean offline = isOffline();
        boolean llm = isLlm();
        modelButton.visible = isEng && offline;
        downloadButton.visible = isEng && offline;
        fallbackButton.visible = isEng && offline;
        llmSettingsButton.visible = isEng && llm;
        modelPickButton.visible = isEng && llm;
        fetchModelsButton.visible = isEng && llm;
        // The endpoint box edits the LibreTranslate endpoint key. Showing it for every online
        // provider let a DeepSeek user edit a value DeepSeek never reads.
        endpoint.setVisible(isEng && isLibreTranslate());
        testConnectionButton.visible = isEng && llm;
        qualityButton.visible = isEng;
        workersButton.visible = isEng;
        batchSizeButton.visible = isEng;
        customPrompt.setVisible(isEng);
        applyPromptButton.visible = isEng;
        checkSettingsButton.visible = isEng && !offline && !llm;
        if (!isEng) {
            modelListOpen = false;
        }

        // Outgoing
        boolean isOut = activeTab == Tab.OUTGOING;
        outgoingButton.visible = isOut;
        outgoingTargetButton.visible = isOut;
        outgoingTargetButton.active = translateOutgoing;

        // HUD
        boolean isHud = activeTab == Tab.HUD;
        hudIndicatorButton.visible = isHud;
        hudCornerButton.visible = isHud;
        hudSizeButton.visible = isHud;
        hudMarginButton.visible = isHud;
        hudColorButton.visible = isHud;
        hudContentButton.visible = isHud;
        hudVisibilityButton.visible = isHud;
    }

    @Override
    protected void buttonClicked(ButtonWidget button) {
        if (button.id == TAB_GENERAL) {
            activeTab = Tab.GENERAL;
            updateTabVisibility();
            return;
        } else if (button.id == TAB_SCOPES) {
            activeTab = Tab.SCOPES;
            updateTabVisibility();
            return;
        } else if (button.id == TAB_ENGINE) {
            activeTab = Tab.ENGINE;
            updateTabVisibility();
            return;
        } else if (button.id == TAB_OUTGOING) {
            activeTab = Tab.OUTGOING;
            updateTabVisibility();
            return;
        } else if (button.id == TAB_HUD) {
            activeTab = Tab.HUD;
            updateTabVisibility();
            return;
        } else if (button.id == ENABLED) {
            enabled = !enabled;
        } else if (button.id == CACHE) {
            diskCache = !diskCache;
        } else if (button.id == CHAT) {
            translateChat = !translateChat;
        } else if (button.id == OTHER) {
            translateOther = !translateOther;
        } else if (button.id == VANILLA) {
            translateVanilla = !translateVanilla;
        } else if (button.id == PROVIDER) {
            openSelection = SettingsSelectionList.Kind.PROVIDER;
        } else if (button.id == DISPLAY) {
            displayMode = displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                    ? TranslationDisplayMode.TRANSLATED_ONLY
                    : TranslationDisplayMode.ORIGINAL_AND_TRANSLATED;
        } else if (button.id == MIXED_TEXT) {
            translateEnglishOnly = !translateEnglishOnly;
        } else if (button.id == COLOR) {
            translatedTextColor = translatedTextColor.next();
        } else if (button.id == DOWNLOAD) {
            offlineAutoDownload = !offlineAutoDownload;
        } else if (button.id == LLM_SETTINGS) {
            OrnitheClientAccess.openScreen(new UniversalTranslatorLlmConfigScreen(
                    this, llmEndpoint, llmModel, !llmApiKey.isEmpty()));
            return;
        } else if (button.id == MODEL_PICK) {
            if (fetchedModels.isEmpty()) {
                fetchModels();
            } else {
                modelListOpen = true;
            }
            return;
        } else if (button.id == FETCH_MODELS) {
            fetchModels();
            return;
        } else if (button.id == TEST_CONNECTION || button.id == CHECK_SETTINGS) {
            testConnection();
            return;
        } else if (button.id == FALLBACK) {
            apiFallback = !apiFallback;
        } else if (button.id == QUALITY) {
            quality = quality.next();
            refreshQualityLabel();
        } else if (button.id == WORKERS) {
            workers = cycleChoice(workers, 1, 2, 4, 6, 8, 12, 16);
            refreshWorkersLabel();
        } else if (button.id == BATCH_SIZE) {
            batchSize = cycleChoice(batchSize, 1, 2, 4, 8, 12, 16);
            refreshBatchLabel();
        } else if (button.id == PROMPT_APPLY) {
            applyPromptSettings();
        } else if (button.id == OUTGOING) {
            translateOutgoing = !translateOutgoing;
            outgoingTargetButton.active = translateOutgoing;
        } else if (button.id == PLAYER_NAMES) {
            translatePlayerNames = !translatePlayerNames;
        } else if (button.id == UI_STYLE) {
            animatedUi = !animatedUi;
            animationStartedNanos = System.nanoTime();
        } else if (button.id == MODEL) {
            offlineModel = offlineModel.next();
        } else if (button.id == DIAGNOSTICS) {
            OrnitheClientAccess.openScreen(new UniversalTranslatorDiagnosticsScreen(this));
            return;
        } else if (button.id == TARGET_LANGUAGE) {
            openSelection = SettingsSelectionList.Kind.TARGET_LANGUAGE;
        } else if (button.id == OUTGOING_TARGET_LANGUAGE) {
            openSelection = SettingsSelectionList.Kind.OUTGOING_LANGUAGE;
        } else if (button.id == HUD_INDICATOR) {
            hudIndicator = !hudIndicator;
        } else if (button.id == HUD_CORNER) {
            hudIndicatorCorner = HudIndicatorCorner.values()[
                    (hudIndicatorCorner.ordinal() + 1) % HudIndicatorCorner.values().length];
        } else if (button.id == HUD_SIZE) {
            hudIndicatorSize = hudIndicatorSize >= HudIndicatorSettings.MAX_SIZE
                    ? HudIndicatorSettings.MIN_SIZE : hudIndicatorSize + 1;
        } else if (button.id == HUD_MARGIN) {
            hudIndicatorMargin = hudIndicatorMargin >= HudIndicatorSettings.MAX_MARGIN
                    ? HudIndicatorSettings.MIN_MARGIN : hudIndicatorMargin + 1;
        } else if (button.id == HUD_COLOR) {
            hudIndicatorColor = HudIndicatorColor.values()[
                    (hudIndicatorColor.ordinal() + 1) % HudIndicatorColor.values().length];
        } else if (button.id == HUD_CONTENT) {
            hudIndicatorContent = HudIndicatorContent.values()[
                    (hudIndicatorContent.ordinal() + 1) % HudIndicatorContent.values().length];
        } else if (button.id == HUD_VISIBILITY) {
            hudIndicatorVisibility = HudIndicatorVisibility.values()[
                    (hudIndicatorVisibility.ordinal() + 1) % HudIndicatorVisibility.values().length];
        } else if (button.id == SAVE) {
            saveAndApply();
            return;
        } else if (button.id == CANCEL) {
            OrnitheClientAccess.openScreen(parent);
            return;
        }
        refreshLabels();
    }

    private void refreshLabels() {
        if (uiStyleButton != null) {
            uiStyleButton.message = tr("screen.universal_translator.option.ui_style",
                    tr(animatedUi ? "value.universal_translator.ui_animated"
                            : "value.universal_translator.ui_classic"));
        }
        if (enabledButton != null) {
            enabledButton.message = tr("screen.universal_translator.option.automatic", onOff(enabled));
        }
        if (targetLanguageButton != null) {
            targetLanguageButton.message = tr("screen.universal_translator.option.target_preset",
                    TargetLanguage.displayName(targetLanguage));
        }
        if (displayButton != null) {
            displayButton.message = tr("screen.universal_translator.option.display",
                    tr(displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                            ? "value.universal_translator.display_bilingual"
                            : "value.universal_translator.display_translated"));
        }
        if (colorButton != null) {
            colorButton.message = tr("screen.universal_translator.option.color", colorLabel(translatedTextColor));
        }
        if (cacheButton != null) {
            cacheButton.message = tr("screen.universal_translator.option.cache", onOff(diskCache));
        }
        if (chatButton != null) {
            chatButton.message = tr("screen.universal_translator.option.chat", onOff(translateChat));
        }
        if (otherButton != null) {
            otherButton.message = tr("screen.universal_translator.option.other", onOff(translateOther));
        }
        if (vanillaButton != null) {
            vanillaButton.message = tr("screen.universal_translator.option.vanilla", onOff(translateVanilla));
        }
        if (playerNamesButton != null) {
            playerNamesButton.message = tr(
                    "screen.universal_translator.option.player_names", onOff(translatePlayerNames));
        }
        if (mixedTextButton != null) {
            mixedTextButton.message = tr("screen.universal_translator.option.mixed", onOff(translateEnglishOnly));
        }
        if (providerButton != null) {
            providerButton.message = tr("screen.universal_translator.option.provider", providerLabel());
        }
        if (modelPickButton != null) {
            String modelValue = llmModel == null || llmModel.trim().isEmpty()
                    ? tr("screen.universal_translator.engine.key_unset") : llmModel.trim();
            modelPickButton.message = tr("screen.universal_translator.engine.model", modelValue);
        }
        // Their own buttons, not the model picker's: tying them to that unrelated widget left both
        // labels unwritten whenever the provider has no model picker.
        if (testConnectionButton != null) {
            testConnectionButton.message = tr("screen.universal_translator.engine.test");
        }
        if (checkSettingsButton != null) {
            checkSettingsButton.message = tr(isLibreTranslate()
                    ? "screen.universal_translator.engine.test"
                    : "screen.universal_translator.engine.check");
        }
        if (modelButton != null) {
            modelButton.message = tr("screen.universal_translator.option.model", offlineModel.displayName());
            modelButton.active = isOffline();
        }
        if (downloadButton != null) {
            downloadButton.message = tr("screen.universal_translator.option.download", onOff(offlineAutoDownload));
            downloadButton.active = isOffline();
        }
        if (fallbackButton != null) {
            fallbackButton.message = tr("screen.universal_translator.option.fallback", onOff(apiFallback));
            fallbackButton.active = isOffline();
        }
        refreshQualityLabel();
        refreshWorkersLabel();
        refreshBatchLabel();
        if (outgoingButton != null) {
            outgoingButton.message = tr("screen.universal_translator.option.outgoing", onOff(translateOutgoing));
        }
        if (outgoingTargetButton != null) {
            outgoingTargetButton.message = tr(
                    "screen.universal_translator.option.outgoing_target",
                    TargetLanguage.displayName(outgoingTargetLanguage));
            outgoingTargetButton.active = translateOutgoing;
        }
        if (hudIndicatorButton != null) {
            hudIndicatorButton.message = tr("screen.universal_translator.option.hud_indicator",
                    onOff(hudIndicator));
            hudCornerButton.message = tr("screen.universal_translator.option.hud_corner",
                    hudCornerLabel(hudIndicatorCorner));
            hudSizeButton.message = tr("screen.universal_translator.option.hud_size", hudIndicatorSize);
            hudMarginButton.message = tr("screen.universal_translator.option.hud_margin", hudIndicatorMargin);
            hudColorButton.message = tr("screen.universal_translator.option.hud_color",
                    hudColorLabel(hudIndicatorColor));
            hudContentButton.message = tr("screen.universal_translator.option.hud_content",
                    hudContentLabel(hudIndicatorContent));
            hudVisibilityButton.message = tr("screen.universal_translator.option.hud_visibility",
                    hudVisibilityLabel(hudIndicatorVisibility));
            // The indicator's own options are meaningless while it is switched off, so they are
            // greyed out rather than hidden: the player can still see what is being configured.
            hudCornerButton.active = hudIndicator;
            hudSizeButton.active = hudIndicator;
            hudMarginButton.active = hudIndicator;
            hudColorButton.active = hudIndicator;
            hudContentButton.active = hudIndicator;
            hudVisibilityButton.active = hudIndicator;
        }
    }

    /**
     * The screen geometry, recomputed only when the window changes.
     *
     * <p>{@code calculate(...)} is pure but allocates a fourteen-field object, and the HUD tab calls
     * this a dozen times per frame through the individual accessors.
     */
    private SettingsScreenLayout.Geometry layout() {
        SettingsScreenLayout.Geometry cached = cachedLayout;
        if (cached != null && cachedWindowWidth == width && cachedWindowHeight == height) {
            return cached;
        }
        SettingsScreenLayout.Geometry computed = SettingsScreenLayout.calculate(width, height, 5);
        cachedLayout = computed;
        cachedWindowWidth = width;
        cachedWindowHeight = height;
        return computed;
    }

    /**
     * Top of the engine tab's control block: three control rows plus the read-only summary and the
     * privacy hint, centred in the band between the tab bar and Save. The shared geometry is left
     * alone because Save/Cancel and the HUD preview depend on it.
     */
    private int engineBlockTop() {
        SettingsScreenLayout.Geometry layout = layout();
        int blockHeight = layout.contentRowStep() * 5 + 20 + 46;
        int available = layout.saveY() - 6 - layout.contentTop();
        // Centring used to clamp at contentTop, so on a short window the whole block - the privacy
// hint included - ran below the Save row and Save painted over it. Let the block start above
// the content area instead, but never over the tab bar.
int centred = layout.contentTop() + (available - blockHeight) / 2;
return Math.max(SettingsScreenLayout.HEADER_BOTTOM + 2, centred);
    }

    private int engineRow(int index) {
        return engineBlockTop() + layout().contentRowStep() * index;
    }

    private boolean isLibreTranslate() {
        return LIBRETRANSLATE_PROVIDER.equalsIgnoreCase(provider);
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
        return layout().left();
    }

    private int hudPreviewY() {
        return layout().contentRow(4);
    }

    private int hudPreviewW() {
        return layout().totalWidth();
    }

    /**
     * Panel height, capped by the space above the Save/Cancel row.
     *
     * <p>{@code contentRow(4)} does not shrink on very short windows because the row step has a
     * floor, so a fixed height would overlap Save. Clamping here keeps the panel clear of it.
     */
    private int hudPreviewH() {
        int available = layout().saveY() - 4 - hudPreviewY();
        if (available < HUD_DRAG_HANDLE_SIZE) {
            // No room above Save: skip the preview entirely rather than draw a panel that overlaps
            // it, which is exactly what the cap above exists to prevent.
            return 0;
        }
        return Math.min(HUD_DRAG_PREVIEW_HEIGHT, available);
    }

    /**
     * Where the indicator sits on the real screen before the drag offset: the same anchor maths the
     * HUD mixin uses, so the preview cannot drift from what the player actually sees.
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

    private boolean handleDragStart(double mouseX, double mouseY) {
        // A selection list keeps first claim on the click: without this, the drag would swallow the
        // click that is meant to close the list.
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

    protected boolean handleDragMove(double mouseX, double mouseY) {
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

    private void drawHudDragPreview() {
        int px = hudPreviewX();
        int py = hudPreviewY();
        int pw = hudPreviewW();
        int ph = hudPreviewH();
        if (ph <= 0) {
            return;
        }
        fill(px, py, px + pw, py + ph, 0x40000000);
        // A corner marker on the anchor edge the indicator hugs, so the four corner options are
        // visible in the preview instead of only in the button label.
        int hx = hudHandleX();
        int hy = hudHandleY();
        fill(hx, hy, hx + HUD_DRAG_HANDLE_SIZE, hy + HUD_DRAG_HANDLE_SIZE,
                hudDragging ? 0xFFFFAA00 : 0xFF00AA00);
    }

    private static String hudCornerLabel(HudIndicatorCorner corner) {
        switch (corner) {
            case TOP_RIGHT: return tr("value.universal_translator.hud_corner.top_right");
            case BOTTOM_LEFT: return tr("value.universal_translator.hud_corner.bottom_left");
            case BOTTOM_RIGHT: return tr("value.universal_translator.hud_corner.bottom_right");
            case TOP_LEFT:
            default: return tr("value.universal_translator.hud_corner.top_left");
        }
    }

    private static String hudColorLabel(HudIndicatorColor color) {
        return tr("value.universal_translator.color." + color.configName().replace('-', '_'));
    }

    private static String hudContentLabel(HudIndicatorContent content) {
        return tr("value.universal_translator.hud_content." + content.configName().replace('-', '_'));
    }

    private static String hudVisibilityLabel(HudIndicatorVisibility visibility) {
        return tr("value.universal_translator.hud_visibility." + visibility.configName().replace('-', '_'));
    }

    private static String onOff(boolean value) {
        return tr(UiTextKeys.onOff(value));
    }

    private static boolean isFailureStatus(String value) {
        return TranslationStatusLocalizer.isFailure(value);
    }

    private void saveAndApply() {
        boolean runtimeChanged = false;
        // The rollback target is what the runtime held before this save, not the snapshot taken when
        // the screen opened: restoring the snapshot would undo changes made from elsewhere meanwhile.
        FabricConfig base = writeBackBase();
        try {
            FabricConfig updated = base.withSettings(
                    enabled,
                    translateChat,
                    translateOther,
                    translateVanilla,
                    translateOutgoing,
                    translatePlayerNames,
                    blockedKeywords.getText(),
                    targetLanguage,
                    outgoingTargetLanguage,
                    displayMode,
                    translateEnglishOnly,
                    translatedTextColor,
                    provider,
                    endpoint.getText(),
                    llmEndpoint,
                    llmApiKey,
                    llmModel,
                    offlineAutoDownload,
                    offlineModel,
                    apiFallback,
                    diskCache,
                    animatedUi,
                    new HudIndicatorSettings(hudIndicator, hudIndicatorCorner, hudIndicatorSize,
                            hudIndicatorMargin, hudIndicatorColor, hudIndicatorContent,
                            hudIndicatorVisibility, hudIndicatorOffsetX, hudIndicatorOffsetY))
                    // Save writes the engine tab's prompt controls too, so applying the prompt and
                    // then saving cannot silently restore the value the screen was opened with.
                    .withPromptSettings(quality, TranslationPrompt.fromEditorText(
                            customPrompt == null ? "" : customPrompt.getText()))
                    .withTranslationTuning(workers, batchSize);
            if (updated.enabled && "tencent-hunyuan".equalsIgnoreCase(updated.provider)
                    && (updated.tencentSecretId.isEmpty() || updated.tencentSecretKey.isEmpty())) {
                throw new IllegalArgumentException(tr("error.universal_translator.tencent_credentials"));
            }
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            runtimeChanged = true;
            FabricTranslationRuntime.initialize(updated);
            updated.save();
            OrnitheClientAccess.openScreen(parent);
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

    @Override
    public void tick() {
        if (activeTab == Tab.ENGINE && isLibreTranslate() && endpoint != null) {
            endpoint.tick();
        }
        if (activeTab == Tab.ENGINE && customPrompt != null) {
            customPrompt.tick();
        }
        if (activeTab == Tab.SCOPES && blockedKeywords != null) {
            blockedKeywords.tick();
        }
        applyPendingModelList();
    }

    /**
     * Opens the model picker once the worker has published a catalog.
     *
     * <p>Runs on the client thread, so {@code modelPage} and {@code modelListOpen} are only ever
     * written here and in the button handlers; the render path therefore cannot observe a catalog
     * that does not match the page it is drawing.
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
    }

    @Override
    protected void keyPressed(char typedChar, int keyCode) {
        // ESC (1 on this generation's LWJGL2 keyboard) closes the overlay first. GuiScreen would
        // otherwise close the whole screen, throwing away every unsaved change, even though the
        // overlay draws its own Back area.
        if (keyCode == 1) {
            if (activeTab == Tab.ENGINE && modelListOpen) {
                modelListOpen = false;
                return;
            }
            if (openSelection != SettingsSelectionList.Kind.NONE) {
                openSelection = SettingsSelectionList.Kind.NONE;
                return;
            }
        }
        if (activeTab == Tab.ENGINE && isLibreTranslate() && endpoint != null
                && endpoint.keyPressed(typedChar, keyCode)) {
            return;
        }
        if (activeTab == Tab.ENGINE && customPrompt != null
                && customPrompt.keyPressed(typedChar, keyCode)) {
            return;
        }
        if (activeTab == Tab.SCOPES && blockedKeywords != null && blockedKeywords.keyPressed(typedChar, keyCode)) {
            return;
        }
        super.keyPressed(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (activeTab == Tab.ENGINE && modelListOpen) {
            handleModelListClick(mouseX, mouseY);
            return;
        }
        if (openSelection != SettingsSelectionList.Kind.NONE) {
            // The overlay is modal: the click that dismisses it is consumed here. Letting it fall
            // through reached the buttons underneath the dimmed area, so a click meant to close the
            // list could also press Save (keeping everything) or Cancel (discarding the form).
            selectFromList(mouseX, mouseY);
            return;
        }
        if (handleDragStart(mouseX, mouseY)) {
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (activeTab == Tab.ENGINE && isLibreTranslate() && endpoint != null) {
            endpoint.mouseClicked(mouseX, mouseY, mouseButton);
        }
        if (activeTab == Tab.ENGINE && customPrompt != null) {
            customPrompt.mouseClicked(mouseX, mouseY, mouseButton);
        }
        if (activeTab == Tab.SCOPES && blockedKeywords != null) {
            blockedKeywords.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int mouseButton) {
        if (handleDragEnd()) {
            return;
        }
        super.mouseReleased(mouseX, mouseY, mouseButton);
    }

    @Override
    public void render(int mouseX, int mouseY, float partialTicks) {
        renderBackground();
        SettingsScreenLayout.Geometry layout = SettingsScreenLayout.calculate(width, height, 5);
        long now = System.nanoTime();
        float opening = 1.0F;
        if (animatedUi) {
            opening = SettingsUiAnimation.openProgress(animationStartedNanos, now);
            int center = width / 2;
            int half = SettingsUiAnimation.expandingHalfWidth(
                    layout.totalWidth() / 2 + 12, opening);
            int panelLeft = center - half;
            int panelRight = center + half;
            int panelBottom = Math.min(height - 4, layout.saveY() + 42);
            fill(0, 0, width, height, 0x76070B10);
            fill(panelLeft - 2, 2, panelRight + 2, panelBottom + 2, 0x70101820);
            fill(panelLeft, 4, panelRight, panelBottom, 0xD41A232E);
            fill(panelLeft, 4, panelRight, 5, 0xCC55D6FF);
            fill(panelLeft, 32, panelRight, 33, 0x6655D6FF);
            int sweep = SettingsUiAnimation.sweepX(
                    panelLeft, Math.max(panelLeft, panelRight - 26), now);
            fill(sweep, 32, Math.min(panelRight, sweep + 26), 34,
                    SettingsUiAnimation.pulseColor(now));

            // Animated Tab underline
            int tabX = layout.tabX(activeTab.ordinal());
            int tabW = layout.tabWidth();
            fill(tabX, layout.tabY() + 20, tabX + tabW, layout.tabY() + 22, 0xFF55D6FF);
        } else {
            // Static active tab indicator
            int tabX = layout.tabX(activeTab.ordinal());
            int tabW = layout.tabWidth();
            fill(tabX, layout.tabY() + 20, tabX + tabW, layout.tabY() + 22, 0xFF55D6FF);
        }

        drawCenteredString(renderer, tr("screen.universal_translator.settings.title"),
                width / 2, 10,
                animatedUi ? SettingsUiAnimation.pulseColor(now) : 0xFFFFFFFF);

        super.render(mouseX, mouseY, partialTicks);

        // Tab-specific text and fields
        if (activeTab == Tab.SCOPES) {
            blockedKeywords.render();
            if (blockedKeywords.getText().isEmpty()) {
                renderer.drawWithShadow(
                        tr("screen.universal_translator.blocked_keywords_hint"),
                        layout.left() + 4, layout.contentRow(3) + 6, 0x70A0A0A0);
            }
        } else if (activeTab == Tab.ENGINE) {
            if (isLibreTranslate()) {
                endpoint.render();
            }
            customPrompt.render();
            renderEngineInfo();
        } else if (activeTab == Tab.OUTGOING) {
            int tipY = layout.contentRow(2);
            drawCenteredString(renderer, tr("screen.universal_translator.info.outgoing_hint"), width / 2, tipY, 0xFFAAAAAA);
        }

        // Status & feedback
        String rawRuntimeStatus = FabricTranslationRuntime.status();
        String runtimeStatus = TranslationStatusLocalizer.localize(rawRuntimeStatus,
                UniversalTranslatorConfigScreen::tr);
        int messageY = layout.saveY() + 24;
        if (messageY > height - 10) {
            messageY = height - 10;
        }
        if (!status.isEmpty()) {
            drawCenteredString(renderer, status, width / 2, messageY, 0xFFFF5555);
        } else if (!testStatus.isEmpty()) {
            drawCenteredString(renderer, testStatus, width / 2, messageY,
                    testStatusIsError ? 0xFFFF5555 : 0xFF55FF55);
        } else if (!runtimeStatus.isEmpty()) {
            drawCenteredString(renderer, runtimeStatus, width / 2, messageY,
                    isFailureStatus(rawRuntimeStatus) ? 0xFFFF5555 : 0xFF55FF55);
        }

        if (animatedUi) {
            int overlayAlpha = SettingsUiAnimation.openingOverlayAlpha(opening);
            if (overlayAlpha > 0) {
                fill(0, 0, width, height, overlayAlpha << 24);
            }
        }
        if (activeTab == Tab.HUD) {
            drawHudDragPreview();
        }
        if (openSelection != SettingsSelectionList.Kind.NONE) {
            renderSelection(mouseX, mouseY);
        }
        if (activeTab == Tab.ENGINE && modelListOpen) {
            renderModelList(mouseX, mouseY);
        }
    }

    private void renderSelection(int mouseX, int mouseY) {
        String[] values = SettingsSelectionList.values(openSelection);
        SettingsSelectionList.Layout list = SettingsSelectionList.layout(width, height, values.length);
        fill(0, 0, width, height, 0xB0080B10);
        fill(list.panelLeft() - 1, list.panelTop - 1,
                list.panelRight() + 1, list.panelBottom + 1, 0xFF55D6FF);
        fill(list.panelLeft(), list.panelTop,
                list.panelRight(), list.panelBottom, 0xF018202A);
        drawCenteredString(renderer, tr(selectionTitleKey()),
                width / 2, list.panelTop + 9, 0xFFFFFFFF);
        for (int index = 0; index < values.length; index++) {
            int x = list.x(index);
            int y = list.y(index);
            boolean hovered = mouseX >= x && mouseX < x + list.buttonWidth
                    && mouseY >= y && mouseY < y + list.buttonHeight;
            boolean selected = values[index].equalsIgnoreCase(selectionValue());
            fill(x, y, x + list.buttonWidth, y + list.buttonHeight,
                    hovered ? 0xFF3B6178 : selected ? 0xFF28533D : 0xFF303844);
            drawCenteredString(renderer,
                    SettingsSelectionList.displayName(openSelection, values[index], key -> tr(key)),
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
                openSelection = SettingsSelectionList.Kind.NONE;
                // Only what the provider actually changes: re-running init() here rebuilt all 38
                // buttons and three text fields inside the click handler, which flashed the screen
                // and dropped the cursor and the scroll position.
                updateTabVisibility();
                refreshLabels();
                return true;
            } else if (openSelection == SettingsSelectionList.Kind.TARGET_LANGUAGE) {
                targetLanguage = values[selected];
            } else {
                outgoingTargetLanguage = values[selected];
            }
            openSelection = SettingsSelectionList.Kind.NONE;
            refreshLabels();
            return true;
        } else if (!list.contains(mouseX, mouseY)) {
            openSelection = SettingsSelectionList.Kind.NONE;
            return false;
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

    /** Read-only engine facts plus the always-visible privacy hint. */
    private void renderEngineInfo() {
        // These text fields have no in-field placeholder, so the prompt's hint sits above it.
        drawCenteredString(renderer, tr("screen.universal_translator.diagnostics.prompt_hint"),
                width / 2, engineRow(4) - 11, 0xFFA0A0A0);
        int y = engineRow(5) + 24;
        if (isLlm()) {
            drawCenteredString(renderer, tr("screen.universal_translator.engine.summary",
                    trimForDisplay(llmEndpoint),
                    tr(llmApiKey == null || llmApiKey.isEmpty()
                            ? "screen.universal_translator.engine.key_unset"
                            : "screen.universal_translator.engine.key_set")),
                    width / 2, y, 0xFFA0A0A0);
        } else if (!isOffline() && !isLibreTranslate()) {
            drawCenteredString(renderer, tr("screen.universal_translator.engine.config_file"),
                    width / 2, y, 0xFFA0A0A0);
        }
        drawCenteredString(renderer, tr(isOffline()
                        ? "screen.universal_translator.info.offline"
                        : "screen.universal_translator.info.api"),
                width / 2, y + 18, 0xFFFFAA55);
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
        Thread worker = new Thread(() -> {
            OpenAiModelCatalog.Catalog catalog = null;
            String failure = "";
            try {
                catalog = OpenAiModelCatalog.fetchCatalog(provider, endpointValue, keyValue);
            } catch (Exception error) {
                failure = describe(error);
            } finally {
                // Released in a finally: a LinkageError / NoClassDefFoundError from a provider class
                // is not an Exception, and releasing the guard at the end of the lambda body left
                // "fetch models" and "test connection" returning immediately for the rest of the
                // screen's life, with no message and no way back short of reopening it.
                fetchingModels = false;
            }
            if (failure.isEmpty() && catalog == null) {
                // fetchCatalog answers null when the endpoint produced no parseable body; the old code
                // read catalog.jsonBody() straight away and threw a NullPointerException into the
                // thread's default handler, so nothing was reported to the user at all.
                failure = tr("screen.universal_translator.engine.test_not_json");
            }
            if (!failure.isEmpty()) {
                setTestStatus(tr("screen.universal_translator.llm.fetch_failed", failure), true);
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
            // Handed to the client thread instead of touching modelPage/modelListOpen here.
            pendingModelList = catalog.models();
            setTestStatus("", false);
        }, "universal-translator-model-list");
        worker.setDaemon(true);
        worker.start();
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
        final boolean libre = isLibreTranslate();
        final String endpointValue = llm ? llmEndpoint : endpoint.getText().trim();
        final String keyValue = llmApiKey;
        testing = true;
        setTestStatus(tr("screen.universal_translator.engine.testing"), false);
        Thread worker = new Thread(() -> {
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
                        ProviderProbe.probe(provider, endpointValue, keyValue, model);
                        message = tr("screen.universal_translator.engine.test_ok_probe", model);
                        error = false;
                    }
                } else if (libre) {
                    String url = libreLanguagesUrl(endpointValue);
                    // The languages catalog is JSON; anything else means this is not LibreTranslate.
                    String body = new HttpJsonClient(5000, 15000).get(
                            EndpointPolicy.requireSafeEndpoint(url),
                            Collections.<String, String>emptyMap());
                    JsonStrings.parse(body);
                    message = tr("screen.universal_translator.engine.test_ok_libre");
                    error = false;
                } else {
                    // Validate the provider the screen is showing, against the configuration the
                    // runtime is running with: {@code original} is the snapshot taken when this
                    // screen opened, so a credential entered in the LLM screen and a provider
                    // switched here never reached this check.
                    writeBackBase().withProvider(provider).validateProviderConfiguration();
                    message = tr("screen.universal_translator.engine.test_ok_config");
                    error = false;
                }
            } catch (Exception failure) {
                message = describe(failure);
                error = true;
            } finally {
                // See fetchModels: an Error escaping the probe must not leave the button latched.
                testing = false;
            }
            setTestStatus(message, error);
        }, "universal-translator-connection-test");
        worker.setDaemon(true);
        worker.start();
    }

    private void setTestStatus(String message, boolean error) {
        testStatus = message;
        testStatusIsError = error;
        // The save-failure line outranks the test line in render(), and nothing ever cleared it: one
        // failed save hid every later "test connection" result until the screen was reopened.
        status = "";
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
    private void renderModelList(int mouseX, int mouseY) {
        fill(0, 0, width, height, 0xFF101010);
        drawCenteredString(renderer,
                tr("screen.universal_translator.llm.select_model", fetchedModels.size()),
                width / 2, 20, 0xFFFFFFFF);
        int rows = modelRowsPerPage();
        modelPage = ModelListPaging.clampPage(modelPage, fetchedModels.size(), rows);
        int first = modelPage * rows;
        for (int row = 0; row < rows && first + row < fetchedModels.size(); row++) {
            int rowY = MODEL_LIST_TOP + row * MODEL_ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + MODEL_ROW_HEIGHT;
            drawString(renderer, (hovered ? "> " : "  ") + fetchedModels.get(first + row),
                    24, rowY, hovered ? 0xFFFFD060 : 0xFFE0E0E0);
        }
        int pages = modelPageCount(rows);
        drawCenteredString(renderer, "< " + (modelPage + 1) + "/" + pages + " >",
                width / 2, height - 46, 0xFFFFFFFF);
        drawCenteredString(renderer, tr("screen.universal_translator.llm.select_back"),
                width / 2, height - 30, 0xFFFFD060);
        drawCenteredString(renderer, tr("screen.universal_translator.llm.select_hint"),
                width / 2, height - 16, 0xFFA0A0A0);
    }

    private void handleModelListClick(double mouseX, double mouseY) {
        // Bands match what renderModelList draws: an 8px line at height-46 occupies
        // [height-46, height-38), and "back" at height-30 occupies [height-30, height-22).
        if (mouseY >= this.height - 48 && mouseY < this.height - 36) {
            int pages = modelPageCount(modelRowsPerPage());
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
        if (mouseY < MODEL_LIST_TOP) {
            return;
        }
        int rows = modelRowsPerPage();
        modelPage = ModelListPaging.clampPage(modelPage, fetchedModels.size(), rows);
        int row = (int) ((mouseY - MODEL_LIST_TOP) / MODEL_ROW_HEIGHT);
        int index = modelPage * rows + row;
        if (row >= rows || index >= fetchedModels.size()) {
            return;
        }
        llmModel = fetchedModels.get(index);
        modelListOpen = false;
        refreshLabels();
    }

    private int modelRowsPerPage() {
        return ModelListPaging.rows(this.height);
    }

    private int modelPageCount(int rows) {
        return ModelListPaging.pageCount(fetchedModels.size(), rows);
    }

    @Override
    public boolean shouldPauseGame() {
        return false;
    }

    private boolean isOffline() {
        return "offline".equalsIgnoreCase(provider);
    }

    private boolean isLlm() {
        return TranslationProviderCatalog.usesLlmEditor(provider);
    }

    private String providerLabel() {
        return TranslationProviderCatalog.displayName(provider, key -> tr(key));
    }

    private void loadLlmSettings(String selectedProvider) {
        if (!TranslationProviderCatalog.usesLlmEditor(selectedProvider)) {
            return;
        }
        // The live configuration, not {@code original}: credentials saved from the LLM screen were
        // overwritten with their screen-open-time values here, and the next Save wrote those back.
        FabricConfig base = writeBackBase();
        this.llmEndpoint = base.editorEndpoint(selectedProvider);
        this.llmApiKey = base.editorApiKey(selectedProvider);
        this.llmModel = base.editorModel(selectedProvider);
    }

    void applyLlmSettings(String endpoint, String model, String apiKey) {
        this.llmEndpoint = endpoint;
        this.llmModel = model;
        this.llmApiKey = apiKey;
    }

    String llmApiKey() {
        return llmApiKey;
    }

    private static String colorLabel(TranslationTextColor color) {
        switch (color) {
            case ORIGINAL: return tr("value.universal_translator.color.original");
            case GREEN: return tr("value.universal_translator.color.green");
            case GOLD: return tr("value.universal_translator.color.gold");
            case LIGHT_PURPLE: return tr("value.universal_translator.color.light_purple");
            case YELLOW: return tr("value.universal_translator.color.yellow");
            case WHITE: return tr("value.universal_translator.color.white");
            case AQUA:
            default: return tr("value.universal_translator.color.aqua");
        }
    }

}
