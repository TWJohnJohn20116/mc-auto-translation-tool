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

    /** Reads a string through a small JSON path such as choices[0].message.content. */
    public static String readStringPath(String json, String path) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException("Translation response is empty");
        }
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Response JSON path is required");
        }
        Object current = new Parser(json).parse();
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

    public static String readStringField(String json, String fieldName) {
        String needle = quote(fieldName);
        int searchFrom = 0;
        while (true) {
            int field = json.indexOf(needle, searchFrom);
            if (field < 0) {
                return null;
            }
            int colon = skipWhitespaceTo(json, field + needle.length(), ':');
            if (colon < 0) {
                return null;
            }
            int valueStart = skipWhitespace(json, colon + 1);
            if (valueStart < json.length() && json.charAt(valueStart) == '"') {
                return readQuoted(json, valueStart + 1);
            }
            searchFrom = field + needle.length();
        }
    }

    private static int skipWhitespaceTo(String value, int index, char expected) {
        int result = skipWhitespace(value, index);
        return result < value.length() && value.charAt(result) == expected ? result : -1;
    }

    private static int skipWhitespace(String value, int index) {
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
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
                    if (index + 4 > json.length()) {
                        throw new IllegalArgumentException("Invalid JSON unicode escape");
                    }
                    output.append((char) Integer.parseInt(json.substring(index, index + 4), 16));
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
                        if (cursor + 4 > json.length()) {
                            throw new IllegalArgumentException("Invalid JSON unicode escape");
                        }
                        try {
                            output.append((char) Integer.parseInt(json.substring(cursor, cursor + 4), 16));
                        } catch (NumberFormatException exception) {
                            throw new IllegalArgumentException("Invalid JSON unicode escape", exception);
                        }
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
            while (cursor < json.length() && Character.isWhitespace(json.charAt(cursor))) {
                cursor++;
            }
        }
    }
}
