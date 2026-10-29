package org.universaltranslator.fabric;

import net.minecraft.client.resource.language.I18n;
import org.universaltranslator.core.HudIndicatorColor;
import org.universaltranslator.core.HudIndicatorContent;
import org.universaltranslator.core.HudIndicatorCorner;
import org.universaltranslator.core.HudIndicatorVisibility;
import org.universaltranslator.core.TranslationStatusLocalizer;
import org.universaltranslator.core.UiTextKeys;

/**
 * Text shared by the 1.13 settings, diagnostics and LLM screens.
 *
 * <p>Mechanical extraction only (audit S2-095): every method here was copied verbatim into two or
 * three of those screens, so a label change had to be made two or three times and one of the copies
 * inevitably drifted. Nothing about the layout, the button ids or the drawn text changed.
 */
final class ScreenText {
    private ScreenText() {
    }

    static String onOff(boolean value) {
        return tr(UiTextKeys.onOff(value));
    }

    static boolean isFailureStatus(String value) {
        return TranslationStatusLocalizer.isFailure(value);
    }

    static String describe(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    /** Keeps the read-only endpoint line inside the panel; the editor still holds the full value. */
    static String trimForDisplay(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= 44 ? trimmed : "\u2026" + trimmed.substring(trimmed.length() - 43);
    }

    static String hudCornerLabel(HudIndicatorCorner corner) {
        switch (corner) {
            case TOP_RIGHT: return tr("value.universal_translator.hud_corner.top_right");
            case BOTTOM_LEFT: return tr("value.universal_translator.hud_corner.bottom_left");
            case BOTTOM_RIGHT: return tr("value.universal_translator.hud_corner.bottom_right");
            case TOP_LEFT:
            default: return tr("value.universal_translator.hud_corner.top_left");
        }
    }

    static String hudColorLabel(HudIndicatorColor color) {
        return tr("value.universal_translator.color." + color.configName().replace('-', '_'));
    }

    static String hudContentLabel(HudIndicatorContent content) {
        return tr("value.universal_translator.hud_content." + content.configName().replace('-', '_'));
    }

    static String hudVisibilityLabel(HudIndicatorVisibility visibility) {
        return tr("value.universal_translator.hud_visibility." + visibility.configName().replace('-', '_'));
    }

    private static String tr(String key, Object... arguments) {
        return I18n.translate(key, arguments);
    }
}
