package org.universaltranslator.core.provider;

import org.universaltranslator.core.net.JsonStrings;

import java.util.List;
import java.util.Map;

/**
 * Reads several fields out of one parsed JSON document.
 *
 * <p>{@link JsonStrings} only offers "path string in, value out", and every call parses the whole
 * body again. A provider that needs four fields of one reply therefore built the same object tree
 * four times — {@code extractContent} plus the field fallback, {@code describeMissingContent}, the
 * {@code reasoning_content} test and the usage check — and the fragment-joining readers rebuilt it
 * once per fragment, so an N-fragment answer cost N+1 parses. On the render worker that is N+1
 * garbage trees per translated line.
 *
 * <p>Parsing once and reading every field from the resulting {@link Map}/{@link List} removes that
 * multiplication without changing any of the paths.
 */
final class JsonTree {
    private JsonTree() {
    }

    /**
     * Parses one body, or returns {@code null} when it is not JSON at all.
     *
     * <p>A body that is not JSON is a normal outcome here — a gateway answers with an HTML page and a
     * relay answers with a bare string — so the caller's own diagnostics describe it instead of a
     * parse failure escaping from a field lookup.
     *
     * @param json raw body, possibly {@code null}
     * @return the parsed root, or {@code null}
     */
    static Object parseOrNull(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return JsonStrings.parse(json);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * Reads a string through a small JSON path such as {@code choices[0].message.content}.
     *
     * @param root parsed document
     * @param path dotted path with optional {@code [index]} segments
     * @return the string at that path, or {@code null} when it is missing or not a string
     */
    static String stringAt(Object root, String path) {
        if (root == null || path == null || path.isEmpty()) {
            return null;
        }
        Object current = root;
        int cursor = 0;
        while (cursor < path.length()) {
            int nameStart = cursor;
            while (cursor < path.length()
                    && path.charAt(cursor) != '.'
                    && path.charAt(cursor) != '[') {
                cursor++;
            }
            if (cursor > nameStart) {
                if (!(current instanceof Map)) {
                    return null;
                }
                current = ((Map<?, ?>) current).get(path.substring(nameStart, cursor));
            }
            while (cursor < path.length() && path.charAt(cursor) == '[') {
                int close = path.indexOf(']', cursor + 1);
                if (close < 0 || !(current instanceof List)) {
                    return null;
                }
                int index;
                try {
                    index = Integer.parseInt(path.substring(cursor + 1, close));
                } catch (NumberFormatException notAnIndex) {
                    return null;
                }
                List<?> list = (List<?>) current;
                if (index < 0 || index >= list.size()) {
                    return null;
                }
                current = list.get(index);
                cursor = close + 1;
            }
            if (current == null) {
                return null;
            }
            if (cursor < path.length()) {
                if (path.charAt(cursor) != '.') {
                    return null;
                }
                cursor++;
            }
        }
        return current instanceof String ? (String) current : null;
    }

    /**
     * Joins one field of every element of an array that starts at {@code arrayPath}.
     *
     * @param root parsed document
     * @param arrayPath path of the array, without an index
     * @param field field of each element to join
     * @param maximumFragments bound against a provider that answers with an unbounded array
     * @return the concatenated text, or {@code null} when the array carried none
     */
    static String joinField(Object root, String arrayPath, String field, int maximumFragments) {
        if (root == null) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (int index = 0; index < maximumFragments; index++) {
            String fragment = stringAt(root, arrayPath + "[" + index + "]." + field);
            if (fragment == null) {
                break;
            }
            joined.append(fragment);
        }
        return joined.length() == 0 ? null : joined.toString();
    }
}
