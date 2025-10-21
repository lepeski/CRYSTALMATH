package dev.crystalmath.claims.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SimpleJson {
    private SimpleJson() {
    }

    public static Object parse(String json) throws IOException {
        if (json == null) {
            return null;
        }
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.isEnd()) {
            throw new IOException("Trailing data in JSON");
        }
        return value;
    }

    public static String stringify(Object value) {
        StringBuilder builder = new StringBuilder();
        appendValue(builder, value);
        return builder.toString();
    }

    private static void appendValue(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
        } else if (value instanceof String string) {
            appendString(builder, string);
        } else if (value instanceof Number || value instanceof Boolean) {
            builder.append(value.toString());
        } else if (value instanceof Map<?, ?> map) {
            builder.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                appendString(builder, entry.getKey().toString());
                builder.append(':');
                appendValue(builder, entry.getValue());
            }
            builder.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            builder.append('[');
            boolean first = true;
            for (Object element : iterable) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                appendValue(builder, element);
            }
            builder.append(']');
        } else {
            appendString(builder, value.toString());
        }
    }

    private static void appendString(StringBuilder builder, String value) {
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                case '\\':
                    builder.append('\\').append(c);
                    break;
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
            }
        }
        builder.append('"');
    }

    private static final class Parser {
        private final String json;
        private int index;

        private Parser(String json) {
            this.json = json;
        }

        private Object parseValue() throws IOException {
            skipWhitespace();
            if (isEnd()) {
                return null;
            }
            char c = json.charAt(index);
            return switch (c) {
                case '"' -> parseString();
                case '{' -> parseObject();
                case '[' -> parseArray();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() throws IOException {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                expect('}');
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    expect('}');
                    break;
                }
                expect(',');
            }
            return map;
        }

        private List<Object> parseArray() throws IOException {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                expect(']');
                return list;
            }
            while (true) {
                Object value = parseValue();
                list.add(value);
                skipWhitespace();
                if (peek(']')) {
                    expect(']');
                    break;
                }
                expect(',');
            }
            return list;
        }

        private String parseString() throws IOException {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (!isEnd()) {
                char c = json.charAt(index++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c == '\\') {
                    if (isEnd()) {
                        throw new IOException("Unterminated escape sequence");
                    }
                    char esc = json.charAt(index++);
                    switch (esc) {
                        case '"', '\\', '/' -> builder.append(esc);
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> {
                            if (index + 4 > json.length()) {
                                throw new IOException("Invalid unicode escape");
                            }
                            String hex = json.substring(index, index + 4);
                            index += 4;
                            try {
                                int code = Integer.parseInt(hex, 16);
                                builder.append((char) code);
                            } catch (NumberFormatException ex) {
                                throw new IOException("Invalid unicode escape", ex);
                            }
                        }
                        default -> throw new IOException("Invalid escape character: " + esc);
                    }
                } else {
                    builder.append(c);
                }
            }
            throw new IOException("Unterminated string");
        }

        private Object parseNumber() throws IOException {
            int start = index;
            if (json.charAt(index) == '-') {
                index++;
            }
            while (!isEnd() && Character.isDigit(json.charAt(index))) {
                index++;
            }
            if (!isEnd() && json.charAt(index) == '.') {
                index++;
                while (!isEnd() && Character.isDigit(json.charAt(index))) {
                    index++;
                }
            }
            if (!isEnd() && (json.charAt(index) == 'e' || json.charAt(index) == 'E')) {
                index++;
                if (!isEnd() && (json.charAt(index) == '+' || json.charAt(index) == '-')) {
                    index++;
                }
                while (!isEnd() && Character.isDigit(json.charAt(index))) {
                    index++;
                }
            }
            String number = json.substring(start, index);
            try {
                if (number.contains(".") || number.contains("e") || number.contains("E")) {
                    return Double.parseDouble(number);
                }
                long longValue = Long.parseLong(number);
                if (longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
                    return (int) longValue;
                }
                return longValue;
            } catch (NumberFormatException ex) {
                throw new IOException("Invalid number: " + number, ex);
            }
        }

        private Object parseLiteral(String literal, Object value) throws IOException {
            if (json.regionMatches(index, literal, 0, literal.length())) {
                index += literal.length();
                return value;
            }
            throw new IOException("Unexpected token at position " + index);
        }

        private void skipWhitespace() {
            while (!isEnd() && Character.isWhitespace(json.charAt(index))) {
                index++;
            }
        }

        private void expect(char expected) throws IOException {
            if (isEnd() || json.charAt(index) != expected) {
                throw new IOException("Expected '" + expected + "' at position " + index);
            }
            index++;
        }

        private boolean peek(char value) {
            return !isEnd() && json.charAt(index) == value;
        }

        private boolean isEnd() {
            return index >= json.length();
        }
    }
}
