package com.ternbusty.takoyaki.util.json;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser ↔ writer roundtrip + spot-checks. We don't aim to be RFC-8259
 * exhaustive; the OCI conformance suite (driven via contestTest) is the
 * functional regression net. These tests catch obvious bugs and pin down
 * the few format choices that downstream tooling depends on.
 */
class JsonRoundtripTest {

    @Test
    void primitivesRoundtripCorrectTypes() {
        assertEquals("hello", JsonParser.parse("\"hello\""));
        assertEquals(42L, JsonParser.parse("42"));
        assertEquals(-1L, JsonParser.parse("-1"));
        assertEquals(3.14, (Double) JsonParser.parse("3.14"), 1e-9);
        assertEquals(Boolean.TRUE, JsonParser.parse("true"));
        assertEquals(Boolean.FALSE, JsonParser.parse("false"));
        assertNull(JsonParser.parse("null"));
    }

    @Test
    void emptyContainers() {
        assertEquals(Map.of(), JsonParser.parse("{}"));
        assertEquals(List.of(), JsonParser.parse("[]"));
    }

    @Test
    void objectPreservesKeyOrder() {
        Map<String, Object> m = cast(JsonParser.parse("{\"z\":1,\"a\":2,\"m\":3}"));
        assertEquals(List.of("z", "a", "m"), m.keySet().stream().toList(),
                "downstream tools (runc-compat output) expect insertion order");
    }

    @Test
    void nestedStructure() {
        String json = "{\"a\":[1,2,{\"b\":\"c\"}]}";
        Map<String, Object> m = cast(JsonParser.parse(json));
        List<?> a = (List<?>) m.get("a");
        assertEquals(3, a.size());
        assertEquals(1L, a.get(0));
        Map<?, ?> inner = (Map<?, ?>) a.get(2);
        assertEquals("c", inner.get("b"));
    }

    @Test
    void stringEscapes() {
        assertEquals("a\"b", JsonParser.parse("\"a\\\"b\""));
        assertEquals("a\\b", JsonParser.parse("\"a\\\\b\""));
        assertEquals("line1\nline2", JsonParser.parse("\"line1\\nline2\""));
        // backslash-u escape
        assertEquals("é", JsonParser.parse("\"\\u00e9\""));
    }

    @Test
    void writerEmitsValidJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "ctr-a");
        m.put("pid", 4242L);
        m.put("running", true);
        m.put("missing", null);
        String s = JsonWriter.toPretty(m);
        // re-parse to verify it's valid
        Map<String, Object> back = cast(JsonParser.parse(s));
        assertEquals("ctr-a", back.get("name"));
        assertEquals(4242L, back.get("pid"));
        assertEquals(Boolean.TRUE, back.get("running"));
        assertTrue(back.containsKey("missing"));
        assertNull(back.get("missing"));
    }

    @Test
    void writerKeepsIntegersAsIntegers() {
        // pid: 4242 should NOT come out as 4242.0 — downstream tooling
        // (containerd, jq) parses it back as float otherwise.
        assertEquals("4242", JsonWriter.toCompact(4242L));
        assertEquals("4242", JsonWriter.toCompact(4242));
        assertEquals("0", JsonWriter.toCompact(0L));
        assertEquals("-1", JsonWriter.toCompact(-1L));
    }

    @Test
    void writerEscapesControlChars() {
        assertEquals("\"a\\nb\"", JsonWriter.toCompact("a\nb"));
        assertEquals("\"a\\\"b\"", JsonWriter.toCompact("a\"b"));
        assertEquals("\"a\\\\b\"", JsonWriter.toCompact("a\\b"));
    }

    @Test
    void deepRoundtripPreservesAll() {
        String original = """
                {
                  "ociVersion" : "1.0.0",
                  "root" : { "path" : "rootfs", "readonly" : false },
                  "process" : {
                    "args" : ["/bin/sh", "-c", "echo hi"],
                    "cwd" : "/",
                    "user" : { "uid" : 0, "gid" : 0 }
                  },
                  "mounts" : [
                    { "destination" : "/proc", "type" : "proc", "source" : "proc" }
                  ]
                }""";
        Object tree = JsonParser.parse(original);
        String reEmitted = JsonWriter.toPretty(tree);
        Object reTree = JsonParser.parse(reEmitted);
        // Structural equality — we don't check string match because formatting
        // can legitimately differ (spaces, trailing newline).
        assertEquals(tree, reTree);
    }

    @Test
    void parserRejectsTrailingGarbage() {
        assertThrows(IllegalStateException.class, () -> JsonParser.parse("42 garbage"));
    }

    @Test
    void parserRejectsUnterminatedString() {
        assertThrows(IllegalStateException.class, () -> JsonParser.parse("\"oops"));
    }

    @Test
    void parserRejectsUnknownKeyword() {
        assertThrows(IllegalStateException.class, () -> JsonParser.parse("yes"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }
}
