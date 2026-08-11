package org.universaltranslator.fabric;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.universaltranslator.core.TranslationDisplayMode;
import org.universaltranslator.core.OfflineModel;
import org.universaltranslator.core.TargetLanguage;
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
    private OfflineModel offlineModel;
    private boolean apiFallback;
    private TranslationDisplayMode displayMode;
    private boolean translateEnglishOnly;
    private TranslationTextColor translatedTextColor;
    private String provider;
    private String targetLanguageValue;
    private String endpointValue;
    private TextFieldWidget targetLanguage;
    private TextFieldWidget endpoint;
    private ButtonWidget enabledButton;
    private ButtonWidget chatButton;
    private ButtonWidget otherButton;
    private ButtonWidget cacheButton;
    private ButtonWidget providerButton;
    private ButtonWidget displayButton;
    private ButtonWidget downloadButton;
    private ButtonWidget modelButton;
    private ButtonWidget fallbackButton;
    private ButtonWidget diagnosticsButton;
    private ButtonWidget mixedTextButton;
    private ButtonWidget colorButton;
    private ButtonWidget targetLanguageButton;
    private String status = "";

    UniversalTranslatorConfigScreen(Screen parent, FabricConfig config) {
        super(Text.literal("MC 自动翻译工具 设置"));
        this.parent = parent;
        this.original = config;
        this.enabled = config.enabled;
        this.translateChat = config.translateChat;
        this.translateOther = config.translateOther;
        this.diskCache = config.diskCache;
        this.offlineAutoDownload = config.offlineAutoDownload;
        this.offlineModel = config.offlineModel;
        this.apiFallback = config.apiFallback;
        this.displayMode = config.displayMode;
        this.translateEnglishOnly = config.translateEnglishOnly;
        this.translatedTextColor = config.translatedTextColor;
        this.provider = config.provider;
        this.targetLanguageValue = config.targetLanguage;
        this.endpointValue = config.endpoint;
    }

    @Override
    protected void init() {
        Layout layout = layout();
        int left = layout.left;
        this.enabledButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            enabled = !enabled;
            refreshLabels();
        }).dimensions(left, layout.row(0), layout.buttonWidth, 20).build());
        this.cacheButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            diskCache = !diskCache;
            refreshLabels();
        }).dimensions(layout.right, layout.row(0), layout.buttonWidth, 20).build());
        this.chatButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            translateChat = !translateChat;
            refreshLabels();
        }).dimensions(left, layout.row(1), layout.buttonWidth, 20).build());
        this.otherButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            translateOther = !translateOther;
            refreshLabels();
        }).dimensions(layout.right, layout.row(1), layout.buttonWidth, 20).build());
        this.providerButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            provider = nextProvider(provider);
            refreshLabels();
        }).dimensions(left, layout.row(2), layout.buttonWidth, 20).build());
        this.displayButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            displayMode = displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED
                    ? TranslationDisplayMode.TRANSLATED_ONLY
                    : TranslationDisplayMode.ORIGINAL_AND_TRANSLATED;
            refreshLabels();
        }).dimensions(layout.right, layout.row(2), layout.buttonWidth, 20).build());
        this.mixedTextButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            translateEnglishOnly = !translateEnglishOnly;
            refreshLabels();
        }).dimensions(left, layout.row(3), layout.buttonWidth, 20).build());
        this.colorButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            translatedTextColor = translatedTextColor.next();
            refreshLabels();
        }).dimensions(layout.right, layout.row(3), layout.buttonWidth, 20).build());
        this.downloadButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            offlineAutoDownload = !offlineAutoDownload;
            refreshLabels();
        }).dimensions(left, layout.row(4), layout.buttonWidth, 20).build());
        this.fallbackButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            apiFallback = !apiFallback;
            refreshLabels();
        }).dimensions(layout.right, layout.row(4), layout.buttonWidth, 20).build());
        this.modelButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            offlineModel = offlineModel.next();
            refreshLabels();
        }).dimensions(left, layout.row(5), layout.buttonWidth, 20).build());
        this.diagnosticsButton = addDrawableChild(ButtonWidget.builder(Text.literal("翻译诊断"), button -> {
            if (client != null) {
                targetLanguageValue = targetLanguage.getText();
                endpointValue = endpoint.getText();
                client.setScreen(new UniversalTranslatorDiagnosticsScreen(this));
            }
        }).dimensions(layout.right, layout.row(5), layout.buttonWidth, 20).build());

        this.targetLanguage = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, layout.targetY, layout.buttonWidth, 20, Text.literal("目标语言")));
        this.targetLanguage.setMaxLength(32);
        this.targetLanguage.setText(targetLanguageValue);
        this.targetLanguageButton = addDrawableChild(ButtonWidget.builder(Text.empty(), button -> {
            targetLanguage.setText(TargetLanguage.nextPreset(targetLanguage.getText()));
            refreshLabels();
        }).dimensions(layout.right, layout.targetY, layout.buttonWidth, 20).build());
        this.endpoint = addDrawableChild(new TextFieldWidget(
                this.textRenderer, left, layout.endpointY, layout.totalWidth, 20,
                Text.literal("LibreTranslate 地址")));
        this.endpoint.setMaxLength(512);
        this.endpoint.setText(endpointValue);

        addDrawableChild(ButtonWidget.builder(Text.literal("保存并应用"), button -> saveAndApply())
                .dimensions(left, layout.saveY, layout.buttonWidth, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("取消"), button -> close())
                .dimensions(layout.right, layout.saveY, layout.buttonWidth, 20).build());
        refreshLabels();
    }

    private void refreshLabels() {
        enabledButton.setMessage(Text.literal("自动翻译: " + onOff(enabled)));
        chatButton.setMessage(Text.literal("聊天内容: " + onOff(translateChat)));
        otherButton.setMessage(Text.literal("其他界面: " + onOff(translateOther)));
        cacheButton.setMessage(Text.literal("本地缓存: " + onOff(diskCache)));
        providerButton.setMessage(Text.literal("服务: " + providerLabel()));
        displayButton.setMessage(Text.literal("显示: "
                + (displayMode == TranslationDisplayMode.ORIGINAL_AND_TRANSLATED ? "原文+译文" : "仅译文")));
        mixedTextButton.setMessage(Text.literal("混合文本仅译英文: " + onOff(translateEnglishOnly)));
        colorButton.setMessage(Text.literal("译文颜色: " + colorLabel(translatedTextColor)));
        downloadButton.setMessage(Text.literal("模型下载: " + onOff(offlineAutoDownload)));
        modelButton.setMessage(Text.literal("离线模型: " + offlineModel.displayName()));
        fallbackButton.setMessage(Text.literal("API 回退: " + onOff(apiFallback)));
        targetLanguageButton.setMessage(Text.literal("目标: "
                + TargetLanguage.displayName(targetLanguage.getText())));
        downloadButton.active = isOffline();
        modelButton.active = isOffline();
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
            if (targetLanguage.getText().trim().isEmpty()) {
                throw new IllegalArgumentException("目标语言不能为空");
            }
            FabricConfig updated = original.withSettings(
                    enabled,
                    translateChat,
                    translateOther,
                    targetLanguage.getText(),
                    displayMode,
                    translateEnglishOnly,
                    translatedTextColor,
                    provider,
                    endpoint.getText(),
                    offlineAutoDownload,
                    offlineModel,
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
            close();
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
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 18, 0xFFFFFF);
        Layout layout = layout();
        int left = layout.left;
        context.drawTextWithShadow(this.textRenderer, Text.literal("目标语言代码 / 快捷选择"),
                left, layout.targetY - 11, 0xA0A0A0);
        context.drawTextWithShadow(this.textRenderer,
                Text.literal("LibreTranslate /translate 地址（仅 API 模式/回退使用）"),
                left, layout.endpointY - 11, 0xA0A0A0);
        String runtimeStatus = FabricTranslationRuntime.status();
        int belowSave = layout.saveY + 28;
        int messageY = belowSave <= this.height - 10 ? belowSave : layout.saveY - 14;
        if (!status.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status),
                    this.width / 2, messageY, 0xFF5555);
        } else if (!runtimeStatus.isEmpty()) {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(runtimeStatus),
                    this.width / 2, messageY,
                    isFailureStatus(runtimeStatus) ? 0xFF5555 : 0x55FF55);
        } else if (layout.saveY - layout.endpointY >= 52) {
            int infoY = layout.endpointY + 28;
            context.drawCenteredTextWithShadow(
                    this.textRenderer,
                    Text.literal(isOffline()
                            ? "离线模式只访问本机；首次使用会在后台下载约 502 MB。"
                            : "API 模式会把选中的服务器文字发送到翻译服务。"),
                    this.width / 2, infoY, 0xFFAA55);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("F8 一键开关；可在 设置 → 控制 → 按键绑定 中修改。"),
                    this.width / 2, infoY + 15, 0xA0A0A0);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void close() {
        if (this.client != null) {
            this.client.setScreen(parent);
        }
    }

    @Override
    public boolean shouldPause() {
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
        int rowStep = this.height >= 300 ? 26 : (this.height >= 260 ? 22 : 20);
        int targetY = top + rowStep * 6 + 2;
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
