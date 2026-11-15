package com.pulseforge.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON parser/serializer.
 * <p>
 * Objects map to {@code LinkedHashMap<String,Object>}, arrays to {@code List<Object>},
 * numbers to {@code Double}, plus {@code String}, {@code Boolean} and {@code null}.
 * Used for persistence, the Garmin adapter and for decoding structured LLM output.
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------ parse

    public static Object parse(String text) {
        if (text == null) {
            throw new JsonException("Cannot parse null JSON text");
        }
        Parser p = new Parser(text);
        p.skipWs();
        Object value = p.readValue();
        p.skipWs();
        if (!p.atEnd()) {
            throw new JsonException("Unexpected trailing content at position " + p.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object o = parse(text);
        if (!(o instanceof Map)) {
            throw new JsonException("Expected a JSON object");
        }
        return (Map<String, Object>) o;
    }

    /**
     * LLMs frequently wrap JSON in markdown fences or add prose around it. This extracts the
     * first balanced JSON object/array from arbitrary text and parses it.
     */
    public static Object parseLenient(String text) {
        if (text == null) {
            throw new JsonException("LLM returned no content");
        }
        String trimmed = text.trim();
        try {
            return parse(trimmed);
        } catch (JsonException ignored) {
            // fall through to extraction
        }
        int start = -1;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '{' || c == '[') {
                start = i;
                break;
            }
        }
        if (start < 0) {
            throw new JsonException("No JSON object found in model output");
        }
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    return parse(trimmed.substring(start, i + 1));
                }
            }
        }
        throw new JsonException("Unbalanced JSON in model output");
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObjectLenient(String text) {
        Object o = parseLenient(text);
        if (!(o instanceof Map)) {
            throw new JsonException("Expected a JSON object in model output");
        }
        return (Map<String, Object>) o;
    }

    // ---------------------------------------------------------------- helpers

    public static String str(Map<String, Object> m, String key, String def) {
        Object v = m == null ? null : m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    public static Double num(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            // Accept "8-12" style ranges by taking the upper bound, and strip units like "300 kcal".
            if (s.matches("\\d+(\\.\\d+)?\\s*-\\s*\\d+(\\.\\d+)?.*")) {
                s = s.replaceAll("^\\s*\\d+(\\.\\d+)?\\s*-\\s*", "");
            }
            s = s.replaceAll("[^0-9.\\-]", "");
            if (!s.isEmpty() && !s.equals("-") && !s.equals(".")) {
                try {
                    return Double.parseDouble(s);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public static double num(Map<String, Object> m, String key, double def) {
        Double d = num(m, key);
        return d == null ? def : d;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objArr(Map<String, Object> m, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : arr(m, key)) {
            if (o instanceof Map) {
                out.add((Map<String, Object>) o);
            }
        }
        return out;
    }

    // -------------------------------------------------------------- stringify

    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, false, 0);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, true, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, boolean pretty, int indent) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(sb, pretty, indent + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(pretty ? ": " : ":");
                write(sb, e.getValue(), pretty, indent + 1);
            }
            newline(sb, pretty, indent);
            sb.append('}');
        } else if (v instanceof Iterable) {
            Iterable<?> it = (Iterable<?>) v;
            if (!it.iterator().hasNext()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                newline(sb, pretty, indent + 1);
                write(sb, o, pretty, indent + 1);
            }
            newline(sb, pretty, indent);
            sb.append(']');
        } else {
            quote(sb, v.toString());
        }
    }

    private static void newline(StringBuilder sb, boolean pretty, int indent) {
        if (pretty) {
            sb.append('\n');
            sb.append("  ".repeat(indent));
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Convenience builder for JSON objects. */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    // ----------------------------------------------------------------- parser

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        Object readValue() {
            if (atEnd()) {
                throw new JsonException("Unexpected end of JSON");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> readNumber();
            };
        }

        Object literal(String word, Object value) {
            if (!s.startsWith(word, pos)) {
                throw new JsonException("Invalid literal at position " + pos);
            }
            pos += word.length();
            return value;
        }

        Map<String, Object> readObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw new JsonException("Expected string key at position " + pos);
                }
                String key = readString();
                skipWs();
                expect(':');
                skipWs();
                m.put(key, readValue());
                skipWs();
                char c = next();
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw new JsonException("Expected ',' or '}' at position " + (pos - 1));
                }
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(readValue());
                skipWs();
                char c = next();
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new JsonException("Expected ',' or ']' at position " + (pos - 1));
                }
            }
        }

        String readString() {
            pos++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonException("Unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw new JsonException("Bad unicode escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new JsonException("Bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Double readNumber() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            if (start == pos) {
                throw new JsonException("Unexpected character '" + s.charAt(pos) + "' at position " + pos);
            }
            try {
                return Double.parseDouble(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw new JsonException("Invalid number at position " + start);
            }
        }

        char peek() {
            if (atEnd()) {
                throw new JsonException("Unexpected end of JSON");
            }
            return s.charAt(pos);
        }

        char next() {
            char c = peek();
            pos++;
            return c;
        }

        void expect(char c) {
            if (next() != c) {
                throw new JsonException("Expected '" + c + "' at position " + (pos - 1));
            }
        }
    }
}
