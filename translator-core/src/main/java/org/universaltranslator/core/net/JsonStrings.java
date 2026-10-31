package org.universaltranslator.core.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.universaltranslator.core.TextPreview;

/** Small dependency-free JSON string helper for the narrow provider response formats we use. */
public final class JsonStrings {
    private JsonStrings() {
    }

    public static String quote(String value) {
        StringBuilder output = new StringBuilder(value.length() + 16).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (character < 0x20) {
                        output.append(String.format("\\u%04x", (int) character));
                    } else {
                        output.append(character);
                    }
            }
        }
        return output.append('"').toString();
    }

    /** Parses arbitrary valid JSON into Maps, Lists, Strings, Booleans, and null. */
    public static Object parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("JSON string is empty");
        }
        try {
            return new Parser(json).parse();
        } catch (IllegalArgumentException malformed) {
            // A bare "Invalid JSON value" says nothing about what the server actually sent, which is
            // the first thing anyone needs when an endpoint answers 200 with a non-JSON body.
            String reason = malformed.getMessage() == null ? "Invalid JSON" : malformed.getMessage();
            throw new IllegalArgumentException(reason + bodyPreview(json), malformed);
        }
    }

    /**
     * Bounded single-line excerpt of a body, for error messages that must say what came back.
     *
     * <p>Sixty characters is enough to tell an HTML error page from a provider JSON error and
     * short enough to survive a Minecraft chat line.
     *
     * @param json raw response body, possibly {@code null}
     * @return an excerpt prefixed for appending to a sentence, never {@code null}
     */
    public static String bodyPreview(String json) {
        String value = json == null ? "" : json;
        int limit = Math.min(value.length(), 60);
        String excerpt = TextPreview.oneLine(value, limit);
        if (value.length() > limit) {
            excerpt = excerpt + '\u2026';
        }
        return " (body starts with: \"" + excerpt + "\")";
    }

    /**
     * Reads a string through a small JSON path such as choices[0].message.content.
     *
     * <p>The path is followed over the raw text instead of materialising the document: every value
     * that the path does not name is skipped structurally. A streaming endpoint emits one event per
     * token, so this runs hundreds of times per translation on 150-250 byte documents, where
     * building a throw-away object graph to read a single field dominated the cost of the read.
     */
    public static String readStringPath(String json, String path) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("Translation response is empty");
        }
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Response JSON path is required");
        }
        try {
            return readAtPath(json, normalizePath(path.trim()));
        } catch (IllegalArgumentException malformed) {
            // Same contract as parse(): a syntactically broken body reports what the server sent,
            // because the shape of the failure is the first thing anyone needs.
            String reason = malformed.getMessage() == null ? "Invalid JSON" : malformed.getMessage();
            throw new IllegalArgumentException(reason + bodyPreview(json), malformed);
        }
    }

    /** Strips the optional {@code $} / {@code $.} root marker from a path. */
    private static String normalizePath(String path) {
        if (path.startsWith("$.")) {
            return path.substring(2);
        }
        if ("$".equals(path)) {
            return "";
        }
        return path;
    }

    /**
     * Walks {@code path} over {@code json} and returns the string it ends on.
     *
     * @return the value, or {@code null} when the path does not resolve or does not end on a string
     * @throws IllegalArgumentException when the body is not structurally valid JSON
     */
    private static String readAtPath(String json, String path) {
        Cursor cursor = new Cursor(0);
        skipWhitespace(json, cursor);
        int position = 0;
        while (position < path.length()) {
            int nameStart = position;
            while (position < path.length()
                    && path.charAt(position) != '.'
                    && path.charAt(position) != '[') {
                position++;
            }
            if (position > nameStart && !enterMember(json, cursor, path.substring(nameStart, position))) {
                return null;
            }
            while (position < path.length() && path.charAt(position) == '[') {
                int close = path.indexOf(']', position + 1);
                if (close < 0) {
                    return null;
                }
                int index;
                try {
                    index = Integer.parseInt(path.substring(position + 1, close));
                } catch (NumberFormatException exception) {
                    return null;
                }
                if (!enterElement(json, cursor, index)) {
                    return null;
                }
                position = close + 1;
            }
            if (position < path.length()) {
                if (path.charAt(position) != '.') {
                    return null;
                }
                position++;
            }
            if (cursor.index >= json.length()) {
                return null;
            }
        }
        skipWhitespace(json, cursor);
        if (cursor.index >= json.length() || json.charAt(cursor.index) != '"') {
            return null;
        }
        cursor.index++;
        return readQuoted(json, cursor);
    }

    /** Leaves the cursor on the value of {@code name}, or returns false when it is absent. */
    private static boolean enterMember(String json, Cursor cursor, String name) {
        skipWhitespace(json, cursor);
        if (cursor.index >= json.length() || json.charAt(cursor.index) != '{') {
            return false;
        }
        cursor.index++;
        skipWhitespace(json, cursor);
        if (cursor.index < json.length() && json.charAt(cursor.index) == '}') {
            return false;
        }
        while (true) {
            skipWhitespace(json, cursor);
            if (cursor.index >= json.length() || json.charAt(cursor.index) != '"') {
                throw new IllegalArgumentException("JSON object key must be a string");
            }
            cursor.index++;
            String key = readQuoted(json, cursor);
            skipWhitespace(json, cursor);
            if (cursor.index >= json.length() || json.charAt(cursor.index) != ':') {
                throw new IllegalArgumentException("JSON object key is missing a value");
            }
            cursor.index++;
            skipWhitespace(json, cursor);
            if (name.equals(key)) {
                return true;
            }
            skipValue(json, cursor);
            skipWhitespace(json, cursor);
            if (cursor.index < json.length() && json.charAt(cursor.index) == ',') {
                cursor.index++;
                continue;
            }
            if (cursor.index < json.length() && json.charAt(cursor.index) == '}') {
                return false;
            }
            throw new IllegalArgumentException("JSON object is missing a comma");
        }
    }

    /** Leaves the cursor on element {@code wanted} of the current array, or returns false. */
    private static boolean enterElement(String json, Cursor cursor, int wanted) {
        if (wanted < 0) {
            return false;
        }
        skipWhitespace(json, cursor);
        if (cursor.index >= json.length() || json.charAt(cursor.index) != '[') {
            return false;
        }
        cursor.index++;
        skipWhitespace(json, cursor);
        if (cursor.index < json.length() && json.charAt(cursor.index) == ']') {
            return false;
        }
        for (int current = 0; current <= wanted; current++) {
            if (current == wanted) {
                return true;
            }
            skipValue(json, cursor);
            skipWhitespace(json, cursor);
            if (cursor.index >= json.length() || json.charAt(cursor.index) != ',') {
                return false;
            }
            cursor.index++;
            skipWhitespace(json, cursor);
        }
        return false;
    }

    /** Advances past exactly one value without interpreting it. */
    private static void skipValue(String json, Cursor cursor) {
        skipWhitespace(json, cursor);
        if (cursor.index >= json.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON response");
        }
        char value = json.charAt(cursor.index);
        if (value == '"') {
            cursor.index++;
            readQuoted(json, cursor);
            return;
        }
        if (value == '{' || value == '[') {
            char close = value == '{' ? '}' : ']';
            int depth = 0;
            while (cursor.index < json.length()) {
                char item = json.charAt(cursor.index);
                if (item == '"') {
                    cursor.index++;
                    readQuoted(json, cursor);
                    continue;
                }
                cursor.index++;
                if (item == value) {
                    depth++;
                } else if (item == close) {
                    depth--;
                    if (depth == 0) {
                        return;
                    }
                }
            }
            throw new IllegalArgumentException("Unexpected end of JSON response");
        }
        if (value == 't') {
            expect(json, cursor, "true");
            return;
        }
        if (value == 'f') {
            expect(json, cursor, "false");
            return;
        }
        if (value == 'n') {
            expect(json, cursor, "null");
            return;
        }
        skipNumber(json, cursor);
    }

    private static void expect(String json, Cursor cursor, String literal) {
        if (!json.regionMatches(cursor.index, literal, 0, literal.length())) {
            throw new IllegalArgumentException("Invalid JSON literal");
        }
        cursor.index += literal.length();
    }

    /**
     * Advances past one number, validating the JSON number grammar.
     *
     * <p>Accepting any run of {@code +-0123456789.eE} meant {@code 1.2.3}, {@code +5} and
     * {@code 1e} still parsed, so a truncated or proxied body was silently accepted as valid JSON
     * and the diagnostics pointed at the wrong layer.
     *
     * @throws IllegalArgumentException when no valid number starts at the cursor
     */
    private static void skipNumber(String json, Cursor cursor) {
        int start = cursor.index;
        if (cursor.index < json.length() && json.charAt(cursor.index) == '-') {
            cursor.index++;
        }
        if (cursor.index >= json.length() || !isDigit(json.charAt(cursor.index))) {
            cursor.index = start;
            throw new IllegalArgumentException("Invalid JSON number");
        }
        if (json.charAt(cursor.index) == '0') {
            cursor.index++;
        } else {
            while (cursor.index < json.length() && isDigit(json.charAt(cursor.index))) {
                cursor.index++;
            }
        }
        if (cursor.index < json.length() && json.charAt(cursor.index) == '.') {
            cursor.index++;
            if (cursor.index >= json.length() || !isDigit(json.charAt(cursor.index))) {
                throw new IllegalArgumentException("Invalid JSON number");
            }
            while (cursor.index < json.length() && isDigit(json.charAt(cursor.index))) {
                cursor.index++;
            }
        }
        if (cursor.index < json.length()
                && (json.charAt(cursor.index) == 'e' || json.charAt(cursor.index) == 'E')) {
            cursor.index++;
            if (cursor.index < json.length()
                    && (json.charAt(cursor.index) == '+' || json.charAt(cursor.index) == '-')) {
                cursor.index++;
            }
            if (cursor.index >= json.length() || !isDigit(json.charAt(cursor.index))) {
                throw new IllegalArgumentException("Invalid JSON number");
            }
            while (cursor.index < json.length() && isDigit(json.charAt(cursor.index))) {
                cursor.index++;
            }
        }
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }


    /**
     * Best-effort lookup of a string field. Returns null instead of throwing for any malformed or
     * truncated body, and only accepts matches at an object-key position.
     *
     * <p>When several candidates exist the deepest one wins, and the last one wins among equals.
     * A relay or debug proxy that echoes the request body back puts the prompt in
     * {@code messages[0].content} at a shallower depth than the answer in
     * {@code choices[0].message.content}, and returning the first match handed that prompt to the
     * caller as if it were the translation. Nesting depth is what separates the echoed copy from
     * the real field, so depth is what selects.
     */
    public static String readStringField(String json, String fieldName) {
        if (json == null || fieldName == null) {
            return null;
        }
        String needle = quote(fieldName);
        String best = null;
        int bestDepth = -1;
        int searchFrom = 0;
        while (true) {
            int field = json.indexOf(needle, searchFrom);
            if (field < 0) {
                return best;
            }
            searchFrom = field + needle.length();
            if (!isKeyPosition(json, field)) {
                continue;
            }
            int colon = skipWhitespaceTo(json, field + needle.length(), ':');
            if (colon < 0) {
                continue;
            }
            int valueStart = skipWhitespace(json, colon + 1);
            if (valueStart >= json.length() || json.charAt(valueStart) != '"') {
                continue;
            }
            int depth = containerDepth(json, field);
            if (depth < bestDepth) {
                continue;
            }
            try {
                String candidate = readQuoted(json, valueStart + 1);
                best = candidate;
                bestDepth = depth;
            } catch (IllegalArgumentException malformed) {
                return best;
            }
        }
    }

    /**
     * Container nesting depth at an index, counting only structural characters.
     *
     * <p>Braces and brackets inside a decoded string are text, not structure, so the scan tracks
     * string state and escapes; otherwise a body that quotes JSON inside a string would report a
     * bogus depth and win a comparison it should lose.
     */
    private static int containerDepth(String json, int index) {
        int depth = 0;
        boolean inString = false;
        for (int position = 0; position < index && position < json.length(); position++) {
            char character = json.charAt(position);
            if (inString) {
                if (character == '\\') {
                    position++;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{' || character == '[') {
                depth++;
            } else if ((character == '}' || character == ']') && depth > 0) {
                depth--;
            }
        }
        return depth;
    }

    /** A quoted token is an object key only when the preceding non-space char opens a container. */
    private static boolean isKeyPosition(String json, int quoteIndex) {
        int previous = quoteIndex - 1;
        while (previous >= 0 && isJsonWhitespace(json.charAt(previous))) {
            previous--;
        }
        if (previous < 0) {
            return false;
        }
        char opener = json.charAt(previous);
        return opener == '{' || opener == ',' || opener == '[';
    }

    private static int skipWhitespaceTo(String value, int index, char expected) {
        int result = skipWhitespace(value, index);
        return result < value.length() && value.charAt(result) == expected ? result : -1;
    }

    private static int skipWhitespace(String value, int index) {
        while (index < value.length() && isJsonWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private static void skipWhitespace(String value, Cursor cursor) {
        cursor.index = skipWhitespace(value, cursor.index);
    }

    /** JSON allows only space, tab, line feed, and carriage return as insignificant whitespace. */
    private static boolean isJsonWhitespace(char character) {
        return character == ' ' || character == '\t' || character == '\n' || character == '\r';
    }

    /** Parses exactly four hex digits, rejecting signs and any other non-hex character. */
    private static int parseHex4(String json, int index) {
        if (index + 4 > json.length()) {
            throw new IllegalArgumentException("Invalid JSON unicode escape");
        }
        for (int offset = 0; offset < 4; offset++) {
            char digit = json.charAt(index + offset);
            boolean hex = (digit >= '0' && digit <= '9')
                    || (digit >= 'a' && digit <= 'f')
                    || (digit >= 'A' && digit <= 'F');
            if (!hex) {
                throw new IllegalArgumentException("Invalid JSON unicode escape");
            }
        }
        return Integer.parseInt(json.substring(index, index + 4), 16);
    }

    /** Mutable character cursor shared by every scan in this class. */
    private static final class Cursor {
        private int index;

        private Cursor(int index) {
            this.index = index;
        }
    }

    /**
     * Convenience overload for callers that keep the cursor in a local variable.
     *
     * @param index position just after the opening quote
     */
    private static String readQuoted(String json, int index) {
        return readQuoted(json, new Cursor(index));
    }

    /**
     * Decodes one string token. This is the only decoder in the class, so a bounded field scan and
     * the tree parser cannot drift on escape handling or on the reported diagnostic.
     *
     * @param cursor positioned just after the opening quote; left just past the closing quote
     */
    private static String readQuoted(String json, Cursor cursor) {
        StringBuilder output = new StringBuilder();
        int index = cursor.index;
        while (index < json.length()) {
            char character = json.charAt(index++);
            if (character == '"') {
                cursor.index = index;
                return output.toString();
            }
            if (character != '\\') {
                output.append(character);
                continue;
            }
            if (index >= json.length()) {
                throw new IllegalArgumentException("Unterminated JSON escape");
            }
            char escaped = json.charAt(index++);
            switch (escaped) {
                case '"': output.append('"'); break;
                case '\\': output.append('\\'); break;
                case '/': output.append('/'); break;
                case 'b': output.append('\b'); break;
                case 'f': output.append('\f'); break;
                case 'n': output.append('\n'); break;
                case 'r': output.append('\r'); break;
                case 't': output.append('\t'); break;
                case 'u':
                    output.append((char) parseHex4(json, index));
                    index += 4;
                    break;
                default: throw new IllegalArgumentException("Invalid JSON escape: " + escaped);
            }
        }
        throw new IllegalArgumentException("Unterminated JSON string");
    }

    private static final class Parser {
        private static final int MAX_DEPTH = 64;
        private final String json;
        private int cursor;

        private Parser(String json) {
            this.json = json;
        }

        private Object parse() {
            Object value = parseValue(0);
            skipWhitespace();
            if (cursor != json.length()) {
                throw new IllegalArgumentException("Unexpected data after JSON response");
            }
            return value;
        }

        private Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("JSON response is nested too deeply");
            }
            skipWhitespace();
            if (cursor >= json.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON response");
            }
            char value = json.charAt(cursor);
            if (value == '{') {
                return parseObject(depth + 1);
            }
            if (value == '[') {
                return parseArray(depth + 1);
            }
            if (value == '"') {
                cursor++;
                return readQuotedValue();
            }
            if (value == 't') {
                expect("true");
                return Boolean.TRUE;
            }
            if (value == 'f') {
                expect("false");
                return Boolean.FALSE;
            }
            if (value == 'n') {
                expect("null");
                return null;
            }
            return parseNumber();
        }

        private Map<String, Object> parseObject(int depth) {
            cursor++;
            LinkedHashMap<String, Object> object = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (consume('}')) {
                return object;
            }
            while (true) {
                skipWhitespace();
                if (!consume('"')) {
                    throw new IllegalArgumentException("JSON object key must be a string");
                }
                String key = readQuotedValue();
                skipWhitespace();
                if (!consume(':')) {
                    throw new IllegalArgumentException("JSON object key is missing a value");
                }
                object.put(key, parseValue(depth));
                skipWhitespace();
                if (consume('}')) {
                    return object;
                }
                if (!consume(',')) {
                    throw new IllegalArgumentException("JSON object is missing a comma");
                }
            }
        }

        private List<Object> parseArray(int depth) {
            cursor++;
            ArrayList<Object> array = new ArrayList<Object>();
            skipWhitespace();
            if (consume(']')) {
                return array;
            }
            while (true) {
                array.add(parseValue(depth));
                skipWhitespace();
                if (consume(']')) {
                    return array;
                }
                if (!consume(',')) {
                    throw new IllegalArgumentException("JSON array is missing a comma");
                }
            }
        }

        /** Reads one string token; the decoder itself lives in {@link JsonStrings#readQuoted}. */
        private String readQuotedValue() {
            Cursor position = new Cursor(cursor);
            String value = readQuoted(json, position);
            cursor = position.index;
            return value;
        }

        /**
         * Reads one number and returns its literal text.
         *
         * <p>Numbers stay text because every consumer of this parser either ignores them or shows
         * the literal; converting to a boxed {@code BigDecimal} would allocate on the streaming hot
         * path and change nothing observable. The grammar is enforced all the same, so a body that
         * is not valid JSON is never accepted.
         */
        private String parseNumber() {
            Cursor position = new Cursor(cursor);
            skipNumber(json, position);
            int start = cursor;
            cursor = position.index;
            return json.substring(start, cursor);
        }

        private void expect(String expected) {
            if (!json.regionMatches(cursor, expected, 0, expected.length())) {
                throw new IllegalArgumentException("Invalid JSON literal");
            }
            cursor += expected.length();
        }

        private boolean consume(char expected) {
            if (cursor < json.length() && json.charAt(cursor) == expected) {
                cursor++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            cursor = JsonStrings.skipWhitespace(json, cursor);
        }
    }
}
