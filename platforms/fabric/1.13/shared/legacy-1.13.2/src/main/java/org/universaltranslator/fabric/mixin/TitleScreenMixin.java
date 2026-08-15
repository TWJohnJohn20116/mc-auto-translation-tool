package org.universaltranslator.fabric.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import org.universaltranslator.fabric.ActionButton;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.universaltranslator.core.HomeQuickSettingsState;
import org.universaltranslator.core.TargetLanguage;
import org.universaltranslator.fabric.FabricTranslationRuntime;
import org.universaltranslator.fabric.OrnitheClientAccess;

@Mixin(TitleScreen.class)
abstract class TitleScreenMixin extends Screen {
    @Unique
    private static final int UT_ENABLED = 31700;
    @Unique
    private static final int UT_VANILLA = 31701;
    @Unique
    private static final int UT_TARGET = 31702;

    @Inject(method = "init()V", at = @At("TAIL"), require = 0)
    private void universalTranslator$addQuickSettings(CallbackInfo callback) {
        int x = Math.max(4, this.width - 136);
        this.addButton(new ActionButton(UT_ENABLED, x, 6, 132, 20, "",
                () -> universalTranslator$change(0)));
        this.addButton(new ActionButton(UT_VANILLA, x, 29, 132, 20, "",
                () -> universalTranslator$change(1)));
        this.addButton(new ActionButton(UT_TARGET, x, 52, 132, 20, "",
                () -> universalTranslator$change(2)));
        universalTranslator$refresh(FabricTranslationRuntime.homeSettings());
    }

    @Unique
    private void universalTranslator$change(int action) {
        try {
            HomeQuickSettingsState state;
            if (action == 0) {
                state = FabricTranslationRuntime.toggleHomeEnabled();
            } else if (action == 1) {
                state = FabricTranslationRuntime.toggleHomeVanilla();
            } else {
                state = FabricTranslationRuntime.cycleHomeTargetLanguage();
            }
            universalTranslator$refresh(state);
        } catch (Exception exception) {
            System.err.println("[MC Auto Translation Tool] Could not update title-screen setting: " + exception);
            universalTranslator$refresh(FabricTranslationRuntime.homeSettings());
        }
    }

    @Unique
    private void universalTranslator$refresh(HomeQuickSettingsState state) {
        for (Object raw : this.buttons) {
            if (!(raw instanceof ButtonWidget)) {
                continue;
            }
            ButtonWidget button = (ButtonWidget) raw;
            if (button.id == UT_ENABLED) {
                button.message = OrnitheClientAccess.tr("screen.universal_translator.home.enabled",
                        OrnitheClientAccess.tr(state.isEnabled()
                                ? "value.universal_translator.enabled"
                                : "value.universal_translator.disabled"));
            } else if (button.id == UT_VANILLA) {
                button.message = OrnitheClientAccess.tr("screen.universal_translator.home.vanilla",
                        OrnitheClientAccess.tr(state.isTranslateVanilla()
                                ? "value.universal_translator.enabled"
                                : "value.universal_translator.disabled"));
            } else if (button.id == UT_TARGET) {
                button.message = OrnitheClientAccess.tr("screen.universal_translator.home.target",
                        TargetLanguage.displayName(state.getTargetLanguage()));
            }
        }
    }
}
