package org.universaltranslator.core.net;

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
}
