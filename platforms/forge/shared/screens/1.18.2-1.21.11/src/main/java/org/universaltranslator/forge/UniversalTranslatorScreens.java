package org.universaltranslator.forge;

import org.universaltranslator.core.ModelListPaging;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.universaltranslator.core.DebugLog;
import org.universaltranslator.core.DiagnosticsBundleExporter;
import org.universaltranslator.core.DiagnosticsLogExporter;
import org.universaltranslator.core.HudIndicatorColor;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HudIndicatorSettings;
import org.universaltranslator.core.HudIndicatorVisibility;
import org.universaltranslator.core.OfflineModel;
import org.universaltranslator.core.SettingsSelectionList;
import org.universaltranslator.core.SettingsUiAnimation;
import org.universaltranslator.core.SettingsScreenLayout;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.core.TranslationDiagnosticsSnapshot;
import org.universaltranslator.core.TranslationDisplayMode;
import org.universaltranslator.core.TranslationPrompt;
import org.universaltranslator.core.TranslationProviderCatalog;
import org.universaltranslator.core.TranslationQuality;
import org.universaltranslator.core.TranslationStats;
import org.universaltranslator.core.TranslationStatusLocalizer;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.net.EndpointPolicy;
import org.universaltranslator.core.net.HttpJsonClient;
import org.universaltranslator.core.net.JsonStrings;
import org.universaltranslator.core.provider.OpenAiModelCatalog;
import org.universaltranslator.core.provider.ProviderProbe;
import org.universaltranslator.core.UiTextKeys;
import org.universaltranslator.core.UiLimits;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.universaltranslator.forge.ForgeScreenText.empty;
import static org.universaltranslator.forge.ForgeScreenText.hint;
import static org.universaltranslator.forge.ForgeScreenText.literal;
import static org.universaltranslator.forge.ForgeScreenText.translatable;

/** Shared state and layout for Forge/NeoForge 1.21 screens with version-specific render bridges. */
abstract class UniversalTranslatorConfigScreenBase extends Screen {
    private final Screen parent;
    private final ForgeConfig original;
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
    private enum Tab {
        GENERAL,
        SCOPES,
        ENGINE,
        OUTGOING,
        HUD
    }
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
    private Button diagnosticsButton;

    private EditBox endpoint;
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
    private Button mixedTextButton;
    private Button colorButton;
    private Button outgoingButton;
    private Button targetLanguageButton;
    private Button outgoingTargetLanguageButton;
    private String status = "";
    /**
     * Result of the engine tab's connection test, written by its worker thread.
     *
     * <p>One reference rather than two independent fields: the message and its error flag are one
     * line on screen, and a reader that picked up the new message next to the previous flag painted
     * a failure in green (or the reverse). A single volatile reference publishes both at once, while
     * the writer runs on a worker and {@code render()} reads it.
     */
    private volatile TestStatus testStatus = TestStatus.EMPTY;
    // volatile because the fetch/test workers clear them and the render thread reads them as a
    // re-entry guard. Without it a worker could clear the flag without the render thread ever
    // observing the write, and the tab's "fetch models" and "test" buttons stayed dead for the life
    // of that screen instance. Every write below is a plain scalar store, which is what makes the
    // volatile alone sufficient here.
    private volatile boolean testing;
    private volatile boolean fetchingModels;
    private volatile List<String> fetchedModels = Collections.emptyList();
    private volatile boolean modelListOpen;
    private volatile int modelPage;
    private long animationStartedNanos = System.nanoTime();
    private SettingsSelectionList.Kind openSelection = SettingsSelectionList.Kind.NONE;
    /**
     * Last computed layout, keyed on the window size it was computed for.
     *
     * <p>One frame of the HUD tab asks for it through a dozen accessors and the engine tab asks for
     * it twice per row, and every one of those used to re-run the shared geometry maths and allocate
     * a 14-field object on the render thread. The geometry is a pure function of the window size, so
     * a size-keyed single slot is exact.
     */
    private Layout cachedLayout;
    private int cachedLayoutWidth = -1;
    private int cachedLayoutHeight = -1;
    /** How long a localized runtime status may be reused. Matches the runtime's own cache window. */
    private static final long STATUS_CACHE_MILLIS = 200L;
    private String cachedLocalizedStatus = "";
    private boolean cachedStatusIsFailure;
    private long cachedStatusAt;
    /**
     * Reused probe client.
     *
     * <p>The type only carries two timeouts, so it holds no connection state; building one per
     * button press was allocation on the client thread for no benefit.
     */
    private final HttpJsonClient probeClient = new HttpJsonClient(5000, 15000);

    /** Daemon workers for the screen actions that must not run on the client thread. */
    private static final ExecutorService SCREEN_WORKERS = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "universal-translator-screen-worker");
        thread.setDaemon(true);
        return thread;
    });

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
    private static final String LIBRETRANSLATE_PROVIDER = "libretranslate";
    private static final String TRANSLATE_PATH = "/translate";
    private static final String LANGUAGES_PATH = "/languages";

    UniversalTranslatorConfigScreenBase(Screen parent, ForgeConfig config) {
        super(translatable("screen.universal_translator.settings.title"));
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
    protected final void init() {
        if (targetLanguage == null) {
            targetLanguage = TargetLanguage.canonicalize(original.targetLanguage);
        }
        if (outgoingTargetLanguage == null) {
            outgoingTargetLanguage = TargetLanguage.canonicalize(original.outgoingTargetLanguage);
        }
        String endpointValue = endpoint == null ? original.endpoint : endpoint.getValue();
        String blockedKeywordsValue = blockedKeywords == null
                ? original.blockedKeywords : blockedKeywords.getValue();
        Layout layout = layout();
        int left = layout.left;

        // Navigation Tabs (positioned right below header at tabY)
        tabGeneralButton = addRenderableWidget(ButtonSpec.of(empty(), button -> {
            activeTab = Tab.GENERAL;
            updateTabVisibility();
        }).bounds(layout.tabX(0), layout.tabY(), layout.tabWidth(), 20).build());

        tabScopesButton = addRenderableWidget(ButtonSpec.of(empty(), button -> {
            activeTab = Tab.SCOPES;
            updateTabVisibility();
        }).bounds(layout.tabX(1), layout.tabY(), layout.tabWidth(), 20).build());

        tabEngineButton = addRenderableWidget(ButtonSpec.of(empty(), button -> {
            activeTab = Tab.ENGINE;
            updateTabVisibility();
        }).bounds(layout.tabX(2), layout.tabY(), layout.tabWidth(), 20).build());

        tabOutgoingButton = addRenderableWidget(ButtonSpec.of(empty(), button -> {
            activeTab = Tab.OUTGOING;
            updateTabVisibility();
        }).bounds(layout.tabX(3), layout.tabY(), layout.tabWidth(), 20).build());

        tabHudButton = addRenderableWidget(ButtonSpec.of(empty(), button -> {
            activeTab = Tab.HUD;
            updateTabVisibility();
        }).bounds(layout.tabX(4), layout.tabY(), layout.tabWidth(), 20).build());

        // --- Tab 1: General (常規) ---
        enabledButton = addRenderableWidget(button(left, layout.contentRow(0), layout.buttonWidth,
                () -> enabled = !enabled));
        targetLanguageButton = addRenderableWidget(button(layout.right, layout.contentRow(0), layout.buttonWidth,
                () -> openSelection = SettingsSelectionList.Kind.TARGET_LANGUAGE));
        displayButton = addRenderableWidget(button(left, layout.contentRow(1), layout.buttonWidth,
                () -> displayMode = displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                        ? TranslationDisplayMode.TRANSLATED_ONLY
                        : TranslationDisplayMode.ORIGINAL_AND_TRANSLATED));
        colorButton = addRenderableWidget(button(layout.right, layout.contentRow(1), layout.buttonWidth,
                () -> translatedTextColor = translatedTextColor.next()));
        cacheButton = addRenderableWidget(button(left, layout.contentRow(2), layout.buttonWidth,
                () -> diskCache = !diskCache));
        uiStyleButton = addRenderableWidget(button(layout.right, layout.contentRow(2), layout.buttonWidth, () -> {
            animatedUi = !animatedUi;
            animationStartedNanos = System.nanoTime();
        }));
        diagnosticsButton = addRenderableWidget(ButtonSpec.of(
                translatable("screen.universal_translator.diagnostics.title"), button -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new UniversalTranslatorDiagnosticsScreen(this, original));
                    }
                }).bounds(left, layout.contentRow(3), layout.totalWidth, 20).build());

        // --- Tab 2: Scopes (範圍) ---
        chatButton = addRenderableWidget(button(left, layout.contentRow(0), layout.buttonWidth,
                () -> translateChat = !translateChat));
        otherButton = addRenderableWidget(button(layout.right, layout.contentRow(0), layout.buttonWidth,
                () -> translateOther = !translateOther));
        vanillaButton = addRenderableWidget(button(left, layout.contentRow(1), layout.buttonWidth,
                () -> translateVanilla = !translateVanilla));
        playerNamesButton = addRenderableWidget(button(layout.right, layout.contentRow(1), layout.buttonWidth,
                () -> translatePlayerNames = !translatePlayerNames));
        mixedTextButton = addRenderableWidget(button(left, layout.contentRow(2), layout.totalWidth,
                () -> translateEnglishOnly = !translateEnglishOnly));
        blockedKeywords = addRenderableWidget(new EditBox(font, left, layout.contentRow(3),
                layout.totalWidth, 20, translatable("screen.universal_translator.blocked_keywords")));
        blockedKeywords.setMaxLength(UiLimits.KEYWORDS_MAX_CHARS);
        blockedKeywords.setValue(blockedKeywordsValue);
        hint(blockedKeywords, translatable("screen.universal_translator.blocked_keywords_hint",
                UiLimits.KEYWORDS_MAX_ENTRIES, UiLimits.KEYWORD_MAX_CHARS));

        // --- Tab 3: Engine (引擎) ---
        // The engine rows are centred in the free band (see engineRow) so the tab does not sit in
        // the top third of an otherwise empty panel, and every row is reachable without moving the
        // shared Save/Cancel row that the other tabs and the HUD preview depend on.
        providerButton = addRenderableWidget(button(left, engineRow(0), layout.totalWidth,
                () -> openSelection = SettingsSelectionList.Kind.PROVIDER));
        modelButton = addRenderableWidget(button(left, engineRow(1), layout.buttonWidth,
                () -> offlineModel = offlineModel.next()));
        downloadButton = addRenderableWidget(button(layout.right, engineRow(1), layout.buttonWidth,
                () -> offlineAutoDownload = !offlineAutoDownload));
        modelPickButton = addRenderableWidget(button(left, engineRow(1), layout.buttonWidth, () -> {
            if (fetchedModels.isEmpty()) {
                fetchModels();
            } else {
                modelListOpen = true;
            }
        }));
        fetchModelsButton = addRenderableWidget(ButtonSpec.of(
                translatable("screen.universal_translator.llm.fetch_models"), button -> fetchModels())
                .bounds(layout.right, engineRow(1), layout.buttonWidth, 20).build());
        endpoint = addRenderableWidget(new EditBox(font, left, engineRow(1), layout.totalWidth,
                20, translatable("screen.universal_translator.endpoint")));
        endpoint.setMaxLength(UiLimits.ENDPOINT_MAX_CHARS);
        endpoint.setValue(endpointValue);
        fallbackButton = addRenderableWidget(button(left, engineRow(2), layout.totalWidth,
                () -> apiFallback = !apiFallback));
        llmConfigButton = addRenderableWidget(ButtonSpec.of(
                translatable("screen.universal_translator.option.llm_settings"), button -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new UniversalTranslatorLlmConfigScreen(
                                this, llmEndpoint, llmModel, !llmApiKey.isEmpty()));
                    }
                }).bounds(left, engineRow(2), layout.buttonWidth, 20).build());
        testConnectionButton = addRenderableWidget(button(layout.right, engineRow(2), layout.buttonWidth,
                () -> testConnection()));
        checkSettingsButton = addRenderableWidget(button(left, engineRow(2), layout.totalWidth,
                () -> testConnection()));

        // Quality and the custom system prompt are engine settings, so they belong on
        // this tab rather than on the diagnostics screen. They take the two rows under the
        // existing three, which leaves every other engine control exactly where it was;
        // the prompt shares its row with its own Apply button.
        qualityButton = addRenderableWidget(ButtonSpec.of(literal(""), button -> {
            quality = quality.next();
            refreshQualityLabel();
        }).bounds(left, engineRow(3), layout.totalWidth, 20).build());
        workersButton = addRenderableWidget(ButtonSpec.of(literal(""), button -> {
            workers = cycleChoice(workers, 1, 2, 4, 6, 8, 12, 16);
            refreshWorkersLabel();
        }).bounds(left, engineRow(5), layout.buttonWidth, 20).build());

        batchSizeButton = addRenderableWidget(ButtonSpec.of(literal(""), button -> {
            batchSize = cycleChoice(batchSize, 1, 2, 4, 8, 12, 16);
            refreshBatchLabel();
        }).bounds(layout.right, engineRow(5), layout.buttonWidth, 20).build());

        String promptValue = customPrompt == null
                ? TranslationPrompt.toEditorText(
                        TranslationPrompt.clampForEditor(original.customSystemPrompt()))
                : customPrompt.getValue();
        int promptApplyWidth = Math.max(48, layout.buttonWidth - 24);
        customPrompt = addRenderableWidget(new EditBox(font, left, engineRow(4),
                layout.totalWidth - promptApplyWidth - 8,
                20, translatable("screen.universal_translator.diagnostics.prompt")));
        customPrompt.setMaxLength(UiLimits.PROMPT_EDITOR_MAX_CHARS);
        customPrompt.setValue(promptValue);
        applyPromptButton = addRenderableWidget(ButtonSpec.of(
                translatable("screen.universal_translator.diagnostics.prompt_apply"),
                button -> applyPromptSettings())
                .bounds(layout.right + layout.buttonWidth - promptApplyWidth, engineRow(4), promptApplyWidth, 20).build());

        // --- Tab 4: Outgoing (傳送) ---
        outgoingButton = addRenderableWidget(button(left, layout.contentRow(0), layout.totalWidth,
                () -> translateOutgoing = !translateOutgoing));
        outgoingTargetLanguageButton = addRenderableWidget(button(left, layout.contentRow(1),
                layout.totalWidth, () -> openSelection = SettingsSelectionList.Kind.OUTGOING_LANGUAGE));

        // --- Tab 5: HUD (抬頭顯示) ---
        hudIndicatorButton = addRenderableWidget(button(left, layout.contentRow(0), layout.buttonWidth,
                () -> hudIndicator = !hudIndicator));
        hudCornerButton = addRenderableWidget(button(layout.right, layout.contentRow(0), layout.buttonWidth, () -> {
            HudIndicatorCorner[] corners = HudIndicatorCorner.values();
            hudIndicatorCorner = corners[(hudIndicatorCorner.ordinal() + 1) % corners.length];
        }));
        hudSizeButton = addRenderableWidget(button(left, layout.contentRow(1), layout.buttonWidth,
                () -> hudIndicatorSize = hudIndicatorSize >= HudIndicatorSettings.MAX_SIZE
                        ? HudIndicatorSettings.MIN_SIZE : hudIndicatorSize + 1));
        hudMarginButton = addRenderableWidget(button(layout.right, layout.contentRow(1), layout.buttonWidth,
                () -> hudIndicatorMargin = hudIndicatorMargin >= HudIndicatorSettings.MAX_MARGIN
                        ? HudIndicatorSettings.MIN_MARGIN : hudIndicatorMargin + 1));
        hudColorButton = addRenderableWidget(button(left, layout.contentRow(2), layout.buttonWidth, () -> {
            HudIndicatorColor[] colors = HudIndicatorColor.values();
            hudIndicatorColor = colors[(hudIndicatorColor.ordinal() + 1) % colors.length];
        }));
        hudContentButton = addRenderableWidget(button(layout.right, layout.contentRow(2), layout.buttonWidth, () -> {
            HudIndicatorContent[] contents = HudIndicatorContent.values();
            hudIndicatorContent = contents[(hudIndicatorContent.ordinal() + 1) % contents.length];
        }));
        hudVisibilityButton = addRenderableWidget(button(left, layout.contentRow(3), layout.buttonWidth, () -> {
            HudIndicatorVisibility[] visibilities = HudIndicatorVisibility.values();
            hudIndicatorVisibility = visibilities[(hudIndicatorVisibility.ordinal() + 1) % visibilities.length];
        }));

        // --- Bottom Action Row ---
        addRenderableWidget(ButtonSpec.of(translatable("screen.universal_translator.save"),
                button -> saveAndApply()).bounds(left, layout.saveY, layout.buttonWidth, 20).build());
        addRenderableWidget(ButtonSpec.of(translatable("gui.cancel"), button -> onClose())
                .bounds(layout.right, layout.saveY, layout.buttonWidth, 20).build());

        refreshLabels();
        updateTabVisibility();

        // A resize or a screen rebuild mid-drag must not leave the handle stuck to the pointer.
        hudDragging = false;
    }

    private Button button(int x, int y, int width, Runnable action) {
        return ButtonSpec.of(empty(), button -> {
            action.run();
            refreshLabels();
        }).bounds(x, y, width, 20).build();
    }

    /** Sets a button's label, tolerating the controls a partly built screen has not created yet. */
    private static void setMessage(Button button, Component message) {
        if (button != null) {
            button.setMessage(message);
        }
    }

    /** Turns a button on or off, tolerating the same partly built state as {@link #setMessage}. */
    private static void setActive(Button button, boolean active) {
        if (button != null) {
            button.active = active;
        }
    }

    private void refreshLabels() {
        setMessage(uiStyleButton, translatable("screen.universal_translator.option.ui_style",
                tr(animatedUi ? "value.universal_translator.ui_animated"
                        : "value.universal_translator.ui_classic")));
        setMessage(hudIndicatorButton, translatable("screen.universal_translator.option.hud_indicator",
                onOff(hudIndicator)));
        setMessage(hudCornerButton, translatable("screen.universal_translator.option.hud_corner",
                cornerLabel(hudIndicatorCorner)));
        setMessage(hudSizeButton, translatable("screen.universal_translator.option.hud_size",
                Integer.toString(hudIndicatorSize)));
        setMessage(hudMarginButton, translatable("screen.universal_translator.option.hud_margin",
                Integer.toString(hudIndicatorMargin)));
        setMessage(hudColorButton, translatable("screen.universal_translator.option.hud_color",
                hudColorLabel(hudIndicatorColor)));
        setMessage(hudContentButton, translatable("screen.universal_translator.option.hud_content",
                hudContentLabel(hudIndicatorContent)));
        setMessage(hudVisibilityButton, translatable("screen.universal_translator.option.hud_visibility",
                hudVisibilityLabel(hudIndicatorVisibility)));
        setMessage(enabledButton, translatable("screen.universal_translator.option.automatic", onOff(enabled)));
        setMessage(cacheButton, translatable("screen.universal_translator.option.cache", onOff(diskCache)));
        setMessage(chatButton, translatable("screen.universal_translator.option.chat", onOff(translateChat)));
        setMessage(otherButton, translatable("screen.universal_translator.option.other", onOff(translateOther)));
        setMessage(providerButton, translatable("screen.universal_translator.option.provider",
                TranslationProviderCatalog.displayName(provider, key -> tr(key))));
        String modelValue = llmModel == null || llmModel.trim().isEmpty()
                ? tr("screen.universal_translator.engine.key_unset") : llmModel.trim();
        setMessage(modelPickButton, translatable("screen.universal_translator.engine.model", modelValue));
        setMessage(testConnectionButton, translatable("screen.universal_translator.engine.test"));
        setMessage(checkSettingsButton, translatable(LIBRETRANSLATE_PROVIDER.equalsIgnoreCase(provider)
                ? "screen.universal_translator.engine.test"
                : "screen.universal_translator.engine.check"));
        setMessage(displayButton, translatable("screen.universal_translator.option.display",
                tr(displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                        ? "value.universal_translator.display_bilingual"
                        : "value.universal_translator.display_translated")));
        setMessage(mixedTextButton, translatable("screen.universal_translator.option.mixed",
                onOff(translateEnglishOnly)));
        setMessage(colorButton, translatable("screen.universal_translator.option.color",
                colorLabel(translatedTextColor)));
        setMessage(downloadButton, translatable("screen.universal_translator.option.download", onOff(offlineAutoDownload)));
        setMessage(modelButton, translatable("screen.universal_translator.option.model", offlineModel.displayName()));
        setMessage(fallbackButton, translatable("screen.universal_translator.option.fallback", onOff(apiFallback)));
        refreshQualityLabel();
        refreshWorkersLabel();
        refreshBatchLabel();
        setMessage(vanillaButton, translatable("screen.universal_translator.option.vanilla", onOff(translateVanilla)));
        setMessage(playerNamesButton, translatable("screen.universal_translator.option.player_names",
                onOff(translatePlayerNames)));
        setMessage(targetLanguageButton, translatable("screen.universal_translator.option.target_preset",
                TargetLanguage.displayName(targetLanguage)));
        setMessage(outgoingButton, translatable("screen.universal_translator.option.outgoing",
                onOff(translateOutgoing)));
        setMessage(outgoingTargetLanguageButton, translatable(
                "screen.universal_translator.option.outgoing_target",
                TargetLanguage.displayName(outgoingTargetLanguage)));
        setActive(outgoingTargetLanguageButton, translateOutgoing);
        setActive(hudCornerButton, hudIndicator);
        setActive(hudSizeButton, hudIndicator);
        setActive(hudMarginButton, hudIndicator);
        setActive(hudColorButton, hudIndicator);
        setActive(hudContentButton, hudIndicator);
        setActive(hudVisibilityButton, hudIndicator);
        refreshTabButtons();
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
        qualityButton.setMessage(translatable(
                "screen.universal_translator.option.quality",
                tr("value.universal_translator.quality." + quality.configName())));
    }

    private void refreshWorkersLabel() {
        if (workersButton == null) {
            return;
        }
        workersButton.setMessage(translatable(
                "screen.universal_translator.option.workers", Integer.toString(workers)));
    }

    private void refreshBatchLabel() {
        if (batchSizeButton == null) {
            return;
        }
        batchSizeButton.setMessage(translatable(
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
        ForgeConfig base = currentConfig();
        try {
            String prompt = customPrompt == null
                    ? base.customSystemPrompt()
                    : TranslationPrompt.clampEditorText(customPrompt.getValue());
            ForgeConfig updated = base.withPromptSettings(quality, prompt);
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            ForgeTranslationRuntime.initialize(updated);
            // Waits for the queued F8 / Home writes before writing: the Save is the last user action
            // and a snapshot queued earlier must never land after it.
            ForgeConfigWriter.saveNow(updated);
            setTestStatus(tr("screen.universal_translator.diagnostics.settings_saved"), false);
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
        checkSettingsButton.visible = isEngine && !offline && !llm;
        qualityButton.visible = isEngine;
        workersButton.visible = isEngine;
        batchSizeButton.visible = isEngine;
        customPrompt.visible = isEngine;
        applyPromptButton.visible = isEngine;
        if (!isEngine) {
            modelListOpen = false;
        }

        // Tab 4: Outgoing
        outgoingButton.visible = isOutgoing;
        outgoingTargetLanguageButton.visible = isOutgoing;

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
        return literal(active ? "§b§l[ " + label + " ]" : "§7" + label);
    }

    /**
     * The configuration this screen edits from, taken from the runtime when it has one.
     *
     * <p>{@code withSettings} copies every key it does not own from the object it is given, so
     * saving from the constructor snapshot silently reverted whatever another path had written
     * while the screen was open: an F8 toggle, a Home-key target-language change. The constructor
     * value is the fallback for the window before the first configuration load.
     */
    private ForgeConfig currentConfig() {
        ForgeConfig live = ForgeTranslationRuntime.diagnosticsConfig();
        return live == null ? original : live;
    }

    private void saveAndApply() {
        ForgeConfig base = currentConfig();
        boolean runtimeChanged = false;
        try {
            ForgeConfig updated = base.withSettings(enabled, translateChat, translateOther,
                    translateVanilla, translateOutgoing, translatePlayerNames,
                    blockedKeywords.getValue(), targetLanguage, outgoingTargetLanguage, displayMode,
                    translateEnglishOnly, translatedTextColor, provider, endpoint.getValue(),
                    llmEndpoint, llmApiKey, llmModel, offlineAutoDownload, offlineModel,
                    apiFallback, diskCache, animatedUi,
                    new HudIndicatorSettings(hudIndicator, hudIndicatorCorner,
                            hudIndicatorSize, hudIndicatorMargin, hudIndicatorColor,
                            hudIndicatorContent, hudIndicatorVisibility,
                            hudIndicatorOffsetX, hudIndicatorOffsetY))
                    // Save writes the engine tab's prompt controls too, so applying the prompt
                    // and then saving cannot silently restore the value the screen
                    // was opened with.
                    .withPromptSettings(quality, TranslationPrompt.clampEditorText(
                            customPrompt == null ? "" : customPrompt.getValue()))
                    .withTranslationTuning(workers, batchSize);
            if (updated.enabled && "tencent-hunyuan".equalsIgnoreCase(updated.provider)
                    && (updated.tencentSecretId.isEmpty() || updated.tencentSecretKey.isEmpty())) {
                throw new IllegalArgumentException(tr("error.universal_translator.tencent_credentials"));
            }
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            runtimeChanged = true;
            ForgeTranslationRuntime.initialize(updated);
            ForgeConfigWriter.saveNow(updated);
            status = tr("status.universal_translator.saved");
            onClose();
        } catch (Exception exception) {
            if (runtimeChanged) {
                try {
                    // Roll back to the configuration that was running before this save, not to the
                    // snapshot the screen opened with: something else may have changed it since,
                    // and restoring the old snapshot left the runtime disagreeing with the file.
                    ForgeTranslationRuntime.initialize(base);
                } catch (Exception restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
            }
            status = tr("status.universal_translator.save_failed", exception.getMessage());
        }
    }

    /**
     * The runtime status as the status line shows it, reused for {@link #STATUS_CACHE_MILLIS}.
     *
     * <p>{@code status()} builds a string out of the provider status and the session's last failure,
     * and localizing it walks a dozen comparisons plus an {@code I18n} lookup. Both ran once per
     * frame while the screen was open, even with nothing changing; the window is one fifth of the
     * runtime's own diagnostics cache, so a change is still on screen before anyone can read it.
     */
    private String localizedStatus() {
        long now = System.currentTimeMillis();
        if (now - cachedStatusAt < STATUS_CACHE_MILLIS) {
            return cachedLocalizedStatus;
        }
        String raw = ForgeTranslationRuntime.status();
        cachedLocalizedStatus = TranslationStatusLocalizer.localize(raw,
                UniversalTranslatorConfigScreenBase::tr);
        cachedStatusIsFailure = TranslationStatusLocalizer.isFailure(raw);
        cachedStatusAt = now;
        return cachedLocalizedStatus;
    }

    protected final float renderSettingsBefore(ForgeScreenCanvas graphics) {
        Layout layout = layout();
        long now = System.nanoTime();
        float opening = 1.0F;
        if (animatedUi) {
            opening = SettingsUiAnimation.openProgress(animationStartedNanos, now);
            int center = width / 2;
            int half = SettingsUiAnimation.expandingHalfWidth(layout.totalWidth / 2 + 12, opening);
            int panelLeft = center - half;
            int panelRight = center + half;
            int panelBottom = Math.min(height - 4, layout.saveY + 42);
            graphics.fill(0, 0, width, height, 0x76070B10);
            graphics.fill(panelLeft - 2, 2, panelRight + 2, panelBottom + 2, 0x70101820);
            graphics.fill(panelLeft, 4, panelRight, panelBottom, 0xD41A232E);
            graphics.fill(panelLeft, 4, panelRight, 5, 0xCC55D6FF);
            graphics.fill(panelLeft, 32, panelRight, 33, 0x6655D6FF);
            int sweep = SettingsUiAnimation.sweepX(panelLeft, Math.max(panelLeft, panelRight - 26), now);
            graphics.fill(sweep, 32, Math.min(panelRight, sweep + 26), 34,
                    SettingsUiAnimation.pulseColor(now));

            // Glowing underline for active tab
            int tabIndex = activeTab.ordinal();
            int tabX = layout.tabX(tabIndex);
            graphics.fill(tabX, layout.tabY() + 20, tabX + layout.tabWidth(), layout.tabY() + 22, 0xFF55D6FF);
        }
        graphics.centered(title, width / 2, 16,
                animatedUi ? SettingsUiAnimation.pulseColor(now) : 0xFFFFFFFF);
        String localized = localizedStatus();
        int belowSave = layout.saveY + 24;
        int messageY = belowSave <= height - 10 ? belowSave : SettingsScreenLayout.COMPACT_STATUS_Y;
        // Read the worker's result once, so the message and its colour come from the same write.
        TestStatus engineTest = testStatus;
        if (!status.isEmpty()) {
            graphics.centered(literal(status), width / 2, messageY, 0xFFFF5555);
        } else if (!engineTest.message.isEmpty()) {
            graphics.centered(literal(engineTest.message), width / 2, messageY,
                    engineTest.error ? 0xFFFF5555 : 0xFF55FF55);
        } else if (!localized.isEmpty()) {
            graphics.centered(literal(localized), width / 2, messageY,
                    cachedStatusIsFailure ? 0xFFFF5555 : 0xFF55FF55);
        } else {
            if (activeTab == Tab.OUTGOING) {
                graphics.centered(translatable("screen.universal_translator.info.outgoing_hint"),
                        width / 2, layout.contentRow(2) + 6, 0xFFA0A0A0);
            } else if (activeTab == Tab.GENERAL) {
                graphics.centered(translatable("screen.universal_translator.info.keybind"),
                        width / 2, layout.contentRow(3) + 24, 0xFFA0A0A0);
            }
        }
        if (activeTab == Tab.ENGINE) {
            renderEngineInfo(graphics);
        }
        return opening;
    }

    protected final void renderSettingsAfter(ForgeScreenCanvas graphics, int mouseX, int mouseY,
                                              float opening) {
        renderHudDragPreview(graphics);
        if (animatedUi) {
            int overlayAlpha = SettingsUiAnimation.openingOverlayAlpha(opening);
            if (overlayAlpha > 0) {
                graphics.fill(0, 0, width, height, overlayAlpha << 24);
            }
        }
        // The catalog overlay and the selection list are mutually exclusive: the overlay consumes
        // every click while it is open, and a click that closes the list also ends the selection.
        if (activeTab == Tab.ENGINE && modelListOpen) {
            renderModelList(graphics, mouseX, mouseY);
            return;
        }
        if (openSelection == SettingsSelectionList.Kind.NONE) {
            return;
        }
        String[] values = SettingsSelectionList.values(openSelection);
        SettingsSelectionList.Layout list = SettingsSelectionList.layout(width, height, values.length);
        graphics.fill(0, 0, width, height, 0xB0080B10);
        graphics.fill(list.panelLeft() - 1, list.panelTop - 1,
                list.panelRight() + 1, list.panelBottom + 1, 0xFF55D6FF);
        graphics.fill(list.panelLeft(), list.panelTop,
                list.panelRight(), list.panelBottom, 0xF018202A);
        graphics.centered(translatable(selectionTitleKey()),
                width / 2, list.panelTop + 9, 0xFFFFFFFF);
        for (int index = 0; index < values.length; index++) {
            int x = list.x(index);
            int y = list.y(index);
            boolean hovered = mouseX >= x && mouseX < x + list.buttonWidth
                    && mouseY >= y && mouseY < y + list.buttonHeight;
            boolean selected = values[index].equalsIgnoreCase(selectionValue());
            graphics.fill(x, y, x + list.buttonWidth, y + list.buttonHeight,
                    hovered ? 0xFF3B6178 : selected ? 0xFF28533D : 0xFF303844);
            graphics.centered(literal(
                    SettingsSelectionList.displayName(openSelection, values[index], key -> tr(key))),
                    x + list.buttonWidth / 2, y + Math.max(1, (list.buttonHeight - 8) / 2),
                    selected ? 0xFF55FF88 : 0xFFFFFFFF);
        }
    }

    protected final boolean handleSelectionClick(double mouseX, double mouseY) {
        // The catalog overlay owns the click first: the per-version screens call this after the HUD
        // drag seam, which refuses on the engine tab, so the picker still gets first claim.
        if (activeTab == Tab.ENGINE && modelListOpen) {
            handleModelListClick(mouseX, mouseY);
            return true;
        }
        if (openSelection == SettingsSelectionList.Kind.NONE) {
            return false;
        }
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
            }
            else if (openSelection == SettingsSelectionList.Kind.TARGET_LANGUAGE) targetLanguage = values[selected];
            else outgoingTargetLanguage = values[selected];
            openSelection = SettingsSelectionList.Kind.NONE;
            refreshLabels();
            updateTabVisibility();
            return true;
        } else if (!list.contains(mouseX, mouseY)) {
            // The overlay is modal, so the click that dismisses it is consumed here. Returning
            // false let the press fall through to whatever sat underneath: at a 320x240 logical
            // resolution the language list ends at y≈154 while Save and Cancel sit at y=216..236,
            // so the natural "click the dimmed area to dismiss" gesture also pressed Save (storing
            // everything) or Cancel (discarding every unsaved change without asking).
            openSelection = SettingsSelectionList.Kind.NONE;
            return true;
        }
        return true;
    }

    /**
     * Whether a full-screen overlay of this screen currently owns the keyboard.
     *
     * <p>Both overlays cover the form completely, so a key that reaches the focused field behind one
     * types into a field the player cannot see: the prompt, the endpoint or the blocked keywords,
     * which the next Save then writes. Version layers gate their own key and character callbacks on
     * this.
     */
    protected final boolean isOverlayOpen() {
        return modelListOpen || openSelection != SettingsSelectionList.Kind.NONE;
    }

    /**
     * ESC closes the open overlay instead of letting the key reach the screen.
     *
     * <p>Version-neutral seam like {@link #handleSelectionClick(double, double)}: the per-version
     * screens call it from their own {@code keyPressed} bridge, so this class never mentions a
     * keyboard signature that changed between generations.
     *
     * @param keyCode platform key code
     * @return true when the overlay consumed the key
     */
    protected final boolean handleSelectionKey(int keyCode) {
        if (keyCode != 256) {   // GLFW_KEY_ESCAPE
            return false;
        }
        if (activeTab == Tab.ENGINE && modelListOpen) {
            modelListOpen = false;
            return true;
        }
        if (openSelection == SettingsSelectionList.Kind.NONE) {
            return false;
        }
        openSelection = SettingsSelectionList.Kind.NONE;
        return true;
    }

    /**
     * ESC closes the open overlay instead of the screen.
     *
     * <p>The generation that changed the keyboard callback (1.21.9) cannot use
     * {@link #handleSelectionKey(int)}, so this hook is what keeps ESC from dropping a whole
     * session of unsaved changes there.
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

    /**
     * Starts a HUD indicator drag when the press lands on the preview handle.
     *
     * <p>Version-neutral seam, exactly like {@link #handleSelectionClick(double, double)}: this base
     * class never mentions a Minecraft mouse signature, and the per-version screens call it from
     * their own {@code mouseClicked} bridge.
     *
     * <p>Refuses while a selection list is open so the list keeps first claim on the click and the
     * pre-existing click handling is unchanged even though the screens now ask for a drag first.
     */
    protected boolean handleDragStart(double mouseX, double mouseY) {
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

    /**
     * Moves the pending offsets by the pointer delta, clamped to what the preview can represent.
     */
    protected boolean handleDragMove(double mouseX, double mouseY) {
        if (!hudDragging) {
            return false;
        }
        // One preview pixel is not one GUI pixel: the panel is a scaled-down screen, so undo the
        // scale before turning the pointer delta into a real offset. Both axes share one scale, so
        // a diagonal drag moves the handle diagonally.
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

    /** Ends the drag. The offsets are only persisted later by {@link #saveAndApply()}. */
    protected boolean handleDragEnd() {
        if (!hudDragging) {
            return false;
        }
        hudDragging = false;
        return true;
    }

    /**
     * Bounds an offset so the indicator stays inside the previewed screen.
     *
     * <p>Clamping the offset rather than the drawn position is what removes the dead zone: while the
     * draw position was clamped the offset kept growing, so the user had to drag back through
     * invisible distance before the handle moved again. One offset unit is one pixel of the
     * 320x240 virtual screen, so the bounds are expressed in those pixels.
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
     * factor of five: a 320x240 screen was drawn 0.97x across and 0.2x down, which put the bottom
     * corners in the wrong place before any drag and made a diagonal drag move the handle along a
     * straight line that did not match the pointer. The smaller of the two fits the whole screen.
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

    /**
     * Draws the HUD indicator preview strip plus its drag handle.
     *
     * <p>Called from {@link #renderSettingsAfter} so it lands after {@code super.render}; the
     * selection overlay is drawn afterwards and therefore still covers it.
     */
    private void renderHudDragPreview(ForgeScreenCanvas graphics) {
        if (activeTab != Tab.HUD) {
            return;
        }
        int px = hudPreviewX();
        int py = hudPreviewY();
        int pw = hudPreviewW();
        int ph = hudPreviewH();
        if (ph <= 0) {
            return;
        }
        graphics.fill(px, py, px + pw, py + ph, 0x40000000);
        int hx = hudHandleX();
        int hy = hudHandleY();
        graphics.fill(hx, hy, hx + HUD_DRAG_HANDLE_SIZE, hy + HUD_DRAG_HANDLE_SIZE,
                hudDragging ? 0xFFFFAA00 : 0xFF00AA00);
    }

    private String selectionValue() {
        if (openSelection == SettingsSelectionList.Kind.PROVIDER) return provider;
        if (openSelection == SettingsSelectionList.Kind.TARGET_LANGUAGE) return targetLanguage;
        return outgoingTargetLanguage;
    }

    private String selectionTitleKey() {
        if (openSelection == SettingsSelectionList.Kind.PROVIDER) {
            return "screen.universal_translator.selection.provider";
        }
        if (openSelection == SettingsSelectionList.Kind.TARGET_LANGUAGE) {
            return "screen.universal_translator.selection.target_language";
        }
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
    private void renderEngineInfo(ForgeScreenCanvas graphics) {
        int y = engineRow(5) + 24;
        // The prompt field carries no placeholder text on this version, so its hint is drawn as a
        // label just above it, the same way the LLM screen labels its fields.
        graphics.text(translatable("screen.universal_translator.diagnostics.prompt_hint"),
                layout().left, engineRow(4) - 11, 0xFFA0A0A0);
        if (isLlm()) {
            graphics.centered(translatable(
                            "screen.universal_translator.engine.summary",
                            trimForDisplay(llmEndpoint),
                            tr(llmApiKey == null || llmApiKey.isEmpty()
                                    ? "screen.universal_translator.engine.key_unset"
                                    : "screen.universal_translator.engine.key_set")),
                    width / 2, y, 0xFFA0A0A0);
        } else if (!isOffline() && !LIBRETRANSLATE_PROVIDER.equalsIgnoreCase(provider)) {
            graphics.centered(translatable("screen.universal_translator.engine.config_file"),
                    width / 2, y, 0xFFA0A0A0);
        }
        graphics.centered(translatable(isOffline()
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
        startWorker("universal-translator-model-list", () -> {
            OpenAiModelCatalog.Catalog catalog = null;
            String failure = "";
            try {
                catalog = OpenAiModelCatalog.fetchCatalog(provider, endpointValue, keyValue);
            } catch (Exception error) {
                failure = describe(error);
            } finally {
                // Released in finally, not after the catch: a LinkageError or an OutOfMemoryError
                // skipped the old assignment and left the tab's buttons dead for the whole session.
                fetchingModels = false;
            }
            if (!failure.isEmpty()) {
                setTestStatus(tr("screen.universal_translator.llm.fetch_failed", failure), true);
                return;
            }
            if (catalog == null) {
                // fetchCatalog answers null for a body it cannot read instead of throwing.
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
            setTestStatus("", false);
        });
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
        startWorker("universal-translator-connection-test", () -> {
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
                    EndpointPolicy.requireSafeEndpoint(url);
                    String body = probeClient.get(URI.create(url),
                            Collections.<String, String>emptyMap());
                    // The languages catalog is JSON; anything else means this is not LibreTranslate.
                    JsonStrings.parse(body);
                    message = tr("screen.universal_translator.engine.test_ok_libre");
                    error = false;
                } else {
                    // Validate the provider the screen is showing, not the one this snapshot was
                    // opened with: they differ until the form is saved, and answering for the old
                    // one told users the wrong thing about the service they had just switched to.
                    currentConfig().withProvider(provider).validateProviderConfiguration();
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

    /**
     * Runs one screen action on the shared worker pool instead of starting a thread per click.
     *
     * <p>Each press used to build a {@code Thread}: a thread object, its thread-locals and a
     * reserved stack, several times per session, all under one fixed name so a stack dump could not
     * tell two of them apart. Two workers cover the actions a screen can overlap (fetch, test,
     * export) and keep a long download from blocking a quick probe.
     */
    static void startWorker(String name, Runnable job) {
        try {
            SCREEN_WORKERS.execute(job);
        } catch (RuntimeException rejected) {
            // Only reachable once the pool has been shut down; the screen keeps its last status.
            DebugLog.global().log("screens", "worker", "rejected", "name", name);
        }
    }

    /** One coherent "engine test" line: the message and the colour it is drawn in. */
    private static final class TestStatus {
        static final TestStatus EMPTY = new TestStatus("", false);

        final String message;
        final boolean error;

        TestStatus(String message, boolean error) {
            this.message = message == null ? "" : message;
            this.error = error;
        }
    }

    private void setTestStatus(String message, boolean error) {
        testStatus = new TestStatus(message, error);
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
    private void renderModelList(ForgeScreenCanvas graphics, int mouseX, int mouseY) {
        // One read of the volatile list for the whole frame. The fetch worker replaces it wholesale,
        // and reading the field again inside the loop and for the page counter let the bound come from
        // one list and the element from the next: IndexOutOfBoundsException out of the render loop,
        // which closes the client. Everything below works on the local snapshot and on the page this
        // snapshot can actually show.
        List<String> models = fetchedModels;
        graphics.fill(0, 0, width, height, 0xFF101010);
        graphics.centered(translatable("screen.universal_translator.llm.select_model",
                models.size()), width / 2, 20, 0xFFFFFFFF);
        int rows = ModelListPaging.rows(height);
        modelPage = ModelListPaging.clampPage(modelPage, models.size(), rows);
        int first = modelPage * rows;
        for (int row = 0; row < rows && first + row < models.size(); row++) {
            int rowY = MODEL_LIST_TOP + row * MODEL_ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + MODEL_ROW_HEIGHT;
            graphics.text(literal((hovered ? "> " : "  ") + models.get(first + row)),
                    24, rowY, hovered ? 0xFFFFD060 : 0xFFE0E0E0);
        }
        int pages = ModelListPaging.pageCount(models.size(), rows);
        graphics.centered(literal("< " + (modelPage + 1) + "/" + pages + " >"),
                width / 2, height - 46, 0xFFFFFFFF);
        graphics.centered(translatable("screen.universal_translator.llm.select_back"),
                width / 2, height - 30, 0xFFFFD060);
        graphics.centered(translatable("screen.universal_translator.llm.select_hint"),
                width / 2, height - 16, 0xFFA0A0A0);
    }

    private void handleModelListClick(double mouseX, double mouseY) {
        // Same snapshot rule as renderModelList: the paging and the selected entry must come from one
        // list, or a fetch that lands mid-click selects a model the player never saw.
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
        if (mouseY < MODEL_LIST_TOP) {
            return;
        }
        modelPage = ModelListPaging.clampPage(modelPage, models.size(), rows);
        int row = ModelListPaging.rowAt(mouseY);
        int index = ModelListPaging.index(modelPage, rows, row);
        if (row >= rows || index >= models.size()) {
            return;
        }
        llmModel = models.get(index);
        modelListOpen = false;
        refreshLabels();
    }

    @Override
    public final void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public final boolean isPauseScreen() {
        return false;
    }

    private void loadLlmSettings(String selectedProvider) {
        if (!TranslationProviderCatalog.usesLlmEditor(selectedProvider)) {
            return;
        }
        this.llmEndpoint = original.editorEndpoint(selectedProvider);
        this.llmApiKey = original.editorApiKey(selectedProvider);
        this.llmModel = original.editorModel(selectedProvider);
    }

    final void applyLlmSettings(String endpoint, String model, String apiKey) {
        this.llmEndpoint = endpoint;
        this.llmModel = model;
        this.llmApiKey = apiKey;
    }

    final String llmApiKey() {
        return llmApiKey;
    }

    private boolean isOffline() {
        return "offline".equalsIgnoreCase(provider);
    }

    private boolean isLlm() {
        return TranslationProviderCatalog.usesLlmEditor(provider);
    }

    private static String onOff(boolean value) {
        return tr(UiTextKeys.onOff(value));
    }

    private static String colorLabel(TranslationTextColor color) {
        return tr("value.universal_translator.color." + color.configName().replace('-', '_'));
    }

    private static String cornerLabel(HudIndicatorCorner corner) {
        return tr("value.universal_translator.hud_corner." + corner.configName().replace('-', '_'));
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

    private static String tr(String key, Object... arguments) {
        return translatable(key, arguments).getString();
    }

    private Layout layout() {
        Layout cached = cachedLayout;
        if (cached != null && cachedLayoutWidth == width && cachedLayoutHeight == height) {
            return cached;
        }
        SettingsScreenLayout.Geometry geometry = SettingsScreenLayout.calculate(width, height, 5);
        Layout built = new Layout(geometry.left(), geometry.right(), geometry.totalWidth(), geometry.buttonWidth(),
                geometry.top(), geometry.rowStep(), geometry.targetY(), geometry.endpointY(), geometry.saveY(),
                geometry.tabY(), geometry.tabWidth(), geometry.tabGap(), geometry.contentTop(), geometry.contentRowStep());
        cachedLayout = built;
        cachedLayoutWidth = width;
        cachedLayoutHeight = height;
        return built;
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

        private int tabY() {
            return tabY;
        }

        private int tabWidth() {
            return tabWidth;
        }

        private int contentRow(int index) {
            return contentTop + contentRowStep * index;
        }
    }
}

abstract class UniversalTranslatorDiagnosticsScreenBase extends Screen {
    /** Vertical distance between two text lines. */
    private static final int LINE_STEP = 17;
    /** First line's y, just under the title. */
    private static final int LINE_START = 43;
    /**
     * How long a built page may be reused.
     *
     * <p>{@code renderDiagnostics} runs every frame and rebuilding the page localizes 4–10 lines,
     * goes through the runtime snapshot and — on the counters page — re-sorts 512 latencies. The
     * window matches the runtime's own diagnostics cache, so the page can never be more than one
     * window behind what that cache would have answered anyway.
     */
    private static final long LINE_REFRESH_MILLIS = 250L;
    /** Name of the one background thread that writes diagnostic archives. */
    private static final String EXPORT_THREAD_NAME = "universal-translator-diagnostics-export";
    /**
     * Serializes archive exports away from the render thread.
     *
     * <p>Both buttons used to run the whole I/O chain inline: the debug log is read line by line,
     * redacted with a regex per line, copied into a {@code StringBuilder} and deflated into a zip,
     * all while the client thread was inside the click handler — hundreds of milliseconds to seconds
     * of frozen window on a multi-megabyte log, with every tick stopped. One daemon thread keeps the
     * cost off that thread and off the frame budget; a single thread also keeps two presses from
     * writing the same archive twice.
     */
    private static final ExecutorService EXPORTER =
            Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, EXPORT_THREAD_NAME);
                thread.setDaemon(true);
                return thread;
            });

    private final Screen parent;
    private final ForgeConfig config;
    private Button debugButton;
    private Button pageButton;
    private Button resetButton;
    private Button exportButton;
    private Button bundleButton;
    /** Whether the counters page is shown instead of the engine diagnostics page. */
    private boolean statisticsPage;
    private boolean debugEnabled;
    /** Rendered page contents, or {@code null} when the next frame must rebuild them. */
    private List<String> cachedLines;
    /** {@link System#currentTimeMillis()} when {@link #cachedLines} was built. */
    private long cachedLinesAt;
    /** Written from the export thread, read by the render thread. */
    private volatile String exportStatus = "";
    private volatile boolean exportFailed;
    /** True while an archive is being written; the buttons are flat and further presses ignored. */
    private volatile boolean exporting;

    UniversalTranslatorDiagnosticsScreenBase(Screen parent, ForgeConfig config) {
        super(translatable("screen.universal_translator.diagnostics.title"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    protected final void init() {
        ForgeConfig live = liveConfig();
        if (live != null) {
            // Re-read here rather than in the constructor so a rebuild after a resize, or a change
            // made by another screen, is reflected instead of showing a stale value.
            debugEnabled = live.debugLog();
        }
        // Entering the screen, or rebuilding it after a resize, is the natural point to recompute.
        invalidateLines();
        int totalWidth = Math.max(180, Math.min(320, width - 24));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (width - totalWidth) / 2;

        debugButton = addRenderableWidget(ButtonSpec.of(literal(""), button -> {
            debugEnabled = !debugEnabled;
            applyDebugMode();
        }).bounds(left, height - 76, buttonWidth, 20).build());
        bundleButton = addRenderableWidget(ButtonSpec.of(
                translatable("screen.universal_translator.debug.bundle"),
                button -> exportBundle())
                .bounds(left + buttonWidth + gap, height - 76, buttonWidth, 20).build());

        pageButton = addRenderableWidget(ButtonSpec.of(literal(""), button -> {
            statisticsPage = !statisticsPage;
            invalidateLines();
            refreshPageLabels();
        }).bounds(left, height - 52, buttonWidth, 20).build());
        resetButton = addRenderableWidget(ButtonSpec.of(
                translatable("screen.universal_translator.stats.reset"),
                button -> resetStatistics())
                .bounds(left + buttonWidth + gap, height - 52, buttonWidth, 20).build());

        addRenderableWidget(ButtonSpec.of(
                translatable("screen.universal_translator.diagnostics.back"), button -> onClose())
                .bounds(left, height - 28, buttonWidth, 20).build());
        exportButton = addRenderableWidget(ButtonSpec.of(
                translatable("screen.universal_translator.diagnostics.export"), button -> exportLog())
                .bounds(left + buttonWidth + gap, height - 28, buttonWidth, 20).build());
        refreshDebugLabel();
        refreshPageLabels();
        refreshExportButtons();
    }

    /** Drops the built page so the next frame rebuilds it once. */
    private void invalidateLines() {
        cachedLines = null;
        cachedLinesAt = 0L;
    }

    /** True while an archive is being written; both archive buttons go flat so one cannot queue twice. */
    private void refreshExportButtons() {
        boolean idle = !exporting;
        if (bundleButton != null) {
            bundleButton.active = idle;
        }
        if (exportButton != null) {
            exportButton.active = idle;
        }
    }

    /**
     * The configuration the controls read and write.
     *
     * <p>The runtime's copy is preferred so a screen rebuilt after a resize, or a change made by
     * another screen, is picked up; the configuration handed to the constructor is the fallback for
     * the window before the first load.
     */
    private ForgeConfig liveConfig() {
        ForgeConfig live = ForgeTranslationRuntime.diagnosticsConfig();
        return live == null ? config : live;
    }

    private void refreshDebugLabel() {
        if (debugButton == null) {
            return;
        }
        debugButton.setMessage(translatable("screen.universal_translator.debug.mode",
                tr(UiTextKeys.onOff(debugEnabled))));
    }

    private void refreshPageLabels() {
        if (pageButton != null) {
            pageButton.setMessage(translatable(statisticsPage
                    ? "screen.universal_translator.stats.show_diagnostics"
                    : "screen.universal_translator.stats.show"));
        }
        if (resetButton != null) {
            // Only the counters page can be cleared, so the button says so by going flat.
            resetButton.active = statisticsPage;
        }
    }

    /**
     * Switches the request trace on or off and persists the switch.
     *
     * <p>{@link DebugLog} is reconfigured directly because the switch takes effect immediately; the
     * configuration write only makes it survive the next launch.
     */
    private void applyDebugMode() {
        refreshDebugLabel();
        ForgeConfig live = liveConfig();
        if (live == null) {
            exportStatus = tr("screen.universal_translator.diagnostics.settings_unavailable");
            exportFailed = true;
            return;
        }
        try {
            ForgeConfig updated = live.withDebugLog(debugEnabled);
            ForgeTranslationRuntime.initialize(updated);
            ForgeConfigWriter.saveNow(updated);
            Path configFile = updated.configFile();
            DebugLog.global().configure(
                    configFile.resolveSibling(DebugLog.FILE_NAME), debugEnabled);
            exportStatus = tr("screen.universal_translator.debug.changed",
                    tr(UiTextKeys.onOff(debugEnabled)));
            exportFailed = false;
            invalidateLines();
        } catch (Exception failure) {
            exportStatus = settingsFailure(failure);
            exportFailed = true;
        }
    }

    /** Clears the process-wide counters and writes the cleared values out. */
    private void resetStatistics() {
        TranslationStats.global().reset();
        exportStatus = tr("screen.universal_translator.stats.reset_done");
        exportFailed = false;
        invalidateLines();
    }

    private String settingsFailure(Exception failure) {
        String message = failure.getMessage();
        return tr("screen.universal_translator.diagnostics.settings_failed",
                message == null || message.trim().isEmpty()
                        ? failure.getClass().getSimpleName() : message);
    }

    protected final void renderDiagnostics(ForgeScreenCanvas graphics) {
        graphics.fill(0, 0, width, height, 0xE510151C);
        graphics.fill(Math.max(5, width / 2 - 190), 8,
                Math.min(width - 5, width / 2 + 190), height - 34, 0xD51A232E);
        graphics.centered(statisticsPage
                        ? translatable("screen.universal_translator.stats.title")
                        : title,
                width / 2, 18, 0xFFFFFFFF);
        int left = Math.max(10, (width - Math.min(360, width - 20)) / 2);
        List<String> lines = displayLines();
        // Stop before the status line and the button rows. A short window shows fewer lines instead
        // of drawing text on top of a button, and the last visible line says that it was cut.
        int limit = Math.max(1, (height - 96 - LINE_START) / LINE_STEP + 1);
        int shown = Math.min(lines.size(), limit);
        int y = LINE_START;
        for (int index = 0; index < shown; index++) {
            String line = lines.get(index);
            if (index == shown - 1 && shown < lines.size()) {
                line = line + " ...";
            }
            graphics.text(literal(line), left, y, 0xFFD0D0D0);
            y += LINE_STEP;
        }
        graphics.centered(translatable(statisticsPage
                        ? "screen.universal_translator.stats.note"
                        : "screen.universal_translator.diagnostics.note"),
                width / 2, Math.min(y + 7, height - 88), 0xFF909090);
        if (!exportStatus.isEmpty()) {
            graphics.centered(literal(exportStatus), width / 2, height - 82,
                    exportFailed ? 0xFFFF5555 : 0xFF55FF88);
        }
    }

    /** The lines the current page shows, served from {@link #cachedLines} while it is still fresh. */
    private List<String> displayLines() {
        long now = System.currentTimeMillis();
        List<String> cached = cachedLines;
        if (cached == null || now - cachedLinesAt >= LINE_REFRESH_MILLIS) {
            cached = computeLines();
            cachedLines = cached;
            cachedLinesAt = now;
        }
        return cached;
    }

    /**
     * Rebuilds the page: 4–10 localized lines plus, on the counters page, the statistics snapshot
     * behind {@code TranslationStats}, which boxes 512 latencies and sorts them.
     */
    private List<String> computeLines() {
        try {
            return statisticsPage
                    ? TranslationStats.global().localizedLines(
                            UniversalTranslatorDiagnosticsScreenBase::tr)
                    : safeLines();
        } catch (RuntimeException ignored) {
            return Collections.singletonList(
                    tr("screen.universal_translator.diagnostics.unavailable"));
        }
    }

    private List<String> safeLines() {
        // Nothing can be reported before the first configuration load, and the runtime would answer
        // with an empty status that the page localizes as "waiting for the first translation". The
        // shared key says what is actually going on.
        if (ForgeTranslationRuntime.diagnosticsConfig() == null) {
            return Collections.singletonList(
                    tr("screen.universal_translator.diagnostics.settings_unavailable"));
        }
        try {
            TranslationDiagnosticsSnapshot snapshot = ForgeTranslationRuntime.diagnostics();
            return snapshot == null
                    ? Collections.singletonList(tr("screen.universal_translator.diagnostics.unavailable"))
                    : snapshot.localizedLines(UniversalTranslatorDiagnosticsScreenBase::tr);
        } catch (RuntimeException ignored) {
            return Collections.singletonList(tr("screen.universal_translator.diagnostics.unavailable"));
        }
    }

    private void exportLog() {
        final Path directory = config.offlineDirectory.getParent()
                .resolve("universal-translator-diagnostics");
        // The page is already built and cached by the render thread; the worker only writes it out.
        final List<String> lines = safeLines();
        startExport("screen.universal_translator.diagnostics.export",
                "screen.universal_translator.diagnostics.exported", null,
                () -> DiagnosticsLogExporter.export(directory, lines));
    }

    /**
     * Writes one archive holding the diagnostics, the debug log, the redacted configuration and the
     * environment into the configuration directory.
     */
    private void exportBundle() {
        ForgeConfig live = liveConfig();
        if (live == null) {
            exportStatus = tr("screen.universal_translator.diagnostics.settings_unavailable");
            exportFailed = true;
            return;
        }
        final Path configFile = live.configFile();
        // Snapshot the page here: the worker thread must never walk this screen's state.
        final List<String> lines = safeLines();
        startExport("screen.universal_translator.debug.bundle",
                "screen.universal_translator.debug.bundle_exported", "", () -> {
                    Path archive = DiagnosticsBundleExporter.export(
                            configFile.getParent(), configFile,
                            configFile.resolveSibling(DebugLog.FILE_NAME), "forge", lines);
                    return archive;
                });
    }

    /**
     * Runs one export on {@link #EXPORTER} and reports the outcome back on the client thread.
     *
     * <p>The click handler returns immediately: the status line names the export that is running and
     * both archive buttons go flat until the worker reports. No {@code CompletableFuture} is involved
     * on purpose — any join or {@code get()} on the render path would reintroduce the freeze.
     *
     * @param busyKey       key naming the export while it runs; an ellipsis is appended
     * @param successKey    key shown when the job returns
     * @param successExtras named argument for {@code successKey}, or {@code null} when it takes none
     * @param job           performs the I/O; its return value is used only when non-null
     */
    private void startExport(String busyKey, String successKey, String successExtras, ExportJob job) {
        if (exporting) {
            return;
        }
        exporting = true;
        exportFailed = false;
        exportStatus = tr(busyKey) + "\u2026";
        refreshExportButtons();
        EXPORTER.execute(() -> {
            Path written = null;
            boolean failed;
            try {
                written = job.run();
                failed = false;
            } catch (Exception ignored) {
                failed = true;
            }
            finishExport(successKey, successExtras, written, failed);
        });
    }

    /** Publishes one export result on the client thread and re-enables the buttons. */
    private void finishExport(String successKey, String successExtras, Path written, boolean failed) {
        Runnable apply = () -> {
            exporting = false;
            exportFailed = failed;
            if (failed) {
                exportStatus = tr("screen.universal_translator.diagnostics.export_failed");
            } else if (successExtras == null) {
                exportStatus = tr(successKey);
            } else {
                exportStatus = tr(successKey, written == null
                        ? successExtras : written.getFileName().toString());
            }
            refreshExportButtons();
        };
        if (minecraft == null) {
            apply.run();
        } else {
            minecraft.execute(apply);
        }
    }

    /** One export job, run on the exporter thread and never on the client thread. */
    private interface ExportJob {
        Path run() throws Exception;
    }

    @Override
    public final void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public final boolean isPauseScreen() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return translatable(key, arguments).getString();
    }
}

abstract class UniversalTranslatorLlmConfigScreenBase extends Screen {
    /** Height of a single catalog row in the model picker overlay. */
    private static final int LIST_ROW_HEIGHT = ModelListPaging.ROW_HEIGHT;
    /** Y of the first catalog row; the picker title sits above it. */
    private static final int LIST_TOP = ModelListPaging.LIST_TOP;
    private static final int ESCAPE_KEY = 256;

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
    private volatile boolean fetching;
    // Written by the fetch worker, read by the render thread: see the note on the engine tab's flags.
    private volatile boolean modelListOpen;
    private volatile int modelPage;
    /** Canvas captured by renderLlm so the catalog overlay can be drawn over the widgets. */
    private ForgeScreenCanvas canvas;

    UniversalTranslatorLlmConfigScreenBase(UniversalTranslatorConfigScreenBase parent,
                                            String endpoint, String model, boolean hasStoredKey) {
        super(translatable("screen.universal_translator.llm.title"));
        this.parent = parent;
        this.initialEndpoint = endpoint;
        this.initialModel = model;
        this.hasStoredKey = hasStoredKey;
    }

    @Override
    protected final void init() {
        // init() also runs on every resize (window drag, F11, GUI scale). Re-applying the
        // constructor snapshot silently discarded whatever had been typed, and the API key field had
        // no value at all - so a pasted key vanished on the first resize.
        if (endpoint != null) {
            initialEndpoint = endpoint.getValue();
        }
        if (model != null) {
            initialModel = model.getValue();
        }
        if (apiKey != null) {
            initialApiKey = apiKey.getValue();
        }
        int formWidth = Math.max(180, Math.min(360, width - 20));
        int left = (width - formWidth) / 2;
        int top = Math.max(42, (height - 174) / 2);
        endpoint = addRenderableWidget(new EditBox(font, left, top, formWidth, 20,
                translatable("screen.universal_translator.llm.endpoint")));
        endpoint.setMaxLength(UiLimits.ENDPOINT_MAX_CHARS);
        endpoint.setValue(initialEndpoint);
        model = addRenderableWidget(new EditBox(font, left, top + 36, formWidth, 20,
                translatable("screen.universal_translator.llm.model")));
        model.setMaxLength(UiLimits.MODEL_ID_MAX_CHARS);
        model.setValue(initialModel);
        apiKey = addRenderableWidget(new EditBox(font, left, top + 72, formWidth, 20,
                translatable("screen.universal_translator.llm.api_key")));
        apiKey.setMaxLength(UiLimits.SECRET_MAX_CHARS);
        apiKey.setValue(initialApiKey);
        int gap = 8;
        int buttonWidth = (formWidth - gap) / 2;
        addRenderableWidget(ButtonSpec.of(translatable("screen.universal_translator.llm.save"),
                button -> save()).bounds(left, top + 108, buttonWidth, 20).build());
        addRenderableWidget(ButtonSpec.of(translatable("gui.cancel"), button -> onClose())
                .bounds(left + buttonWidth + gap, top + 108, buttonWidth, 20).build());
        addRenderableWidget(ButtonSpec.of(
                        translatable("screen.universal_translator.llm.fetch_models"), button -> fetchModels())
                .bounds(left, top + 132, formWidth, 20).build());
    }

    protected final void renderLlm(ForgeScreenCanvas graphics) {
        canvas = graphics;
        int formWidth = Math.max(180, Math.min(360, width - 20));
        int left = (width - formWidth) / 2;
        int top = Math.max(42, (height - 174) / 2);
        graphics.centered(title, width / 2, 18, 0xFFFFFFFF);
        graphics.text(translatable("screen.universal_translator.llm.endpoint_hint"),
                left, top - 11, 0xFFA0A0A0);
        graphics.text(translatable("screen.universal_translator.llm.model_hint"),
                left, top + 25, 0xFFA0A0A0);
        graphics.text(translatable(hasStoredKey
                        ? "screen.universal_translator.llm.key_saved_hint"
                        : "screen.universal_translator.llm.key_empty_hint"),
                left, top + 61, 0xFFA0A0A0);
        if (!status.isEmpty()) {
            graphics.centered(literal(status), width / 2, top + 158,
                    statusIsError ? 0xFFFF5555 : 0xFFE0E0E0);
        }
    }

    private void save() {
        String endpointValue = endpoint.getValue().trim();
        String modelValue = model.getValue().trim();
        if (endpointValue.isEmpty() || modelValue.isEmpty()) {
            status = translatable("error.universal_translator.llm_required").getString();
            return;
        }
        try {
            // The same policy the connection test applies. Saving without it wrote an arbitrary
            // scheme, host and port into the endpoint every later translation request goes to.
            EndpointPolicy.requireSafeEndpoint(endpointValue);
        } catch (RuntimeException rejected) {
            status = describe(rejected);
            statusIsError = true;
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
        UniversalTranslatorConfigScreenBase.startWorker("universal-translator-model-list", () -> {
            List<String> models = Collections.emptyList();
            String failure = "";
            try {
                models = OpenAiModelCatalog.fetch(endpointValue, keyValue);
            } catch (Exception error) {
                failure = describe(error);
            } finally {
                // Released in finally so an Error cannot leave the fetch button dead for the
                // whole life of this screen.
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

    /**
     * Draws the fetched catalog over the form. Each concrete screen calls this right after
     * {@code super.render} because the render signature differs per version (PoseStack before
     * 1.20, GuiGraphics after).
     */
    protected final void renderModelListOverlay(int mouseX, int mouseY) {
        if (modelListOpen && canvas != null) {
            renderModelList(canvas, mouseX, mouseY);
        }
    }

    /** Draws the fetched catalog over the form so a served model can be picked instead of typed. */
    private void renderModelList(ForgeScreenCanvas graphics, int mouseX, int mouseY) {
        // Snapshot first: see the note on UniversalTranslatorConfigScreenBase.renderModelList.
        List<String> models = fetchedModels;
        graphics.fill(0, 0, width, height, 0xFF101010);
        graphics.centered(translatable("screen.universal_translator.llm.select_model",
                models.size()), width / 2, 20, 0xFFFFFFFF);
        int rows = ModelListPaging.rows(height);
        modelPage = ModelListPaging.clampPage(modelPage, models.size(), rows);
        int first = modelPage * rows;
        for (int row = 0; row < rows && first + row < models.size(); row++) {
            int rowY = LIST_TOP + row * LIST_ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + LIST_ROW_HEIGHT;
            graphics.text(literal((hovered ? "> " : "  ") + models.get(first + row)),
                    24, rowY, hovered ? 0xFFFFD060 : 0xFFE0E0E0);
        }
        int pages = ModelListPaging.pageCount(models.size(), rows);
        graphics.centered(literal("< " + (modelPage + 1) + "/" + pages + " >"),
                width / 2, height - 46, 0xFFFFFFFF);
        graphics.centered(translatable("screen.universal_translator.llm.select_back"),
                width / 2, height - 30, 0xFFFFD060);
        graphics.centered(translatable("screen.universal_translator.llm.select_hint"),
                width / 2, height - 16, 0xFFA0A0A0);
    }

    /**
     * Handles a click while the catalog overlay is open.
     *
     * <p>Minecraft 1.21.9 replaced the primitive mouse parameters with an event object, so the
     * per-version screens own the {@code mouseClicked} signature and call this from their bridge.
     *
     * @return true when the overlay consumed the click
     */
    protected final boolean handleModelListClick(double mouseX, double mouseY) {
        if (!modelListOpen) {
            return false;
        }
        List<String> models = fetchedModels;
        int rows = ModelListPaging.rows(height);
        if (mouseY >= height - 54 && mouseY < height - 38) {
            int pages = ModelListPaging.pageCount(models.size(), rows);
            if (pages > 1) {
                modelPage = mouseX < width / 2.0
                        ? (modelPage + pages - 1) % pages : (modelPage + 1) % pages;
            }
            return true;
        }
        if (mouseY >= height - 38 && mouseY < height - 20) {
            modelListOpen = false;
            return true;
        }
        if (mouseY < LIST_TOP) {
            return true;
        }
        modelPage = ModelListPaging.clampPage(modelPage, models.size(), rows);
        int row = ModelListPaging.rowAt(mouseY);
        int index = ModelListPaging.index(modelPage, rows, row);
        if (row >= rows || index >= models.size()) {
            return true;
        }
        model.setValue(models.get(index));
        modelListOpen = false;
        setStatus("", false);
        return true;
    }

    /**
     * Handles a key press while the catalog overlay is open; the per-version screens call this
     * from their own keyboard bridge because that signature also changed in Minecraft 1.21.9.
     *
     * @return true when the overlay consumed the key
     */
    protected final boolean handleModelListKey(int keyCode) {
        if (!modelListOpen) {
            return false;
        }
        if (keyCode == ESCAPE_KEY) {
            modelListOpen = false;
        }
        return true;
    }

    /** Whether the catalog overlay currently owns the screen; version layers gate typing on it. */
    protected final boolean isModelListOpen() {
        return modelListOpen;
    }

    @Override
    public final void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public final boolean isPauseScreen() {
        return false;
    }

    private static String tr(String key, Object... arguments) {
        return translatable(key, arguments).getString();
    }
}

interface ForgeScreenCanvas {
    void fill(int left, int top, int right, int bottom, int color);
    void centered(Component text, int centerX, int y, int color);
    void text(Component text, int x, int y, int color);
}
