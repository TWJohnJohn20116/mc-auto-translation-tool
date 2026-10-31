package org.universaltranslator.fabric;

import org.universaltranslator.core.ModelListPaging;
import java.net.URI;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.universaltranslator.core.HudIndicatorColor;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HudIndicatorSettings;
import org.universaltranslator.core.HudIndicatorVisibility;
import org.universaltranslator.core.TranslationDisplayMode;
import org.universaltranslator.core.OfflineModel;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.core.TranslationStatusLocalizer;
import org.universaltranslator.core.TranslationTextColor;
import org.universaltranslator.core.TranslationPrompt;
import org.universaltranslator.core.TranslationProviderCatalog;
import org.universaltranslator.core.TranslationQuality;
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
abstract class UniversalTranslatorSettingsScreen extends Screen {
    /** The preview panel is a miniature of Minecraft's 320x240 minimum logical screen. */
    private static final int HUD_PREVIEW_SCREEN_WIDTH = HudPreviewGeometry.SCREEN_WIDTH;
    private static final int HUD_PREVIEW_SCREEN_HEIGHT = HudPreviewGeometry.SCREEN_HEIGHT;
    /** Height of one row in the engine tab's model picker overlay. */
    private static final int MODEL_ROW_HEIGHT = ModelListPaging.ROW_HEIGHT;
    /** Y of the first model row; the picker title sits above it. */
    private static final int MODEL_LIST_TOP = ModelListPaging.LIST_TOP;
    /** Room kept under the model rows for the paging, back, and hint lines. */
    private static final int MODEL_LIST_FOOTER_HEIGHT = ModelListPaging.FOOTER_HEIGHT;
    private static final String LIBRETRANSLATE_PROVIDER = "libretranslate";
    private static final String TRANSLATE_PATH = "/translate";
    private static final String LANGUAGES_PATH = "/languages";
    /** How long the runtime status line is reused; it only changes when a translation fails. */
    private static final long STATUS_LINE_CACHE_NANOS = 200L * 1_000_000L;
    /**
     * Worker for the engine tab's two requests.
     *
     * <p>Each button press used to build a fresh {@code Thread} — a thread object, a
     * {@code ThreadLocalMap} and a megabyte of reserved stack — with a fixed name, so several
     * same-named threads existed at once and tracing them in a thread dump was guesswork. Two
     * daemon threads are enough: the two in-flight flags already serialize each kind of request.
     */
    private static final java.util.concurrent.ExecutorService SCREEN_WORKERS =
            java.util.concurrent.Executors.newFixedThreadPool(2,
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable runnable) {
                            Thread thread = new Thread(runnable, "universal-translator-screen");
                            thread.setDaemon(true);
                            return thread;
                        }
                    });
    /** Shared request client for the reachability check; {@code HttpJsonClient} is a timeout holder. */
    private final HttpJsonClient httpClient = new HttpJsonClient(5000, 15000);

    /** Builds one settings button with the factory this Minecraft version exposes. */
    /** Creates a text field; the placeholder argument is a component from 1.16 onwards. */
    protected abstract TextFieldWidget newTextField(int x, int y, int width, int height,
            String initialText);

    /** The font renderer; every generation calls this field either font or textRenderer. */
    protected abstract TextRenderer textRenderer();

    /** Creates one button; every generation builds and labels these differently. */
    protected abstract SettingsButtonBuilder of(String label, ButtonWidget.PressAction action);

    /** Fills a rectangle on the render surface of this Minecraft version. */
    protected abstract void fillRect(Object canvas, int x1, int y1, int x2, int y2, int color);

    /** Draws horizontally centred shadowed text on the render surface of this Minecraft version. */
    protected abstract void drawCentered(Object canvas, TextRenderer renderer, String text,
            int centerX, int y, int color);

    /** Draws left-aligned shadowed text on the render surface of this Minecraft version. */
    protected abstract void drawLeft(Object canvas, TextRenderer renderer, String text,
            int x, int y, int color);

    /**
     * Builds one of the on-screen strings. Every generation localizes differently and 1.14 to 1.18
     * only accept a plain {@code String} where 1.19 onwards take a text component, so this seam
     * yields the localized text and the per-generation base wraps it back into whatever object
     * its widgets want.
     */
    protected abstract String trStr(String key, Object... arguments);

    /** Same as {@link #trStr}, for a string that needs no lookup. */
    protected abstract String lit(String value);

    /** The empty on-screen string; {@link #lit} with nothing in it, kept named for readability. */
    protected abstract String emptyStr();

    /** The client instance; {@code this.client} only exists from 1.20 onwards. */
    protected abstract MinecraftClient minecraftClient();

    /** Sets a button label. 1.14 takes a plain string, 1.16 onwards take a text component. */
    protected abstract void setLabel(ButtonWidget button, String label);

    /** Opens another screen; every generation spells this differently. */
    protected abstract void showScreen(Screen screen);

    /** Registers one button with this generation screen; 1.16 spells this differently. */
    protected abstract ButtonWidget addSettingButton(ButtonWidget button);

    /** Registers one text field with this generation screen; 1.16 spells this differently. */
    protected abstract TextFieldWidget addSettingField(TextFieldWidget field);

    protected final Screen parent;
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
    /**
     * Selected provider name.
     *
     * <p>Volatile because the engine tab's worker threads compare it against the value they started
     * with, to drop a model list that belongs to a provider the player has already switched away
     * from. Without the guarantee the worker could keep reading the old name and publish the old
     * provider's catalogue over the new one.
     */
    private volatile String provider;
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
    /** The tab whose button labels are currently installed; see {@link #refreshTabButtons()}. */
    private Tab renderedTab;
    private ButtonWidget tabGeneralButton;
    private ButtonWidget tabScopesButton;
    private ButtonWidget tabEngineButton;
    private ButtonWidget tabOutgoingButton;
    private ButtonWidget tabHudButton;
    private ButtonWidget llmConfigButton;
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
    private TextFieldWidget blockedKeywords;
    private ButtonWidget enabledButton;
    private ButtonWidget uiStyleButton;
    private ButtonWidget hudIndicatorButton;
    private ButtonWidget hudCornerButton;
    private ButtonWidget hudSizeButton;
    private ButtonWidget hudMarginButton;
    private ButtonWidget hudColorButton;
    private ButtonWidget hudContentButton;
    private ButtonWidget hudVisibilityButton;
    private ButtonWidget chatButton;
    private ButtonWidget otherButton;
    private ButtonWidget vanillaButton;
    private ButtonWidget playerNamesButton;
    private ButtonWidget cacheButton;
    private ButtonWidget providerButton;
    private ButtonWidget displayButton;
    private ButtonWidget downloadButton;
    private ButtonWidget modelButton;
    private ButtonWidget fallbackButton;
    private ButtonWidget diagnosticsButton;
    private ButtonWidget mixedTextButton;
    private ButtonWidget colorButton;
    private ButtonWidget outgoingButton;
    private ButtonWidget targetLanguageButton;
    private ButtonWidget outgoingTargetLanguageButton;
    private String status = "";
    /**
     * Result of the engine tab's connection test.
     *
     * <p>Published by a worker thread as a <em>key</em> plus its one argument, not as a localized
     * string: {@code tr(...)} resolves through the client's language manager, and calling it from a
     * {@code universal-translator-connection-test} thread reads an object owned by the client thread
     * (which may be loading a language file at that moment) and can answer the raw key. The client
     * thread localizes when it draws the line.
     *
     * <p>One immutable line behind one volatile field: with a field per part, the drawing code could
     * read the key of the message the worker had just published together with the argument and the
     * colour of the one before it, so the line shown was a mix of two answers.
     */
    private volatile TestStatus testStatus = TestStatus.EMPTY;

    /** One published engine-tab status line; see {@link #testStatus}. */
    private static final class TestStatus {
        static final TestStatus EMPTY = new TestStatus(null, "", "", false);
        /** Localization key, or {@code null} when {@link #text} is already the whole line. */
        final String key;
        final String argument;
        final String text;
        final boolean error;

        TestStatus(String key, String argument, String text, boolean error) {
            this.key = key;
            this.argument = argument == null ? "" : argument;
            this.text = text == null ? "" : text;
            this.error = error;
        }
    }
    /**
     * In-flight flags for the two engine-tab requests.
     *
     * <p>Both are written by the worker thread that finishes the request and read by the client
     * thread that starts the next one, so a plain {@code boolean} left the reader free to keep
     * seeing {@code true} after the worker had cleared it: the button then answered "already
     * running" forever and only closing the screen recovered it.
     */
    private volatile boolean testing;
    private volatile boolean fetchingModels;
    private volatile List<String> fetchedModels = Collections.emptyList();
    private volatile boolean modelListOpen;
    /**
     * Current page of the model picker.
     *
     * <p>Published by the worker thread together with the list, so it has to be volatile like them:
     * a plain {@code int} let the render thread read the new list with the previous page number and
     * open on a blank page.
     */
    private volatile int modelPage;
    private long animationStartedNanos = System.nanoTime();
    private SettingsSelectionList.Kind openSelection = SettingsSelectionList.Kind.NONE;
    /** Values, labels and geometry of the open selection list; see {@link #selectionGeometry()}. */
    private String[] selectionValues;
    private String[] selectionLabels;
    private SettingsSelectionList.Layout selectionLayout;
    private SettingsSelectionList.Kind selectionCacheKind = SettingsSelectionList.Kind.NONE;
    private int selectionCacheWidth = -1;
    private int selectionCacheHeight = -1;
    /** Page footer of the model picker, rebuilt only when the page count changes. */
    private String modelPageLabel = "";
    private int modelPageLabelPage = -1;
    private int modelPageLabelPages = -1;
    /** Localized runtime status line, refreshed at most every {@link #STATUS_LINE_CACHE_NANOS}. */
    private String runtimeStatusLine = "";
    private boolean runtimeStatusFailure;
    private long runtimeStatusAtNanos;
    /** Static hint lines; they depend on the language only, so one lookup per {@code init()}. */
    private String outgoingHint = "";
    private String keybindHint = "";
    /** Layout snapshot for the current window size; see {@link #layout()}. */
    private Layout cachedLayout;
    private int cachedWindowWidth = -1;
    private int cachedWindowHeight = -1;

    protected UniversalTranslatorSettingsScreen(Screen parent, FabricConfig config, Text title) {
        super(title);
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
        String endpointValue = endpoint == null ? original.endpoint : endpoint.getText();
        String blockedKeywordsValue = blockedKeywords == null
                ? original.blockedKeywords : blockedKeywords.getText();
        if (outgoingTargetLanguage == null) {
            outgoingTargetLanguage = TargetLanguage.canonicalize(original.outgoingTargetLanguage);
        }
        // Both are fixed for the lifetime of the screen and were looked up again on every frame.
        this.outgoingHint = trStr("screen.universal_translator.info.outgoing_hint");
        this.keybindHint = trStr("screen.universal_translator.info.keybind");
        // Fresh widgets need fresh labels, including the tab row.
        this.renderedTab = null;
        Layout layout = layout();
        int left = layout.left;

        // Navigation Tabs (positioned right below header at tabY)
        this.tabGeneralButton = addSettingButton(of(emptyStr(), button -> {
            activeTab = Tab.GENERAL;
            updateTabVisibility();
        }).dimensions(layout.tabX(0), layout.tabY(), layout.tabWidth(), 20).build());

        this.tabScopesButton = addSettingButton(of(emptyStr(), button -> {
            activeTab = Tab.SCOPES;
            updateTabVisibility();
        }).dimensions(layout.tabX(1), layout.tabY(), layout.tabWidth(), 20).build());

        this.tabEngineButton = addSettingButton(of(emptyStr(), button -> {
            activeTab = Tab.ENGINE;
            updateTabVisibility();
        }).dimensions(layout.tabX(2), layout.tabY(), layout.tabWidth(), 20).build());

        this.tabOutgoingButton = addSettingButton(of(emptyStr(), button -> {
            activeTab = Tab.OUTGOING;
            updateTabVisibility();
        }).dimensions(layout.tabX(3), layout.tabY(), layout.tabWidth(), 20).build());

        this.tabHudButton = addSettingButton(of(emptyStr(), button -> {
            activeTab = Tab.HUD;
            updateTabVisibility();
        }).dimensions(layout.tabX(4), layout.tabY(), layout.tabWidth(), 20).build());

        // --- Tab 1：常規 ---
        this.enabledButton = addSettingButton(of(emptyStr(), button -> {
            enabled = !enabled;
            refreshLabels();
        }).dimensions(left, layout.contentRow(0), layout.buttonWidth, 20).build());

        this.targetLanguageButton = addSettingButton(of(emptyStr(), button -> {
            openSelection = SettingsSelectionList.Kind.TARGET_LANGUAGE;
            releaseFieldFocus();
        }).dimensions(layout.right, layout.contentRow(0), layout.buttonWidth, 20).build());

        this.displayButton = addSettingButton(of(emptyStr(), button -> {
            displayMode = displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                    ? TranslationDisplayMode.TRANSLATED_ONLY
                    : TranslationDisplayMode.ORIGINAL_AND_TRANSLATED;
            refreshLabels();
        }).dimensions(left, layout.contentRow(1), layout.buttonWidth, 20).build());

        this.colorButton = addSettingButton(of(emptyStr(), button -> {
            translatedTextColor = translatedTextColor.next();
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(1), layout.buttonWidth, 20).build());

        this.cacheButton = addSettingButton(of(emptyStr(), button -> {
            diskCache = !diskCache;
            refreshLabels();
        }).dimensions(left, layout.contentRow(2), layout.buttonWidth, 20).build());

        this.uiStyleButton = addSettingButton(of(emptyStr(), button -> {
            animatedUi = !animatedUi;
            animationStartedNanos = System.nanoTime();
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(2), layout.buttonWidth, 20).build());

        this.diagnosticsButton = addSettingButton(of(
                trStr("screen.universal_translator.diagnostics.title"), button -> {
            if (minecraftClient() != null) {
                showScreen(new UniversalTranslatorDiagnosticsScreen(this));
            }
        }).dimensions(left, layout.contentRow(3), layout.totalWidth, 20).build());

        // --- Tab 2：範圍 ---
        this.chatButton = addSettingButton(of(emptyStr(), button -> {
            translateChat = !translateChat;
            refreshLabels();
        }).dimensions(left, layout.contentRow(0), layout.buttonWidth, 20).build());

        this.otherButton = addSettingButton(of(emptyStr(), button -> {
            translateOther = !translateOther;
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(0), layout.buttonWidth, 20).build());

        this.vanillaButton = addSettingButton(of(emptyStr(), button -> {
            translateVanilla = !translateVanilla;
            refreshLabels();
        }).dimensions(left, layout.contentRow(1), layout.buttonWidth, 20).build());

        this.playerNamesButton = addSettingButton(of(emptyStr(), button -> {
            translatePlayerNames = !translatePlayerNames;
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(1), layout.buttonWidth, 20).build());

        this.mixedTextButton = addSettingButton(of(emptyStr(), button -> {
            translateEnglishOnly = !translateEnglishOnly;
            refreshLabels();
        }).dimensions(left, layout.contentRow(2), layout.totalWidth, 20).build());

        this.blockedKeywords = addSettingField(newTextField(left, layout.contentRow(3), layout.totalWidth, 20,
                tr("screen.universal_translator.blocked_keywords")));
        this.blockedKeywords.setMaxLength(UiLimits.KEYWORDS_MAX_CHARS);
        this.blockedKeywords.setText(blockedKeywordsValue);
        this.blockedKeywords.setSuggestion(tr("screen.universal_translator.blocked_keywords_hint"));

        // --- Tab 3：引擎 ---
        // The engine rows are centred in the free band (see engineRow) so the tab does not sit in
        // the top third of an otherwise empty panel, and every row is reachable without moving the
        // shared Save/Cancel row that the other tabs and the HUD preview depend on.
        this.providerButton = addSettingButton(of(emptyStr(), button -> {
            openSelection = SettingsSelectionList.Kind.PROVIDER;
            releaseFieldFocus();
        }).dimensions(left, engineRow(0), layout.totalWidth, 20).build());

        this.modelButton = addSettingButton(of(emptyStr(), button -> {
            offlineModel = offlineModel.next();
            refreshLabels();
        }).dimensions(left, engineRow(1), layout.buttonWidth, 20).build());

        this.downloadButton = addSettingButton(of(emptyStr(), button -> {
            offlineAutoDownload = !offlineAutoDownload;
            refreshLabels();
        }).dimensions(layout.right, engineRow(1), layout.buttonWidth, 20).build());

        this.modelPickButton = addSettingButton(of(emptyStr(), button -> {
            if (fetchedModels.isEmpty()) {
                fetchModels();
            } else {
                modelListOpen = true;
                releaseFieldFocus();
            }
        }).dimensions(left, engineRow(1), layout.buttonWidth, 20).build());

        this.fetchModelsButton = addSettingButton(of(
                trStr("screen.universal_translator.llm.fetch_models"), button -> fetchModels())
                .dimensions(layout.right, engineRow(1), layout.buttonWidth, 20).build());

        this.llmConfigButton = addSettingButton(of(
                trStr("screen.universal_translator.option.llm_settings"), button -> {
            if (minecraftClient() != null) {
                showScreen(new UniversalTranslatorLlmConfigScreen(
                        this, llmEndpoint, llmModel, !llmApiKey.isEmpty()));
            }
        }).dimensions(left, engineRow(2), layout.buttonWidth, 20).build());

        this.testConnectionButton = addSettingButton(of(emptyStr(), button -> testConnection())
                .dimensions(layout.right, engineRow(2), layout.buttonWidth, 20).build());

        this.checkSettingsButton = addSettingButton(of(emptyStr(), button -> testConnection())
                .dimensions(left, engineRow(2), layout.totalWidth, 20).build());

        this.fallbackButton = addSettingButton(of(emptyStr(), button -> {
            apiFallback = !apiFallback;
            refreshLabels();
        }).dimensions(left, engineRow(2), layout.totalWidth, 20).build());

        this.endpoint = addSettingField(newTextField(left, engineRow(1), layout.totalWidth, 20,
                tr("screen.universal_translator.endpoint")));
        this.endpoint.setMaxLength(UiLimits.ENDPOINT_MAX_CHARS);
        this.endpoint.setText(endpointValue);

        // Quality and the custom system prompt are engine settings, so they belong on this tab
        // rather than on the diagnostics screen. They take the two rows under the existing three,
        // which leaves every other engine control exactly where it was; the prompt shares its row
        // with its own Apply button.
        this.qualityButton = addSettingButton(of(emptyStr(), button -> {
            quality = quality.next();
            refreshQualityLabel();
        }).dimensions(left, engineRow(3), layout.totalWidth, 20).build());
        this.workersButton = addSettingButton(of(emptyStr(), button -> {
            workers = cycleChoice(workers, 1, 2, 4, 6, 8, 12, 16);
            refreshWorkersLabel();
        }).dimensions(left, engineRow(5), layout.buttonWidth, 20).build());

        this.batchSizeButton = addSettingButton(of(emptyStr(), button -> {
            batchSize = cycleChoice(batchSize, 1, 2, 4, 8, 12, 16);
            refreshBatchLabel();
        }).dimensions(layout.right, engineRow(5), layout.buttonWidth, 20).build());

        String promptValue = customPrompt == null
                ? TranslationPrompt.toEditorText(
                        TranslationPrompt.clampForEditor(original.customSystemPrompt()))
                : customPrompt.getText();
        int promptApplyWidth = Math.max(48, layout.buttonWidth - 24);
        this.customPrompt = addSettingField(newTextField(left, engineRow(4),
                layout.totalWidth - promptApplyWidth - 8, 20,
                tr("screen.universal_translator.diagnostics.prompt")));
        this.customPrompt.setMaxLength(UiLimits.PROMPT_EDITOR_MAX_CHARS);
        this.customPrompt.setText(promptValue);
        this.customPrompt.setSuggestion(tr("screen.universal_translator.diagnostics.prompt_hint"));
        this.applyPromptButton = addSettingButton(of(
                trStr("screen.universal_translator.diagnostics.prompt_apply"),
                button -> applyPromptSettings())
                .dimensions(layout.right + layout.buttonWidth - promptApplyWidth, engineRow(4),
                        promptApplyWidth, 20).build());

        // --- Tab 4：傳送 ---
        this.outgoingButton = addSettingButton(of(emptyStr(), button -> {
            translateOutgoing = !translateOutgoing;
            refreshLabels();
        }).dimensions(left, layout.contentRow(0), layout.totalWidth, 20).build());

        this.outgoingTargetLanguageButton = addSettingButton(of(emptyStr(), button -> {
            openSelection = SettingsSelectionList.Kind.OUTGOING_LANGUAGE;
            releaseFieldFocus();
        }).dimensions(left, layout.contentRow(1), layout.totalWidth, 20).build());

        // --- Tab 5：抬頭顯示 ---
        this.hudIndicatorButton = addSettingButton(of(emptyStr(), button -> {
            hudIndicator = !hudIndicator;
            refreshLabels();
        }).dimensions(left, layout.contentRow(0), layout.buttonWidth, 20).build());

        this.hudCornerButton = addSettingButton(of(emptyStr(), button -> {
            hudIndicatorCorner = HudIndicatorCorner.values()[
                    (hudIndicatorCorner.ordinal() + 1) % HudIndicatorCorner.values().length];
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(0), layout.buttonWidth, 20).build());

        this.hudSizeButton = addSettingButton(of(emptyStr(), button -> {
            hudIndicatorSize = hudIndicatorSize >= HudIndicatorSettings.MAX_SIZE
                    ? HudIndicatorSettings.MIN_SIZE : hudIndicatorSize + 1;
            refreshLabels();
        }).dimensions(left, layout.contentRow(1), layout.buttonWidth, 20).build());

        this.hudMarginButton = addSettingButton(of(emptyStr(), button -> {
            hudIndicatorMargin = hudIndicatorMargin >= HudIndicatorSettings.MAX_MARGIN
                    ? HudIndicatorSettings.MIN_MARGIN : hudIndicatorMargin + 1;
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(1), layout.buttonWidth, 20).build());

        this.hudColorButton = addSettingButton(of(emptyStr(), button -> {
            hudIndicatorColor = HudIndicatorColor.values()[
                    (hudIndicatorColor.ordinal() + 1) % HudIndicatorColor.values().length];
            refreshLabels();
        }).dimensions(left, layout.contentRow(2), layout.buttonWidth, 20).build());

        this.hudContentButton = addSettingButton(of(emptyStr(), button -> {
            hudIndicatorContent = HudIndicatorContent.values()[
                    (hudIndicatorContent.ordinal() + 1) % HudIndicatorContent.values().length];
            refreshLabels();
        }).dimensions(layout.right, layout.contentRow(2), layout.buttonWidth, 20).build());

        this.hudVisibilityButton = addSettingButton(of(emptyStr(), button -> {
            hudIndicatorVisibility = HudIndicatorVisibility.values()[
                    (hudIndicatorVisibility.ordinal() + 1) % HudIndicatorVisibility.values().length];
            refreshLabels();
        }).dimensions(left, layout.contentRow(3), layout.buttonWidth, 20).build());

        // --- Bottom Action Row ---
        addSettingButton(of(trStr("screen.universal_translator.save"), button -> saveAndApply())
                .dimensions(left, layout.saveY, layout.buttonWidth, 20).build());
        addSettingButton(of(trStr("gui.cancel"), button -> onBack())
                .dimensions(layout.right, layout.saveY, layout.buttonWidth, 20).build());

        refreshLabels();
        updateTabVisibility();
        // A resize or a screen rebuild mid-drag must not leave the handle stuck to the pointer.
        hudDragging = false;
    }

    private void refreshLabels() {
        setLabel(uiStyleButton, trStr("screen.universal_translator.option.ui_style",
                tr(animatedUi ? "value.universal_translator.ui_animated"
                        : "value.universal_translator.ui_classic")));
        setLabel(hudIndicatorButton, trStr(
                "screen.universal_translator.option.hud_indicator", onOff(hudIndicator)));
        setLabel(hudCornerButton, trStr(
                "screen.universal_translator.option.hud_corner", hudCornerLabel(hudIndicatorCorner)));
        setLabel(hudSizeButton, trStr(
                "screen.universal_translator.option.hud_size", hudIndicatorSize));
        setLabel(hudMarginButton, trStr(
                "screen.universal_translator.option.hud_margin", hudIndicatorMargin));
        setLabel(hudColorButton, trStr(
                "screen.universal_translator.option.hud_color", hudColorLabel(hudIndicatorColor)));
        setLabel(hudContentButton, trStr(
                "screen.universal_translator.option.hud_content", hudContentLabel(hudIndicatorContent)));
        setLabel(hudVisibilityButton, trStr(
                "screen.universal_translator.option.hud_visibility", hudVisibilityLabel(hudIndicatorVisibility)));
        hudCornerButton.active = hudIndicator;
        hudSizeButton.active = hudIndicator;
        hudMarginButton.active = hudIndicator;
        hudColorButton.active = hudIndicator;
        hudContentButton.active = hudIndicator;
        hudVisibilityButton.active = hudIndicator;
        setLabel(enabledButton, trStr("screen.universal_translator.option.automatic", onOff(enabled)));
        setLabel(chatButton, trStr("screen.universal_translator.option.chat", onOff(translateChat)));
        setLabel(otherButton, trStr("screen.universal_translator.option.other", onOff(translateOther)));
        setLabel(vanillaButton, trStr("screen.universal_translator.option.vanilla", onOff(translateVanilla)));
        setLabel(playerNamesButton, trStr(
                "screen.universal_translator.option.player_names", onOff(translatePlayerNames)));
        setLabel(cacheButton, trStr("screen.universal_translator.option.cache", onOff(diskCache)));
        setLabel(providerButton, trStr("screen.universal_translator.option.provider", providerLabel()));
        String modelValue = llmModel == null || llmModel.trim().isEmpty()
                ? tr("screen.universal_translator.engine.key_unset") : llmModel.trim();
        setLabel(modelPickButton, trStr("screen.universal_translator.engine.model", modelValue));
        setLabel(testConnectionButton, trStr("screen.universal_translator.engine.test"));
        setLabel(checkSettingsButton, trStr(LIBRETRANSLATE_PROVIDER.equalsIgnoreCase(provider)
                ? "screen.universal_translator.engine.test"
                : "screen.universal_translator.engine.check"));
        setLabel(displayButton, trStr("screen.universal_translator.option.display",
                tr(displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                        ? "value.universal_translator.display_bilingual"
                        : "value.universal_translator.display_translated")));
        setLabel(mixedTextButton, trStr("screen.universal_translator.option.mixed", onOff(translateEnglishOnly)));
        setLabel(colorButton, trStr("screen.universal_translator.option.color", colorLabel(translatedTextColor)));
        setLabel(downloadButton, trStr("screen.universal_translator.option.download", onOff(offlineAutoDownload)));
        setLabel(modelButton, trStr("screen.universal_translator.option.model", offlineModel.displayName()));
        setLabel(fallbackButton, trStr("screen.universal_translator.option.fallback", onOff(apiFallback)));
        refreshQualityLabel();
        refreshWorkersLabel();
        refreshBatchLabel();
        setLabel(outgoingButton, trStr("screen.universal_translator.option.outgoing", onOff(translateOutgoing)));
        setLabel(targetLanguageButton, trStr("screen.universal_translator.option.target_preset",
                TargetLanguage.displayName(targetLanguage)));
        setLabel(outgoingTargetLanguageButton, trStr(
                "screen.universal_translator.option.outgoing_target",
                TargetLanguage.displayName(outgoingTargetLanguage)));
        outgoingTargetLanguageButton.active = translateOutgoing;
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
        setLabel(qualityButton, trStr(
                "screen.universal_translator.option.quality",
                tr("value.universal_translator.quality." + quality.configName())));
    }

    private void refreshWorkersLabel() {
        if (workersButton == null) {
            return;
        }
        setLabel(workersButton, trStr(
                "screen.universal_translator.option.workers", Integer.toString(workers)));
    }

    private void refreshBatchLabel() {
        if (batchSizeButton == null) {
            return;
        }
        setLabel(batchSizeButton, trStr(
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
     * Drops field focus while an overlay owns the keyboard.
     *
     * <p>{@code Screen.charTyped} only reaches the widget that holds focus, so with the model picker
     * or a selection list open — both drawn over the whole form — the player's keystrokes were still
     * delivered to the endpoint box, the prompt or the keyword field behind the dimmed overlay, and
     * the save then wrote what had been typed into a field nobody could see.
     */
    private void releaseFieldFocus() {
        syncFieldEditing();
    }

    /**
     * Keeps the three fields from accepting characters while an overlay owns the keyboard, and hands
     * them back the moment it closes.
     *
     * <p>Clearing a field's own focus needs an accessor whose visibility and name both move between
     * generations ({@code setTextFieldFocused} up to 1.20, {@code setFocused} from 1.21) and the game
     * rewrites that name at runtime, so resolving it by reflection found nothing and the guard was a
     * no-op in every published client. The editability switch exists on every generation, so the
     * characters are stopped at the field itself instead.
     *
     * <p>Re-applied from {@link #renderChrome} every frame rather than only when an overlay opens:
     * that is what unfreezes the fields for the paths that dismiss an overlay without going through
     * {@link #releaseFieldFocus()} (ESC, a click outside the list, a resize rebuilding the widgets).
     */
    private void syncFieldEditing() {
        boolean overlayOwnsKeyboard = openSelection != SettingsSelectionList.Kind.NONE
                || (activeTab == Tab.ENGINE && modelListOpen);
        setFieldEditing(endpoint, !overlayOwnsKeyboard);
        setFieldEditing(customPrompt, !overlayOwnsKeyboard);
        setFieldEditing(blockedKeywords, !overlayOwnsKeyboard);
    }

    private static void setFieldEditing(TextFieldWidget field, boolean editable) {
        if (field != null) {
            field.setEditable(editable);
        }
    }

    /**
     * Values, labels and geometry of the open selection list.
     *
     * <p>Rebuilt only when the open list or the window size changes. While a list was open the
     * screen copied a fresh {@code String[]}, built a {@code Layout} and looked up every row's
     * localized name on every frame — twenty rows at sixty frames is twelve hundred language lookups
     * per second for text that cannot change while it is displayed.
     */
    private void refreshSelectionCache() {
        if (selectionValues != null && selectionCacheKind == openSelection
                && selectionCacheWidth == width && selectionCacheHeight == height) {
            return;
        }
        String[] values = SettingsSelectionList.values(openSelection);
        String[] labels = new String[values.length];
        for (int index = 0; index < values.length; index++) {
            labels[index] = SettingsSelectionList.displayName(openSelection, values[index], key -> tr(key));
        }
        selectionValues = values;
        selectionLabels = labels;
        selectionLayout = SettingsSelectionList.layout(width, height, values.length);
        selectionCacheKind = openSelection;
        selectionCacheWidth = width;
        selectionCacheHeight = height;
    }

    /** The engine tab's status line, localized on the client thread. */
    private String testStatusLine() {
        TestStatus line = testStatus;
        if (line.key == null) {
            return line.text;
        }
        return line.argument.isEmpty() ? tr(line.key) : tr(line.key, line.argument);
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
        // Same base and same provider as Save. Building this from the snapshot the screen was opened
        // with pushed the running provider back to the old one while the form still showed the new
        // one, so "apply the prompt" silently switched the service the player had just selected.
        FabricConfig base = writeBackBase();
        try {
            String prompt = customPrompt == null
                    ? base.customSystemPrompt()
                    : TranslationPrompt.clampEditorText(customPrompt.getText());
            FabricConfig updated = base.withProvider(provider).withPromptSettings(quality, prompt);
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            FabricTranslationRuntime.initialize(updated);
            // Same writer as the F8 toggles: two threads writing this file let an older queued
            // snapshot land after the newer one and silently revert what the player just saved.
            FabricTranslationRuntimeBase.saveInBackgroundAndWait(updated);
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
        // Only when the active tab really changed. Every setting button calls refreshLabels(), which
        // ends here, and init() also called this right after updateTabVisibility() — so one click on
        // a toggle rebuilt the five tab labels plus the forty-three settings labels.
        if (renderedTab == activeTab) {
            return;
        }
        renderedTab = activeTab;
        setLabel(tabGeneralButton, tabTitle("screen.universal_translator.tab.general", activeTab == Tab.GENERAL));
        setLabel(tabScopesButton, tabTitle("screen.universal_translator.tab.scopes", activeTab == Tab.SCOPES));
        setLabel(tabEngineButton, tabTitle("screen.universal_translator.tab.engine", activeTab == Tab.ENGINE));
        setLabel(tabOutgoingButton, tabTitle("screen.universal_translator.tab.outgoing", activeTab == Tab.OUTGOING));
        setLabel(tabHudButton, tabTitle("screen.universal_translator.tab.hud", activeTab == Tab.HUD));
    }

    private String tabTitle(String key, boolean active) {
        String label = tr(key);
        return lit(active ? "§b§l[ " + label + " ]" : "§7" + label);
    }

    private String onOff(boolean value) {
        return tr(UiTextKeys.onOff(value));
    }

    private static boolean isFailureStatus(String value) {
        return TranslationStatusLocalizer.isFailure(value);
    }

    private void saveAndApply() {
        boolean runtimeChanged = false;
        // Built on the running configuration, not on the snapshot this screen was opened with: F8
        // pressed while the screen is open replaces it, and writing back the older snapshot silently
        // undid that toggle (and the debug switch, provider and target language with it).
        FabricConfig base = writeBackBase();
        try {
            // One snapshot for the whole form: the general settings, the engine tab's prompt
            // controls (Save writes those too, so applying the prompt and then saving cannot
            // silently restore the value the screen was opened with) and the throughput tuning.
            // Built through one method so the file is read once instead of once per group — this
            // runs straight from a button callback, on the client thread.
            FabricConfig updated = base.withAllSettings(
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
                    new HudIndicatorSettings(hudIndicator, hudIndicatorCorner,
                            hudIndicatorSize, hudIndicatorMargin, hudIndicatorColor,
                            hudIndicatorContent, hudIndicatorVisibility,
                            hudIndicatorOffsetX, hudIndicatorOffsetY),
                    quality,
                    TranslationPrompt.fromEditorText(
                            customPrompt == null ? "" : customPrompt.getText()),
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
            FabricTranslationRuntimeBase.saveInBackgroundAndWait(updated);
            status = tr("status.universal_translator.saved");
            // Drawn once before the screen goes away; closing in this callback meant the success
            // line never appeared anywhere, so Save gave no feedback unless it failed.
            closeAfterSave();
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
     * The configuration a save builds on: the one the runtime is running.
     *
     * <p>Falls back to the snapshot the screen was opened with only before the first
     * {@code initialize}, when there is nothing to build on.
     */
    private FabricConfig writeBackBase() {
        FabricConfig live = FabricTranslationRuntime.diagnosticsConfig();
        return live == null ? original : live;
    }

    /** Leaves the screen after the success line has had one frame to be drawn. */
    private void closeAfterSave() {
        MinecraftClient client = minecraftClient();
        if (client == null) {
            onBack();
            return;
        }
        client.execute(this::onBack);
    }

    protected final void renderChrome(Object canvas, int mouseX, int mouseY, float delta) {
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
            fillRect(canvas, 0, 0, this.width, this.height, 0x76070B10);
            fillRect(canvas, panelLeft - 2, 2, panelRight + 2, panelBottom + 2, 0x70101820);
            fillRect(canvas, panelLeft, 4, panelRight, panelBottom, 0xD41A232E);
            fillRect(canvas, panelLeft, 4, panelRight, 5, 0xCC55D6FF);
            fillRect(canvas, panelLeft, 32, panelRight, 33, 0x6655D6FF);
            int sweep = SettingsUiAnimation.sweepX(panelLeft, Math.max(panelLeft, panelRight - 26), now);
            fillRect(canvas, sweep, 32, Math.min(panelRight, sweep + 26), 34,
                    SettingsUiAnimation.pulseColor(now));

            // Glowing underline for active tab
            int tabIndex = activeTab.ordinal();
            int tabX = layout.tabX(tabIndex);
            fillRect(canvas, tabX, layout.tabY() + 20, tabX + layout.tabWidth(), layout.tabY() + 22, 0xFF55D6FF);
        }
        drawCentered(canvas, textRenderer(), this.getTitle().getString(), this.width / 2, 16,
                animatedUi ? SettingsUiAnimation.pulseColor(now) : 0xFFFFFFFF);
        refreshRuntimeStatusLine(now);
        String runtimeStatus = runtimeStatusLine;
        int belowSave = layout.saveY + 24;
        int messageY = belowSave <= this.height - 10 ? belowSave : SettingsScreenLayout.COMPACT_STATUS_Y;
        String testLine = testStatusLine();
        if (!status.isEmpty()) {
            drawCentered(canvas, textRenderer(), lit(status),
                    this.width / 2, messageY, 0xFFFF5555);
        } else if (!testLine.isEmpty()) {
            drawCentered(canvas, textRenderer(), lit(testLine),
                    this.width / 2, messageY, testStatus.error ? 0xFFFF5555 : 0xFF55FF55);
        } else if (!runtimeStatus.isEmpty()) {
            drawCentered(canvas, textRenderer(), lit(runtimeStatus),
                    this.width / 2, messageY,
                    runtimeStatusFailure ? 0xFFFF5555 : 0xFF55FF55);
        } else {
            if (activeTab == Tab.OUTGOING) {
                drawCentered(canvas, textRenderer(), outgoingHint,
                        this.width / 2, layout.contentRow(2) + 6, 0xFFA0A0A0);
            } else if (activeTab == Tab.GENERAL) {
                drawCentered(canvas, textRenderer(), keybindHint,
                        this.width / 2, layout.contentRow(3) + 24, 0xFFA0A0A0);
            }
        }
        if (activeTab == Tab.ENGINE) {
            renderEngineInfo(canvas);
        }
    }

    /**
     * Recomputes the localized runtime status at most every {@link #STATUS_LINE_CACHE_NANOS}.
     *
     * <p>Both halves were per-frame work: {@code status()} asks the provider, and the localization
     * walks a table of twenty Chinese phrases plus a language lookup. The line only changes when a
     * translation starts failing, so a fifth of a second of staleness costs nothing.
     */
    private void refreshRuntimeStatusLine(long nowNanos) {
        if (runtimeStatusAtNanos != 0L && nowNanos - runtimeStatusAtNanos < STATUS_LINE_CACHE_NANOS) {
            return;
        }
        String raw = FabricTranslationRuntime.status();
        if (raw == null || raw.isEmpty()) {
            runtimeStatusLine = "";
            runtimeStatusFailure = false;
        } else {
            runtimeStatusLine = TranslationStatusLocalizer.localize(raw,
                    (key, arguments) -> tr(key, arguments));
            runtimeStatusFailure = isFailureStatus(raw);
        }
        runtimeStatusAtNanos = nowNanos;
    }

    protected final void renderOverlay(Object canvas, int mouseX, int mouseY, float delta) {
        float opening = animatedUi
                ? SettingsUiAnimation.openProgress(animationStartedNanos, System.nanoTime())
                : 1.0F;
        if (animatedUi) {
            int overlayAlpha = SettingsUiAnimation.openingOverlayAlpha(opening);
            if (overlayAlpha > 0) {
                fillRect(canvas, 0, 0, this.width, this.height, overlayAlpha << 24);
            }
        }
        if (activeTab == Tab.HUD) {
            drawHudDragPreview(canvas);
        }
        if (openSelection != SettingsSelectionList.Kind.NONE) {
            renderSelection(canvas, mouseX, mouseY);
        }
        if (activeTab == Tab.ENGINE && modelListOpen) {
            renderModelList(canvas, mouseX, mouseY);
        }
    }

    protected boolean handleSelectionClick(double mouseX, double mouseY) {
        // The catalog overlay owns the click first: the shared base calls this after the HUD drag
        // seam, which refuses on the engine tab, so the picker still gets first claim.
        if (activeTab == Tab.ENGINE && modelListOpen) {
            handleModelListClick(mouseX, mouseY);
            return true;
        }
        return openSelection != SettingsSelectionList.Kind.NONE
                && selectFromList(mouseX, mouseY);
    }

    protected boolean handleDragStart(double mouseX, double mouseY) {
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
     * <p>Clamping the offset instead of the drawn position is what removes the dead zone: while
     * only the drawing was clamped the offset kept growing, so the handle stopped following the
     * pointer and the user had to drag back through invisible distance before it moved again.
     * One offset unit is one pixel of the 320x240 virtual screen.
     */
    private int clampOffsetToPreview(int value, int anchor, int extent) {
        return HudPreviewGeometry.clampOffset(value, anchor, extent, hudIndicatorSize);
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

    /** Panel height; 0 means there is no room above Save and the preview is skipped. */
    private int hudPreviewH() {
        return HudPreviewGeometry.height(hudPreviewY(), layout().saveY);
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

    private double hudScale() {
        return HudPreviewGeometry.scale(hudPreviewW(), hudPreviewH());
    }

    private int hudHandleX() {
        return HudPreviewGeometry.handleX(hudPreviewX(), hudPreviewW(),
                HudPreviewGeometry.contentX(hudPreviewX(), hudPreviewW(), hudScale()),
                hudAnchorX(), hudIndicatorOffsetX, hudScale());
    }

    private int hudHandleY() {
        return HudPreviewGeometry.handleY(hudPreviewY(), hudPreviewH(),
                HudPreviewGeometry.contentY(hudPreviewY(), hudPreviewH(), hudScale()),
                hudAnchorY(), hudIndicatorOffsetY, hudScale());
    }

    private boolean insideHudDragHandle(double mouseX, double mouseY) {
        int previewY = hudPreviewY();
        int previewHeight = HudPreviewGeometry.height(previewY, layout().saveY);
        if (previewHeight <= 0) {
            return false;
        }
        int previewX = hudPreviewX();
        int previewWidth = hudPreviewW();
        double scale = HudPreviewGeometry.scale(previewWidth, previewHeight);
        int contentX = HudPreviewGeometry.contentX(previewX, previewWidth, scale);
        int contentY = HudPreviewGeometry.contentY(previewY, previewHeight, scale);
        return HudPreviewGeometry.insideHandle(mouseX, mouseY,
                HudPreviewGeometry.handleX(previewX, previewWidth, contentX,
                        hudAnchorX(), hudIndicatorOffsetX, scale),
                HudPreviewGeometry.handleY(previewY, previewHeight, contentY,
                        hudAnchorY(), hudIndicatorOffsetY, scale));
    }

    private void drawHudDragPreview(Object canvas) {
        // One layout read and one geometry pass for the whole panel: every helper below used to
        // rebuild the layout object, so drawing an eight-pixel handle cost about twenty of them.
        Layout layout = layout();
        int previewX = layout.left;
        int previewY = layout.contentRow(4);
        int previewWidth = layout.totalWidth;
        int previewHeight = HudPreviewGeometry.height(previewY, layout.saveY);
        if (previewHeight <= 0) {
            return;
        }
        double scale = HudPreviewGeometry.scale(previewWidth, previewHeight);
        fillRect(canvas, previewX, previewY, previewX + previewWidth, previewY + previewHeight,
                0x40000000);
        // A corner marker on the anchor edge the indicator hugs, so the four corner options are
        // visible in the preview instead of only in the button label.
        int handleX = HudPreviewGeometry.handleX(previewX, previewWidth,
                HudPreviewGeometry.contentX(previewX, previewWidth, scale),
                hudAnchorX(), hudIndicatorOffsetX, scale);
        int handleY = HudPreviewGeometry.handleY(previewY, previewHeight,
                HudPreviewGeometry.contentY(previewY, previewHeight, scale),
                hudAnchorY(), hudIndicatorOffsetY, scale);
        fillRect(canvas, handleX, handleY,
                handleX + HudPreviewGeometry.HANDLE_SIZE, handleY + HudPreviewGeometry.HANDLE_SIZE,
                hudDragging ? 0xFFFFAA00 : 0xFF00AA00);
    }

    private void renderSelection(Object canvas, int mouseX, int mouseY) {
        refreshSelectionCache();
        String[] values = selectionValues;
        SettingsSelectionList.Layout list = selectionLayout;
        String selectedValue = selectionValue();
        fillRect(canvas, 0, 0, width, height, 0xB0080B10);
        fillRect(canvas, list.panelLeft() - 1, list.panelTop - 1,
                list.panelRight() + 1, list.panelBottom + 1, 0xFF55D6FF);
        fillRect(canvas, list.panelLeft(), list.panelTop,
                list.panelRight(), list.panelBottom, 0xF018202A);
        drawCentered(canvas, textRenderer(),
                trStr(selectionTitleKey()), width / 2, list.panelTop + 9, 0xFFFFFFFF);
        for (int index = 0; index < values.length; index++) {
            int x = list.x(index);
            int y = list.y(index);
            boolean hovered = mouseX >= x && mouseX < x + list.buttonWidth
                    && mouseY >= y && mouseY < y + list.buttonHeight;
            boolean selected = values[index].equalsIgnoreCase(selectedValue);
            fillRect(canvas, x, y, x + list.buttonWidth, y + list.buttonHeight,
                    hovered ? 0xFF3B6178 : selected ? 0xFF28533D : 0xFF303844);
            drawCentered(canvas, textRenderer(),
                    lit(selectionLabels[index]),
                    x + list.buttonWidth / 2, y + Math.max(1, (list.buttonHeight - 8) / 2),
                    selected ? 0xFF55FF88 : 0xFFFFFFFF);
        }
    }

    private boolean selectFromList(double mouseX, double mouseY) {
        refreshSelectionCache();
        String[] values = selectionValues;
        SettingsSelectionList.Layout list = selectionLayout;
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
            // The overlay is modal: the click that dismisses it is consumed here. Returning false
            // reported the press as unhandled, so it fell through to the buttons underneath the
            // dimmed area. On the general tab at a 320x240 logical resolution the language list
            // stops at y≈154 while Save and Cancel sit at y=216..236, so "click the empty space to
            // close the list" also pressed Save (keeping everything) or Cancel (throwing away every
            // unsaved change without asking).
            openSelection = SettingsSelectionList.Kind.NONE;
            return true;
        }
        return true;
    }

    /**
     * Closes the open overlay on ESC instead of letting the key reach the screen.
     *
     * <p>Called from the version-pinned {@code keyPressed} bridge of the generations whose key
     * callback this repository already drives. The shared class cannot declare that override
     * itself: the keyboard callback changed shape in Minecraft 1.21.9, and this class is compiled
     * for every generation from 1.14 to 26.x.
     *
     * @param keyCode the platform key code
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
     * <p>This class is shared by every Fabric generation from 1.14 to 26.x, and the keyboard
     * callback signature differs across them, so the authoritative hook is the one that only
     * decides whether ESC may close: an overlay that is open pins it to "no". That alone stops
     * ESC from discarding a whole session of unsaved changes. The generations whose key callback
     * this repository already calls ({@code keyPressed(int, int, int)}) additionally close the
     * overlay itself before falling through here.
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
        // ...and never past the row the Save button lives on either. The sixth engine row sits at a
        // floor, because contentRowStep has a minimum, so at a 320x200 window it landed on top of
        // Save: pressing Save hit the workers button (registered later, dispatched first) and the
        // settings were never written.
        return Math.max(SettingsScreenLayout.HEADER_BOTTOM + 2,
                Math.min(centred, layout.saveY - 6 - blockHeight));
    }

    private int engineRow(int index) {
        return engineBlockTop() + layout().contentRowStep * index;
    }

    /** Read-only engine facts plus the always-visible privacy hint. */
    private void renderEngineInfo(Object canvas) {
        Layout layout = layout();
        int y = engineRow(5) + 24;
        // Nothing left between the last engine row and Save: the summary and the privacy hint were
        // painted over the Save button on a short window, which also put them under its clicks.
        if (y + 18 > layout.saveY - 2) {
            return;
        }
        if (isLlm()) {
            drawCentered(canvas, textRenderer(), trStr(
                            "screen.universal_translator.engine.summary",
                            trimForDisplay(llmEndpoint),
                            tr(llmApiKey == null || llmApiKey.isEmpty()
                                    ? "screen.universal_translator.engine.key_unset"
                                    : "screen.universal_translator.engine.key_set")),
                    this.width / 2, y, 0xFFA0A0A0);
        } else if (!isOffline() && !LIBRETRANSLATE_PROVIDER.equalsIgnoreCase(provider)) {
            drawCentered(canvas, textRenderer(),
                    trStr("screen.universal_translator.engine.config_file"),
                    this.width / 2, y, 0xFFA0A0A0);
        }
        drawCentered(canvas, textRenderer(),
                trStr(isOffline()
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
        // Snapshot the provider the answer belongs to. Switching providers while the request is in
        // flight used to publish the previous service's catalogue — and force the picker open — so
        // the player could pick a model name the new provider has never heard of, and the save then
        // wrote it. The worker now drops its answer unless the screen still shows that provider.
        final String startedProvider = provider;
        fetchingModels = true;
        releaseFieldFocus();
        setTestStatus(tr("screen.universal_translator.llm.fetching"), false);
        SCREEN_WORKERS.execute(new Runnable() {
            @Override
            public void run() {
                OpenAiModelCatalog.Catalog catalog = null;
                String failure = "";
                try {
                    catalog = OpenAiModelCatalog.fetchCatalog(startedProvider, endpointValue, keyValue);
                } catch (Exception error) {
                    failure = describe(error);
                } finally {
                    // Released in a finally: a LinkageError or an OutOfMemoryError is not an
                    // Exception, and leaving the flag set disabled both engine buttons for the rest
                    // of the screen's life with no message and no way to retry.
                    fetchingModels = false;
                }
                if (!startedProvider.equals(provider)) {
                    // A different provider is selected now; its own fetch (or the player) owns the
                    // state.
                    return;
                }
                if (!failure.isEmpty()) {
                    setTestResult("screen.universal_translator.llm.fetch_failed", failure, true);
                    return;
                }
                if (catalog == null || !catalog.jsonBody()) {
                    // fetchCatalog answers null for a body it cannot parse instead of throwing, and
                    // the worker then dereferenced it on a thread with no handler: the player got
                    // nothing at all.
                    setTestResult("screen.universal_translator.engine.test_not_json", "", true);
                    return;
                }
                if (catalog.models().isEmpty()) {
                    setTestResult("screen.universal_translator.llm.fetch_empty", "", true);
                    return;
                }
                fetchedModels = catalog.models();
                modelPage = 0;
                modelListOpen = true;
                setTestStatus("", false);
            }
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
        final String endpointValue = llm ? llmEndpoint : endpoint.getText().trim();
        final String keyValue = llmApiKey;
        // Same snapshot rule as fetchModels: a verdict about the provider the player has already
        // switched away from would describe the wrong service.
        final String startedProvider = provider;
        testing = true;
        setTestStatus(tr("screen.universal_translator.engine.testing"), false);
        SCREEN_WORKERS.execute(new Runnable() {
            @Override
            public void run() {
                // Every branch publishes a key plus its one argument: tr(...) resolves through the
                // client's language manager, which this thread does not own.
                String key = null;
                String argument = "";
                boolean error;
                try {
                    if (llm) {
                        // Probe the endpoint translations actually use. Reading /models can succeed
                        // while every translation fails on a wrong chat path or an unset model.
                        String model = llmModel == null ? "" : llmModel.trim();
                        if (model.isEmpty()) {
                            key = "screen.universal_translator.engine.test_need_model";
                            error = true;
                        } else {
                            ProviderProbe.probe(startedProvider, endpointValue, keyValue, model);
                            key = "screen.universal_translator.engine.test_ok_probe";
                            argument = model;
                            error = false;
                        }
                    } else if (libre) {
                        String url = libreLanguagesUrl(endpointValue);
                        EndpointPolicy.requireSafeEndpoint(url);
                        String body = httpClient.get(URI.create(url),
                                Collections.<String, String>emptyMap());
                        // The languages catalog is JSON; anything else means this is not LibreTranslate.
                        JsonStrings.parse(body);
                        key = "screen.universal_translator.engine.test_ok_libre";
                        error = false;
                    } else {
                        // Validate the provider the screen is showing, not the one this snapshot was
                        // opened with: they differ until the form is saved, and answering for the old
                        // one told users the wrong thing about the service they had just switched to.
                        writeBackBase().withProvider(startedProvider).validateProviderConfiguration();
                        key = "screen.universal_translator.engine.test_ok_config";
                        error = false;
                    }
                } catch (Exception failure) {
                    // Plain text, not a key: the cause is whatever the service answered.
                    argument = describe(failure);
                    error = true;
                } finally {
                    // See fetchModels: released in a finally so an Error cannot latch the flag.
                    testing = false;
                }
                if (!startedProvider.equals(provider)) {
                    return;
                }
                setTestResult(key, argument, error);
            }
        });
    }

    /** Publishes a line that is already text: an exception message, or a client-thread lookup. */
    private void setTestStatus(String message, boolean error) {
        testStatus = new TestStatus(null, "", message, error);
    }

    /**
     * Publishes a status line for the client thread to localize.
     *
     * <p>A {@code null} key means the argument is already the whole line, which is what an exception
     * message is. The whole line is published in one write, so a reader either sees the finished
     * message or the one before it — never a key paired with the argument of another answer.
     */
    private void setTestResult(String key, String argument, boolean error) {
        if (key == null) {
            setTestStatus(argument, error);
            return;
        }
        testStatus = new TestStatus(key, argument, "", error);
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
    private void renderModelList(Object canvas, int mouseX, int mouseY) {
        // One read of the volatile list for the whole frame: a worker thread can replace it at any
        // moment, and reading size() and get() separately threw IndexOutOfBoundsException on the
        // render thread whenever the replacement was shorter than the list it replaced.
        List<String> models = fetchedModels;
        fillRect(canvas, 0, 0, this.width, this.height, 0xFF101010);
        drawCentered(canvas, textRenderer(),
                trStr("screen.universal_translator.llm.select_model", models.size()),
                this.width / 2, 20, 0xFFFFFFFF);
        int rows = modelRowsPerPage();
        modelPage = ModelListPaging.clampPage(modelPage, models.size(), rows);
        int first = modelPage * rows;
        for (int row = 0; row < rows && first + row < models.size(); row++) {
            int rowY = MODEL_LIST_TOP + row * MODEL_ROW_HEIGHT;
            boolean hovered = mouseY >= rowY && mouseY < rowY + MODEL_ROW_HEIGHT;
            String name = models.get(first + row);
            // The cursor is only built for the one hovered row; every other row is drawn as the
            // string it already is, instead of concatenating twenty new strings per frame.
            drawLeft(canvas, textRenderer(), lit(hovered ? "> " + name : name),
                    24, rowY, hovered ? 0xFFFFD060 : 0xFFE0E0E0);
        }
        int pages = modelPageCount(models.size(), rows);
        drawCentered(canvas, textRenderer(),
                lit(modelPageLabel(pages)),
                this.width / 2, this.height - 46, 0xFFFFFFFF);
        drawCentered(canvas, textRenderer(),
                trStr("screen.universal_translator.llm.select_back"),
                this.width / 2, this.height - 30, 0xFFFFD060);
        drawCentered(canvas, textRenderer(),
                trStr("screen.universal_translator.llm.select_hint"),
                this.width / 2, this.height - 16, 0xFFA0A0A0);
    }

    private void handleModelListClick(double mouseX, double mouseY) {
        List<String> models = fetchedModels;
        if (mouseY >= this.height - 54 && mouseY < this.height - 38) {
            int pages = modelPageCount(models.size(), modelRowsPerPage());
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
        int rows = modelRowsPerPage();
        modelPage = ModelListPaging.clampPage(modelPage, models.size(), rows);
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

    private int modelPageCount(int size, int rows) {
        return Math.max(1, (size + rows - 1) / rows);
    }

    /** The picker's page footer, rebuilt only when the page or the page count changes. */
    private String modelPageLabel(int pages) {
        if (modelPageLabelPage != modelPage || modelPageLabelPages != pages) {
            modelPageLabel = "< " + (modelPage + 1) + "/" + pages + " >";
            modelPageLabelPage = modelPage;
            modelPageLabelPages = pages;
        }
        return modelPageLabel;
    }

    /**
     * Leaves this screen. Every generation spells the exit hook differently, so each per-generation
     * base maps its own vanilla method onto this one.
     */
    protected void onBack() {
        showScreen(parent);
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
        this.llmEndpoint = original.editorEndpoint(selectedProvider);
        this.llmApiKey = original.editorApiKey(selectedProvider);
        this.llmModel = original.editorModel(selectedProvider);
    }

    public final void applyLlmSettings(String endpoint, String model, String apiKey) {
        this.llmEndpoint = endpoint;
        this.llmModel = model;
        this.llmApiKey = apiKey;
    }

    public final String llmApiKey() {
        return llmApiKey;
    }

    private String colorLabel(TranslationTextColor color) {
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

    private String hudColorLabel(HudIndicatorColor color) {
        return tr("value.universal_translator.color." + color.configName().replace('-', '_'));
    }

    private String hudContentLabel(HudIndicatorContent content) {
        return tr("value.universal_translator.hud_content." + content.configName().replace('-', '_'));
    }

    private String hudVisibilityLabel(HudIndicatorVisibility visibility) {
        return tr("value.universal_translator.hud_visibility." + visibility.configName().replace('-', '_'));
    }

    private String hudCornerLabel(HudIndicatorCorner corner) {
        switch (corner) {
            case TOP_RIGHT: return tr("value.universal_translator.hud_corner.top_right");
            case BOTTOM_LEFT: return tr("value.universal_translator.hud_corner.bottom_left");
            case BOTTOM_RIGHT: return tr("value.universal_translator.hud_corner.bottom_right");
            case TOP_LEFT:
            default: return tr("value.universal_translator.hud_corner.top_left");
        }
    }

    protected final String tr(String key, Object... arguments) {
        return trStr(key, arguments);
    }

    /**
     * The layout for the current window size, computed once per size.
     *
     * <p>Every {@code hudPreview*}, {@code hudHandle*}, {@code engineRow} and {@code tabX} call used
     * to recompute {@link SettingsScreenLayout#calculate} and build two objects from it, and those
     * call each other — a single HUD frame asked for about twenty of them. The geometry depends on
     * nothing but the window size and the tab count, so the result is a snapshot of that size and
     * a resize (or {@code init} on a new screen) is what invalidates it.
     */
    private Layout layout() {
        int windowWidth = this.width;
        int windowHeight = this.height;
        Layout cached = cachedLayout;
        if (cached != null && cachedWindowWidth == windowWidth && cachedWindowHeight == windowHeight) {
            return cached;
        }
        SettingsScreenLayout.Geometry geometry = SettingsScreenLayout.calculate(windowWidth, windowHeight, 5);
        Layout computed = new Layout(geometry.left(), geometry.right(), geometry.totalWidth(), geometry.buttonWidth(),
                geometry.top(), geometry.rowStep(), geometry.targetY(), geometry.endpointY(), geometry.saveY(),
                geometry.tabY(), geometry.tabWidth(), geometry.tabGap(), geometry.contentTop(), geometry.contentRowStep());
        cachedLayout = computed;
        cachedWindowWidth = windowWidth;
        cachedWindowHeight = windowHeight;
        return computed;
    }

    /**
     * One window size's layout, with the row helpers this screen asks for.
     *
     * <p>Held by {@link #layout()} as a per-size snapshot, so it is built at most once per resize.
     * The accessors are the same names the rest of the class reads as fields; that duplication with
     * {@link SettingsScreenLayout.Geometry} is what {@code S2-077} still has to remove.
     */
    private static final class Layout {
        private final int left;
        private final int right;
        private final int totalWidth;
        private final int buttonWidth;
        private final int top;
        private final int rowStep;
        private final int targetY;
        private final int endpointY;
        private final int saveY;
        private final int tabY;
        private final int tabWidth;
        private final int tabGap;
        private final int contentTop;
        private final int contentRowStep;

        private Layout(int left, int right, int totalWidth, int buttonWidth,
                       int top, int rowStep, int targetY, int endpointY, int saveY,
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
