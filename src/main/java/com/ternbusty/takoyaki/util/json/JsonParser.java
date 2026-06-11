package com.ternbusty.takoyaki.util.json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal recursive-descent JSON parser.
 *
 * <p>Parses input into a tree of:
 * <ul>
 *   <li>{@link Map}&lt;String, Object&gt; for JSON objects (insertion-ordered)</li>
 *   <li>{@link List}&lt;Object&gt; for JSON arrays</li>
 *   <li>{@link String} for JSON strings</li>
 *   <li>{@link Long} for integral numbers, {@link Double} for fractional or out-of-range</li>
 *   <li>{@link Boolean} for true/false</li>
 *   <li>{@code null} for JSON null</li>
 * </ul>
 *
 * <p>Why hand-rolled: jackson-databind brings in ~3,000 reachable methods,
 * ~2.6 MB of code, and transitively ~4.6 MB of java.xml at native-image
 * build time. takoyaki only ever parses OCI spec / state JSON — small,
 * well-typed schemas. A 300-line parser is plenty.
 *
 * <p>Not goals: streaming, performance for huge files, lenient JSON5,
 * comments, trailing commas. The OCI conformance test corpus is the
 * spec we care about.
 */
public final class JsonParser {
    private final String src;
    private int pos;

    private JsonParser(String src) {
        this.src = src;
    }

    public static Object parse(String s) {
        JsonParser p = new JsonParser(s);
        Object v = p.readValue();
        p.skipWs();
        if (p.pos != p.src.length()) {
            throw p.error("trailing data after value");
        }
        return v;
    }

    public static Object parse(byte[] bytes) {
        return parse(new String(bytes, StandardCharsets.UTF_8));
    }

    private Object readValue() {
        skipWs();
        if (pos >= src.length()) throw error("unexpected EOF");
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readKeyword("true", Boolean.TRUE);
            case 'f' -> readKeyword("false", Boolean.FALSE);
            case 'n' -> readKeyword("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        pos++; // consume '{'
        Map<String, Object> m = new LinkedHashMap<>();
        skipWs();
        if (peek('}')) { pos++; return m; }
        while (true) {
            skipWs();
            if (!peek('"')) throw error("expected string key");
            String k = readString();
            skipWs();
            if (!peek(':')) throw error("expected ':' after key");
            pos++;
            m.put(k, readValue());
            skipWs();
            if (peek(',')) { pos++; continue; }
            if (peek('}')) { pos++; return m; }
            throw error("expected ',' or '}' in object");
        }
    }

    private List<Object> readArray() {
        pos++; // consume '['
        List<Object> a = new ArrayList<>();
        skipWs();
        if (peek(']')) { pos++; return a; }
        while (true) {
            a.add(readValue());
            skipWs();
            if (peek(',')) { pos++; continue; }
            if (peek(']')) { pos++; return a; }
            throw error("expected ',' or ']' in array");
        }
    }

    private String readString() {
        if (!peek('"')) throw error("expected '\"'");
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (pos >= src.length()) throw error("bad escape at EOF");
                char e = src.charAt(pos++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (pos + 4 > src.length()) throw error("bad \\u escape");
                        int code = Integer.parseInt(src.substring(pos, pos + 4), 16);
                        sb.append((char) code);
                        pos += 4;
                    }
                    default -> throw error("bad escape '\\" + e + "'");
                }
            } else {
                sb.append(c);
            }
        }
        throw error("unterminated string");
    }

    private Object readKeyword(String kw, Object val) {
        if (pos + kw.length() > src.length() || !src.startsWith(kw, pos)) {
            throw error("expected '" + kw + "'");
        }
        pos += kw.length();
        return val;
    }

    private Object readNumber() {
        int start = pos;
        boolean fractional = false;
        if (pos < src.length() && src.charAt(pos) == '-') pos++;
        int digitsStart = pos;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c >= '0' && c <= '9') { pos++; continue; }
            if (c == '.' || c == 'e' || c == 'E') {
                fractional = true; pos++; continue;
            }
            if (fractional && (c == '+' || c == '-')) { pos++; continue; }
            break;
        }
        // Require at least one digit. Without this, bareword input like
        // "yes" falls into readNumber() (default in readValue switch) and
        // Double.parseDouble("") throws NumberFormatException — which masks
        // the real "unknown token" message.
        if (pos == digitsStart) {
            throw error("expected JSON value, got '" + src.charAt(start) + "'");
        }
        String s = src.substring(start, pos);
        if (fractional) return Double.parseDouble(s);
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException ex) {
            return Double.parseDouble(s);
        }
    }

    private void skipWs() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
            else break;
        }
    }

    private boolean peek(char c) {
        return pos < src.length() && src.charAt(pos) == c;
    }

    private IllegalStateException error(String msg) {
        return new IllegalStateException("json parse: " + msg + " at pos " + pos);
    }
}
