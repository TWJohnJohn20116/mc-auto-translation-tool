package org.universaltranslator.forge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.screen.ChatScreen;
import org.universaltranslator.core.TranslationTextStyling;

import java.util.LinkedHashMap;
import java.util.Map;

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
    /**
     * Stripped lines, most recently used last; bounded because they are player and server text.
     *
     * <p>The chat window draws a hundred lines in a fixed order, so a single slot would miss on
     * nearly every lookup and every miss would rebuild the whole line. One map per render thread:
     * these are cache entries keyed by the text drawn in this frame, not state of the guard.
     */
    private static final int STRIPPED_MEMO_LIMIT = 64;
    private static final ThreadLocal<LinkedHashMap<String, String>> STRIPPED =
            new ThreadLocal<LinkedHashMap<String, String>>() {
                @Override
                protected LinkedHashMap<String, String> initialValue() {
                    return new LinkedHashMap<String, String>(16, 0.75F, true) {
                        @Override
                        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                            return size() > STRIPPED_MEMO_LIMIT;
                        }
                    };
                }
            };

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
        String visible = stripped(rendered);
        return visible.equals(typed)
                || visible.equals(typed + "_")
                || visible.equals(typed + "|");
    }

    /**
     * Strips legacy colour codes, reusing the answer for the lines of this frame.
     *
     * <p>The chat window redraws every visible line each frame, so without this memo the strip ran
     * once per line per frame — a fresh {@code StringBuilder} and {@code String} each time.
     */
    private static String stripped(String rendered) {
        LinkedHashMap<String, String> memo = STRIPPED.get();
        String cached = memo.get(rendered);
        if (cached != null) {
            return cached;
        }
        String computed = TranslationTextStyling.stripLegacyFormatting(rendered);
        memo.put(rendered, computed);
        return computed;
    }
}
