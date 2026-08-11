package org.universaltranslator.fabric;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.universaltranslator.core.TranslationDisplayMode;
import org.universaltranslator.core.TranslationTextColor;

/** Minimal dependency-free settings screen, opened with U by default. */
final class UniversalTranslatorConfigScreen extends Screen {
    private final Screen parent;
    private final FabricConfig original;
    private boolean enabled;
    private boolean translateChat;
    private boolean translateOther;
    private boolean diskCache;
    private boolean offlineAutoDownload;
    private boolean apiFallback;
    private TranslationDisplayMode displayMode;
    private boolean translateEnglishOnly;
    private TranslationTextColor translatedTextColor;
    private String provider;
    private EditBox targetLanguage;
    private EditBox endpoint;
    private Button enabledButton;
    private Button chatButton;
    private Button otherButton;
    private Button cacheButton;
    private Button providerButton;
    private Button displayButton;
    private Button downloadButton;
    private Button fallbackButton;
    private Button mixedTextButton;
    private Button colorButton;
    private String status = "";

    UniversalTranslatorConfigScreen(Screen parent, FabricConfig config) {
        super(Component.literal("MC 自动翻译工具 设置"));
        this.parent = parent;
        this.original = config;
        this.enabled = config.enabled;
        this.translateChat = config.translateChat;
        this.translateOther = config.translateOther;
        this.diskCache = config.diskCache;
        this.offlineAutoDownload = config.offlineAutoDownload;
        this.apiFallback = config.apiFallback;
        this.displayMode = config.displayMode;
        this.translateEnglishOnly = config.translateEnglishOnly;
        this.translatedTextColor = config.translatedTextColor;
        this.provider = config.provider;
    }

    @Override
    protected void init() {
        Layout layout = layout();
        int left = layout.left;
        this.enabledButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            enabled = !enabled;
            refreshLabels();
        }).bounds(left, layout.row(0), layout.buttonWidth, 20).build());
        this.cacheButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            diskCache = !diskCache;
            refreshLabels();
        }).bounds(layout.right, layout.row(0), layout.buttonWidth, 20).build());
        this.chatButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            translateChat = !translateChat;
            refreshLabels();
        }).bounds(left, layout.row(1), layout.buttonWidth, 20).build());
        this.otherButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            translateOther = !translateOther;
            refreshLabels();
        }).bounds(layout.right, layout.row(1), layout.buttonWidth, 20).build());
        this.providerButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            provider = nextProvider(provider);
            refreshLabels();
        }).bounds(left, layout.row(2), layout.buttonWidth, 20).build());
        this.displayButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            displayMode = displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                    ? TranslationDisplayMode.TRANSLATED_ONLY
                    : TranslationDisplayMode.ORIGINAL_AND_TRANSLATED;
            refreshLabels();
        }).bounds(layout.right, layout.row(2), layout.buttonWidth, 20).build());
        this.mixedTextButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            translateEnglishOnly = !translateEnglishOnly;
            refreshLabels();
        }).bounds(left, layout.row(3), layout.buttonWidth, 20).build());
        this.colorButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            translatedTextColor = translatedTextColor.next();
            refreshLabels();
        }).bounds(layout.right, layout.row(3), layout.buttonWidth, 20).build());
        this.downloadButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            offlineAutoDownload = !offlineAutoDownload;
            refreshLabels();
        }).bounds(left, layout.row(4), layout.buttonWidth, 20).build());
        this.fallbackButton = addRenderableWidget(Button.builder(Component.empty(), button -> {
            apiFallback = !apiFallback;
            refreshLabels();
        }).bounds(layout.right, layout.row(4), layout.buttonWidth, 20).build());

        this.targetLanguage = addRenderableWidget(new EditBox(
                this.font, left, layout.targetY, layout.buttonWidth, 20, Component.literal("目标语言")));
        this.targetLanguage.setMaxLength(32);
        this.targetLanguage.setValue(original.targetLanguage);
        this.endpoint = addRenderableWidget(new EditBox(
                this.font, left, layout.endpointY, layout.totalWidth, 20,
                Component.literal("LibreTranslate 地址")));
        this.endpoint.setMaxLength(512);
        this.endpoint.setValue(original.endpoint);

        addRenderableWidget(Button.builder(Component.literal("保存并应用"), button -> saveAndApply())
                .bounds(left, layout.saveY, layout.buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"), button -> onClose())
                .bounds(layout.right, layout.saveY, layout.buttonWidth, 20).build());
        refreshLabels();
    }

    private void refreshLabels() {
        enabledButton.setMessage(Component.literal("自动翻译: " + onOff(enabled)));
        chatButton.setMessage(Component.literal("聊天内容: " + onOff(translateChat)));
        otherButton.setMessage(Component.literal("其他界面: " + onOff(translateOther)));
        cacheButton.setMessage(Component.literal("本地缓存: " + onOff(diskCache)));
        providerButton.setMessage(Component.literal("服务: " + providerLabel()));
        displayButton.setMessage(Component.literal("显示: "
                + (displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED ? "原文+译文" : "仅译文")));
        mixedTextButton.setMessage(Component.literal("混合文本仅译英文: " + onOff(translateEnglishOnly)));
        colorButton.setMessage(Component.literal("译文颜色: " + colorLabel(translatedTextColor)));
        downloadButton.setMessage(Component.literal("模型下载: " + onOff(offlineAutoDownload)));
        fallbackButton.setMessage(Component.literal("API 回退: " + onOff(apiFallback)));
        downloadButton.active = isOffline();
        fallbackButton.active = isOffline();
    }

    private static String onOff(boolean value) {
        return value ? "开启" : "关闭";
    }

    private static boolean isFailureStatus(String value) {
        return value.startsWith("翻译失败") || value.startsWith("离线翻译失败")
                || value.contains("均失败");
    }

    private void saveAndApply() {
        boolean runtimeChanged = false;
        try {
            if (targetLanguage.getValue().trim().isEmpty()) {
                throw new IllegalArgumentException("目标语言不能为空");
            }
            FabricConfig updated = original.withSettings(
                    enabled,
                    translateChat,
                    translateOther,
                    targetLanguage.getValue(),
                    displayMode,
                    translateEnglishOnly,
                    translatedTextColor,
                    provider,
                    endpoint.getValue(),
                    offlineAutoDownload,
                    apiFallback,
                    diskCache);
            if (updated.enabled && "tencent-hunyuan".equalsIgnoreCase(updated.provider)
                    && (updated.tencentSecretId.isEmpty() || updated.tencentSecretKey.isEmpty())) {
                throw new IllegalArgumentException("请先在本地配置文件填写腾讯 SecretId 和 SecretKey");
            }
            if (updated.enabled) {
                updated.validateProviderConfiguration();
            }
            runtimeChanged = true;
            FabricTranslationRuntime.initialize(updated);
            updated.save();
            status = "设置已保存";
            onClose();
        } catch (Exception exception) {
            if (runtimeChanged) {
                try {
                    FabricTranslationRuntime.initialize(original);
                } catch (Exception restoreFailure) {
                    exception.addSuppressed(restoreFailure);
                }
            }
            status = "无法保存: " + exception.getMessage();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        graphics.centeredText(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        Layout layout = layout();
        int left = layout.left;
        graphics.text(this.font, Component.literal("目标语言 (例如 zh-CN)"),
                left, layout.targetY - 11, 0xA0A0A0);
        graphics.text(this.font,
                Component.literal("LibreTranslate /translate 地址（仅 API 模式/回退使用）"),
                left, layout.endpointY - 11, 0xA0A0A0);
        String runtimeStatus = FabricTranslationRuntime.status();
        int belowSave = layout.saveY + 28;
        int messageY = belowSave <= this.height - 10 ? belowSave : layout.saveY - 14;
        if (!status.isEmpty()) {
            graphics.centeredText(this.font, Component.literal(status),
                    this.width / 2, messageY, 0xFF5555);
        } else if (!runtimeStatus.isEmpty()) {
            graphics.centeredText(this.font, Component.literal(runtimeStatus),
                    this.width / 2, messageY,
                    isFailureStatus(runtimeStatus) ? 0xFF5555 : 0x55FF55);
        } else if (layout.saveY - layout.endpointY >= 52) {
            int infoY = layout.endpointY + 28;
            graphics.centeredText(
                    this.font,
                    Component.literal(isOffline()
                            ? "离线模式只访问本机；首次使用会在后台下载约 502 MB。"
                            : "API 模式会把选中的服务器文字发送到翻译服务。"),
                    this.width / 2, infoY, 0xFFAA55);
            graphics.centeredText(this.font,
                    Component.literal("F8 一键开关；可在 设置 → 控制 → 按键绑定 中修改。"),
                    this.width / 2, infoY + 15, 0xA0A0A0);
        }
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private boolean isTencent() {
        return "tencent-hunyuan".equalsIgnoreCase(provider);
    }

    private boolean isOffline() {
        return "offline".equalsIgnoreCase(provider);
    }

    private String providerLabel() {
        return isOffline() ? "离线" : (isTencent() ? "腾讯" : "Libre");
    }

    private static String nextProvider(String current) {
        if ("offline".equalsIgnoreCase(current)) {
            return "libretranslate";
        }
        if ("libretranslate".equalsIgnoreCase(current)) {
            return "tencent-hunyuan";
        }
        return "offline";
    }

    private static String colorLabel(TranslationTextColor color) {
        switch (color) {
            case ORIGINAL: return "保留原色";
            case GREEN: return "绿色";
            case GOLD: return "金色";
            case LIGHT_PURPLE: return "浅紫";
            case YELLOW: return "黄色";
            case WHITE: return "白色";
            case AQUA:
            default: return "青色";
        }
    }

    private Layout layout() {
        int totalWidth = Math.max(180, Math.min(310, this.width - 20));
        int gap = 8;
        int buttonWidth = (totalWidth - gap) / 2;
        int left = (this.width - totalWidth) / 2;
        int top = Math.max(20, Math.min(44, 20 + Math.max(0, this.height - 220) / 4));
        int rowStep = this.height >= 300 ? 26 : 22;
        int targetY = top + rowStep * 5 + 2;
        int endpointY = targetY + (this.height >= 300 ? 32 : 28);
        int saveY = this.height >= 330 ? 270 : Math.max(endpointY + 22, this.height - 24);
        return new Layout(left, left + buttonWidth + gap, totalWidth, buttonWidth,
                top, rowStep, targetY, endpointY, saveY);
    }

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

        private Layout(int left, int right, int totalWidth, int buttonWidth,
                       int top, int rowStep, int targetY, int endpointY, int saveY) {
            this.left = left;
            this.right = right;
            this.totalWidth = totalWidth;
            this.buttonWidth = buttonWidth;
            this.top = top;
            this.rowStep = rowStep;
            this.targetY = targetY;
            this.endpointY = endpointY;
            this.saveY = saveY;
        }

        private int row(int index) {
            return top + rowStep * index;
        }
    }
}
