package org.universaltranslator.core.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        return new Parser(json).parse();
    }

    /** Reads a string through a small JSON path such as choices[0].message.content. */
    public static String readStringPath(String json, String path) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("Translation response is empty");
        }
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Response JSON path is required");
        }
        Object current = parse(json);
        String normalized = path.trim();
        if (normalized.startsWith("$.")) {
            normalized = normalized.substring(2);
        } else if ("$".equals(normalized)) {
            normalized = "";
        }
        int cursor = 0;
        while (cursor < normalized.length()) {
            int nameStart = cursor;
            while (cursor < normalized.length()
                    && normalized.charAt(cursor) != '.'
                    && normalized.charAt(cursor) != '[') {
                cursor++;
            }
            if (cursor > nameStart) {
                if (!(current instanceof Map)) {
                    return null;
                }
                current = ((Map<?, ?>) current).get(normalized.substring(nameStart, cursor));
            }
            while (cursor < normalized.length() && normalized.charAt(cursor) == '[') {
                int close = normalized.indexOf(']', cursor + 1);
                if (close < 0 || !(current instanceof List)) {
                    return null;
                }
                int index;
                try {
                    index = Integer.parseInt(normalized.substring(cursor + 1, close));
                } catch (NumberFormatException exception) {
                    return null;
                }
                List<?> list = (List<?>) current;
                if (index < 0 || index >= list.size()) {
                    return null;
                }
                current = list.get(index);
                cursor = close + 1;
            }
            if (cursor < normalized.length()) {
                if (normalized.charAt(cursor) != '.') {
                    return null;
                }
                cursor++;
            }
            if (current == null) {
                return null;
            }
        }
        return current instanceof String ? (String) current : null;
    }

    /**
     * Best-effort lookup of a string field. Returns null instead of throwing for any malformed or
     * truncated body, and only accepts matches at an object-key position.
     */
    public static String readStringField(String json, String fieldName) {
        if (json == null || fieldName == null) {
            return null;
        }
        String needle = quote(fieldName);
        int searchFrom = 0;
        while (true) {
            int field = json.indexOf(needle, searchFrom);
            if (field < 0) {
                return null;
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
            try {
                return readQuoted(json, valueStart + 1);
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }
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

    private static String readQuoted(String json, int index) {
        StringBuilder output = new StringBuilder();
        while (index < json.length()) {
            char character = json.charAt(index++);
            if (character == '"') {
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

        private String readQuotedValue() {
            StringBuilder output = new StringBuilder();
            while (cursor < json.length()) {
                char character = json.charAt(cursor++);
                if (character == '"') {
                    return output.toString();
                }
                if (character != '\\') {
                    output.append(character);
                    continue;
                }
                if (cursor >= json.length()) {
                    throw new IllegalArgumentException("Unterminated JSON escape");
                }
                char escaped = json.charAt(cursor++);
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
                        output.append((char) parseHex4(json, cursor));
                        cursor += 4;
                        break;
                    default: throw new IllegalArgumentException("Invalid JSON escape");
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private String parseNumber() {
            int start = cursor;
            while (cursor < json.length()) {
                char character = json.charAt(cursor);
                if ((character >= '0' && character <= '9') || character == '-'
                        || character == '+' || character == '.' || character == 'e' || character == 'E') {
                    cursor++;
                } else {
                    break;
                }
            }
            if (cursor == start) {
                throw new IllegalArgumentException("Invalid JSON value");
            }
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
            while (cursor < json.length() && isJsonWhitespace(json.charAt(cursor))) {
                cursor++;
            }
        }
    }
}
