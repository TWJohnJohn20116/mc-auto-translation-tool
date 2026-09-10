package org.universaltranslator.fabric;

import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;

/** Dependency-free regression check for the ChatImage 1.4.7 compatibility guard. */
public final class ChatImageCompatibilitySelfTest {
    private ChatImageCompatibilitySelfTest() {
    }

    public static void main(String[] arguments) {
        MutableText nested = Text.translatable("test.nested", "nested string");
        MutableText sibling = Text.translatable("test.sibling", "sibling string");
        MutableText message = Text.translatable(
                "test.outer", "plain string", nested, Integer.valueOf(42), null);
        message.setStyle(Style.EMPTY.withBold(true));
        message.append(sibling);

        Text normalized = ChatImageCompatibility.normalizeForTest(message);

        assertNotSame(message, normalized, "changed message identity");
        assertEquals(message.getStyle(), normalized.getStyle(), "message style");
        assertTextArguments(normalized, "plain string", "test.nested", "42", "");
        Text normalizedNested = (Text) arguments(normalized)[1];
        assertTextArguments(normalizedNested, "nested string");
        assertTextArguments(normalized.getSiblings().get(0), "sibling string");
        if (arguments(message)[0] instanceof Text) {
            throw new AssertionError("original message was mutated");
        }
        assertSame(normalized, ChatImageCompatibility.normalizeForTest(normalized),
                "normalization must be idempotent");

        MutableText shared = Text.translatable("test.shared", "shared string");
        Text normalizedShared = ChatImageCompatibility.normalizeForTest(
                Text.translatable("test.shared_pair", shared, shared));
        Object[] sharedArguments = arguments(normalizedShared);
        assertSame(sharedArguments[0], sharedArguments[1], "shared component memoization");
        assertTextArguments((Text) sharedArguments[0], "shared string");

        Text normalizedFallback = ChatImageCompatibility.normalizeForTest(
                Text.translatableWithFallback("test.fallback", "Fallback: %s", "value"));
        assertEquals("Fallback: %s",
                ((TranslatableTextContent) normalizedFallback.getContent()).getFallback(),
                "translatable fallback");
        System.out.println("ChatImageCompatibilitySelfTest: all checks passed");
    }

    private static void assertTextArguments(Text text, String... expected) {
        Object[] actual = arguments(text);
        if (actual.length < expected.length) {
            throw new AssertionError("not enough arguments: " + actual.length);
        }
        for (int index = 0; index < expected.length; index++) {
            if (!(actual[index] instanceof Text)) {
                throw new AssertionError("argument " + index + " was not normalized: " + actual[index]);
            }
            assertEquals(expected[index], ((Text) actual[index]).getString(),
                    "argument " + index);
        }
    }

    private static Object[] arguments(Text text) {
        if (!(text.getContent() instanceof TranslatableTextContent)) {
            throw new AssertionError("expected translatable content");
        }
        return ((TranslatableTextContent) text.getContent()).getArgs();
    }

    private static void assertSame(Object expected, Object actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected same object");
        }
    }

    private static void assertNotSame(Object expected, Object actual, String label) {
        if (expected == actual) {
            throw new AssertionError(label + ": expected different objects");
        }
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(label + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
