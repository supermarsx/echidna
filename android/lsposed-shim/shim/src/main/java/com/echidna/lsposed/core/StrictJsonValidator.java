package com.echidna.lsposed.core;

import java.util.HashSet;
import java.util.Set;

/** Escape-aware duplicate-key and Unicode pre-scan for org.json, which collapses duplicates. */
final class StrictJsonValidator {

    private StrictJsonValidator() {
    }

    static boolean isSafe(String json) {
        return json != null && new Scanner(json).readDocument();
    }

    static boolean isWellFormedUtf16(String value) {
        if (value == null) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    private static final class Scanner {
        private static final int MAX_DEPTH = 128;
        private final String text;
        private int index;

        Scanner(String text) {
            this.text = text;
        }

        boolean readDocument() {
            if (!isWellFormedUtf16(text)) {
                return false;
            }
            skipWhitespace();
            if (!readValue(0)) {
                return false;
            }
            skipWhitespace();
            return index == text.length();
        }

        private boolean readValue(int depth) {
            if (depth > MAX_DEPTH) {
                return false;
            }
            skipWhitespace();
            if (index >= text.length()) {
                return false;
            }
            switch (text.charAt(index)) {
                case '{':
                    return readObject(depth + 1);
                case '[':
                    return readArray(depth + 1);
                case '"':
                    return readString() != null;
                default:
                    return readPrimitive();
            }
        }

        private boolean readObject(int depth) {
            index++;
            skipWhitespace();
            if (consume('}')) {
                return true;
            }
            Set<String> keys = new HashSet<>();
            while (index < text.length()) {
                skipWhitespace();
                String key = readString();
                if (key == null || !keys.add(key)) {
                    return false;
                }
                skipWhitespace();
                if (!consume(':') || !readValue(depth)) {
                    return false;
                }
                skipWhitespace();
                if (consume('}')) {
                    return true;
                }
                if (!consume(',')) {
                    return false;
                }
            }
            return false;
        }

        private boolean readArray(int depth) {
            index++;
            skipWhitespace();
            if (consume(']')) {
                return true;
            }
            while (index < text.length()) {
                if (!readValue(depth)) {
                    return false;
                }
                skipWhitespace();
                if (consume(']')) {
                    return true;
                }
                if (!consume(',')) {
                    return false;
                }
            }
            return false;
        }

        private String readString() {
            if (!consume('"')) {
                return null;
            }
            StringBuilder value = new StringBuilder();
            while (index < text.length()) {
                char current = text.charAt(index++);
                if (current == '"') {
                    String decoded = value.toString();
                    return isWellFormedUtf16(decoded) ? decoded : null;
                }
                if (current < 0x20) {
                    return null;
                }
                if (current != '\\') {
                    value.append(current);
                    continue;
                }
                if (index >= text.length()) {
                    return null;
                }
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"':
                    case '\\':
                    case '/':
                        value.append(escaped);
                        break;
                    case 'b':
                        value.append('\b');
                        break;
                    case 'f':
                        value.append('\f');
                        break;
                    case 'n':
                        value.append('\n');
                        break;
                    case 'r':
                        value.append('\r');
                        break;
                    case 't':
                        value.append('\t');
                        break;
                    case 'u':
                        if (index + 4 > text.length()) {
                            return null;
                        }
                        int code = parseHex(text, index);
                        if (code < 0) {
                            return null;
                        }
                        value.append((char) code);
                        index += 4;
                        break;
                    default:
                        return null;
                }
            }
            return null;
        }

        private boolean readPrimitive() {
            int start = index;
            while (index < text.length()) {
                char current = text.charAt(index);
                if (current == ',' || current == ']' || current == '}' || isJsonWhitespace(current)) {
                    break;
                }
                index++;
            }
            if (index <= start) {
                return false;
            }
            String primitive = text.substring(start, index);
            return "true".equals(primitive)
                    || "false".equals(primitive)
                    || "null".equals(primitive)
                    || isJsonNumber(primitive);
        }

        private static boolean isJsonNumber(String value) {
            int cursor = 0;
            if (value.charAt(cursor) == '-') {
                cursor++;
                if (cursor == value.length()) {
                    return false;
                }
            }

            if (value.charAt(cursor) == '0') {
                cursor++;
            } else if (value.charAt(cursor) >= '1' && value.charAt(cursor) <= '9') {
                do {
                    cursor++;
                } while (cursor < value.length()
                        && value.charAt(cursor) >= '0'
                        && value.charAt(cursor) <= '9');
            } else {
                return false;
            }

            if (cursor < value.length() && value.charAt(cursor) == '.') {
                cursor++;
                int fractionStart = cursor;
                while (cursor < value.length()
                        && value.charAt(cursor) >= '0'
                        && value.charAt(cursor) <= '9') {
                    cursor++;
                }
                if (cursor == fractionStart) {
                    return false;
                }
            }

            if (cursor < value.length()
                    && (value.charAt(cursor) == 'e' || value.charAt(cursor) == 'E')) {
                cursor++;
                if (cursor < value.length()
                        && (value.charAt(cursor) == '+' || value.charAt(cursor) == '-')) {
                    cursor++;
                }
                int exponentStart = cursor;
                while (cursor < value.length()
                        && value.charAt(cursor) >= '0'
                        && value.charAt(cursor) <= '9') {
                    cursor++;
                }
                if (cursor == exponentStart) {
                    return false;
                }
            }
            return cursor == value.length();
        }

        private boolean consume(char expected) {
            if (index >= text.length() || text.charAt(index) != expected) {
                return false;
            }
            index++;
            return true;
        }

        private void skipWhitespace() {
            while (index < text.length() && isJsonWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private static boolean isJsonWhitespace(char value) {
            return value == ' ' || value == '\t' || value == '\r' || value == '\n';
        }

        private static int parseHex(String value, int offset) {
            int result = 0;
            for (int index = offset; index < offset + 4; index++) {
                int digit = Character.digit(value.charAt(index), 16);
                if (digit < 0) {
                    return -1;
                }
                result = (result << 4) | digit;
            }
            return result;
        }
    }
}
