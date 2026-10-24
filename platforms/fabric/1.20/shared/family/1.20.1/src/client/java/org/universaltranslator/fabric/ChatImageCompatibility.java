package org.universaltranslator.fabric;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.text.MutableText;
import net.minecraft.text.StringVisitable;
import org.universaltranslator.core.DebugLog;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Normalizes translatable chat arguments before ChatImage 1.4.7 inspects them.
 *
 * <p>ChatImage assumes every argument of a translatable component is another
 * {@link Text}. Vanilla and ChatPatches legitimately retain plain strings in
 * that array, so restoring a large chat history otherwise throws and logs once
 * per message on the render thread. Converting those values to literal text is
 * display-equivalent for Minecraft's translatable chat placeholders.</p>
 */
public final class ChatImageCompatibility {
    private static final int MAX_COMPONENT_DEPTH = 64;
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    private ChatImageCompatibility() {
    }

    public static Text normalizeArguments(Text message) {
        if (message == null || !LoadedMods.CHAT_IMAGE) {
            return message;
        }
        Normalization normalized = normalize(
                message, new IdentityHashMap<Text, Normalization>(), 0);
        if (normalized.replacements > 0 && REPORTED.compareAndSet(false, true)) {
            // Once per session, through DebugLog so the debug-log switch controls it and the line is
            // stamped like every other tool line. A bare System.out.println bypassed both.
            DebugLog.global().log("chatimage", "compatibility", "normalized");
        }
        return normalized.text;
    }

    static Text normalizeForTest(Text message) {
        if (message == null) {
            return null;
        }
        return normalize(message, new IdentityHashMap<Text, Normalization>(), 0).text;
    }

    private static Normalization normalize(
            Text message,
            IdentityHashMap<Text, Normalization> memoized,
            int depth
    ) {
        if (message == null || depth > MAX_COMPONENT_DEPTH) {
            return new Normalization(message, 0);
        }
        Normalization cached = memoized.get(message);
        if (cached != null) {
            return cached == Normalization.IN_PROGRESS
                    ? new Normalization(message, 0) : cached;
        }
        memoized.put(message, Normalization.IN_PROGRESS);

        int replacements = 0;
        boolean contentChanged = false;
        Object[] normalizedArguments = null;
        if (message.getContent() instanceof TranslatableTextContent) {
            Object[] arguments = ((TranslatableTextContent) message.getContent()).getArgs();
            normalizedArguments = arguments.clone();
            for (int index = 0; index < arguments.length; index++) {
                Object argument = arguments[index];
                if (argument instanceof Text) {
                    Normalization normalized = normalize(
                            (Text) argument, memoized, depth + 1);
                    normalizedArguments[index] = normalized.text;
                    replacements += normalized.replacements;
                    contentChanged |= normalized.text != argument;
                } else {
                    String literal = argument instanceof StringVisitable
                            ? ((StringVisitable) argument).getString()
                            : argument == null ? "" : String.valueOf(argument);
                    normalizedArguments[index] = Text.literal(literal);
                    replacements++;
                    contentChanged = true;
                }
            }
        }

        List<Text> normalizedSiblings = new ArrayList<Text>(message.getSiblings().size());
        boolean siblingsChanged = false;
        for (Text sibling : message.getSiblings()) {
            Normalization normalized = normalize(sibling, memoized, depth + 1);
            normalizedSiblings.add(normalized.text);
            replacements += normalized.replacements;
            siblingsChanged |= normalized.text != sibling;
        }
        if (!contentChanged && !siblingsChanged) {
            Normalization unchanged = new Normalization(message, replacements);
            memoized.put(message, unchanged);
            return unchanged;
        }

        MutableText replacement;
        if (contentChanged) {
            TranslatableTextContent content =
                    (TranslatableTextContent) message.getContent();
            replacement = content.getFallback() == null
                    ? Text.translatable(content.getKey(), normalizedArguments)
                    : Text.translatableWithFallback(
                            content.getKey(), content.getFallback(), normalizedArguments);
        } else {
            replacement = message.copyContentOnly();
        }
        replacement.setStyle(message.getStyle());
        for (Text sibling : normalizedSiblings) {
            replacement.append(sibling);
        }
        Normalization result = new Normalization(replacement, replacements);
        memoized.put(message, result);
        return result;
    }

    private static final class LoadedMods {
        private static final boolean CHAT_IMAGE =
                FabricLoader.getInstance().isModLoaded("chatimage");
    }

    private static final class Normalization {
        private static final Normalization IN_PROGRESS =
                new Normalization(null, 0);

        private final Text text;
        private final int replacements;

        private Normalization(Text text, int replacements) {
            this.text = text;
            this.replacements = replacements;
        }
    }
}
