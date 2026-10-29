package org.universaltranslator.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.ChatScreen;
import org.universaltranslator.core.TranslationTextStyling;

/** Identifies text currently being edited by the local player in the chat box. */
public final class ForgeLocalTextGuard {
    /**
     * The chat screen's edit box, reported by
     * {@link org.universaltranslator.forge.mixin.TextFieldWidgetMixin} while it renders.
     *
     * <p>This used to be found by reflection: scan {@code ChatScreen}'s declared fields, take the
     * first one whose type is {@code TextFieldWidget}, then read it on every rendered string. Two
     * things were wrong with that. The pick depended on declaration order, so a Forge patch or
     * another mod adding a second text field could make the guard read the wrong widget and treat
     * the player's own input as world text. And the lookup was only latched on success, so a single
     * failure re-walked the whole class hierarchy (with {@code setAccessible}) for every rendered
     * string, every frame. The mixin already knows the instance, so it reports it instead.
     */
    private static volatile TextFieldWidget chatInput;

    private ForgeLocalTextGuard() {
    }

    /** Called from the mixin at the top of the text field's render pass. */
    public static void trackRenderedInput(TextFieldWidget widget) {
        if (widget != null && Minecraft.getInstance().screen instanceof ChatScreen) {
            chatInput = widget;
        }
    }

    static Object currentScreen(Minecraft client) {
        return client == null ? null : client.screen;
    }

    static boolean isLocalChatInput(Minecraft client, String rendered) {
        if (client == null || !(client.screen instanceof ChatScreen)
                || rendered == null || rendered.isEmpty()) {
            return false;
        }
        TextFieldWidget input = chatInput;
        if (input == null) {
            // The chat box has not drawn yet; nothing can be the player's own input at this point.
            return false;
        }
        return matches(input.getValue(), rendered);
    }

    private static boolean matches(String typed, String rendered) {
        if (typed == null || typed.isEmpty()) {
            return false;
        }
        String visible = TranslationTextStyling.stripLegacyFormatting(rendered);
        if (visible.equals(typed) || visible.equals(typed + "_")) {
            return true;
        }
        return visible.length() >= 2 && typed.contains(visible);
    }
}
