package org.universaltranslator.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import org.universaltranslator.core.TranslationTextStyling;

/** Identifies text currently being edited by the local player in the chat box. */
public final class ForgeLocalTextGuard {
    /**
     * The chat screen's edit box, reported by
     * {@link org.universaltranslator.forge.mixin.TextFieldWidgetMixin} while it renders.
     *
     * <p>This used to be found by reflection: scan {@code ChatScreen}'s declared fields, take the
     * first one whose type is {@code EditBox}, then read it on every rendered string. Two things
     * were wrong with that. The pick depended on declaration order, so a mod adding a second text
     * field to the chat screen could make the guard read the wrong widget and treat the player's
     * own input as world text. And the lookup was only latched on success, so a single failure
     * re-walked the whole class hierarchy (with {@code setAccessible}) for every rendered string,
     * every frame. The mixin already knows the instance, so it reports it instead.
     */
    private static volatile EditBox chatInput;
    /** Last text the chat box held, with the two cursor forms the renderer appends to it. */
    private static volatile String cachedTyped;
    private static volatile String cachedTypedUnderscore;
    private static volatile String cachedTypedPipe;
    /** Last stripped line, kept so the same drawn line is not re-walked for every hook. */
    private static volatile String strippedSource;
    private static volatile String strippedValue;

    private ForgeLocalTextGuard() {
    }

    /** Called from the mixin at the top of the text field's render pass. */
    public static void trackRenderedInput(EditBox widget) {
        if (widget != null && Minecraft.getInstance().gui.screen() instanceof ChatScreen) {
            chatInput = widget;
        }
    }

    static Object currentScreen(Minecraft client) {
        return client == null ? null : client.gui.screen();
    }

    static boolean isLocalChatInput(Minecraft client, String rendered) {
        if (client == null || !(client.gui.screen() instanceof ChatScreen)
                || rendered == null || rendered.isEmpty()) {
            return false;
        }
        EditBox field = chatInput;
        if (field == null) {
            // The chat box has not drawn yet; nothing can be the player's own input at this point.
            return false;
        }
        return matches(field.getValue(), rendered);
    }

    /**
     * True when {@code rendered} is the chat box's own text, cursor included.
     *
     * <p>Only equality counts. Accepting "typed contains rendered" made every other line that
     * happened to be a substring of the draft untranslatable while the chat screen was open: typing
     * {@code hello world} silently skipped the server message {@code world}.
     */
    private static boolean matches(String typed, String rendered) {
        if (typed == null || typed.isEmpty()) {
            return false;
        }
        // The cursor candidates are derived once per distinct typed value instead of three
        // concatenations per drawn line, and the stripped comparison is memoized for the line
        // currently being drawn.
        String underscore = cachedTypedUnderscore;
        String pipe = cachedTypedPipe;
        if (!typed.equals(cachedTyped)) {
            underscore = typed + "_";
            pipe = typed + "|";
            cachedTypedUnderscore = underscore;
            cachedTypedPipe = pipe;
            cachedTyped = typed;
        }
        String visible = stripped(rendered);
        return visible.equals(typed)
                || visible.equals(underscore)
                || visible.equals(pipe);
    }

    /**
     * {@code rendered} without its legacy colour codes, memoized for the last line seen; the value
     * is published before its source, so a racing reader sees the pair or recomputes.
     */
    private static String stripped(String rendered) {
        String source = strippedSource;
        if (source != null && source.equals(rendered)) {
            return strippedValue;
        }
        String value = TranslationTextStyling.stripLegacyFormatting(rendered);
        strippedValue = value;
        strippedSource = rendered;
        return value;
    }
}
