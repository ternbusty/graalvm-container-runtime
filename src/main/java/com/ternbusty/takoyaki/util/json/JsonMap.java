package com.ternbusty.takoyaki.util.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Type-safe accessors for the {@link JsonParser}-shaped tree, plus list/map
 * helpers used by the per-class codecs in {@code spec/}, {@code state/},
 * and {@code config/}.
 *
 * <p>The accessors return {@code null} when the key is absent so that the
 * caller can decide whether the field is optional. Primitive variants
 * ({@link #longOr}, {@link #intOr}, {@link #boolOr}) take a default for
 * fields that aren't nullable in the bean.
 *
 * <p>Type mismatches throw {@link IllegalStateException} with a helpful
 * pointer to the offending key — this is the same shape jackson's
 * {@code MismatchedInputException} surfaces, just without the stack.
 */
public final class JsonMap {
    private JsonMap() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object node) {
        if (node == null) return null;
        if (node instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw new IllegalStateException("expected JSON object, got " + typeName(node));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object node) {
        if (node == null) return null;
        if (node instanceof List<?> l) return (List<Object>) l;
        throw new IllegalStateException("expected JSON array, got " + typeName(node));
    }

    public static String str(Map<String, Object> o, String key) {
        Object v = o.get(key);
        if (v == null) return null;
        if (v instanceof String s) return s;
        throw new IllegalStateException("expected string at '" + key + "', got " + typeName(v));
    }

    public static Long longBoxed(Map<String, Object> o, String key) {
        Object v = o.get(key);
        if (v == null) return null;
        if (v instanceof Long l) return l;
        if (v instanceof Number n) return n.longValue();
        throw new IllegalStateException("expected number at '" + key + "', got " + typeName(v));
    }

    public static long longOr(Map<String, Object> o, String key, long def) {
        Long l = longBoxed(o, key);
        return l == null ? def : l;
    }

    public static Integer intBoxed(Map<String, Object> o, String key) {
        Long l = longBoxed(o, key);
        return l == null ? null : l.intValue();
    }

    public static int intOr(Map<String, Object> o, String key, int def) {
        Integer i = intBoxed(o, key);
        return i == null ? def : i;
    }

    public static Boolean boolBoxed(Map<String, Object> o, String key) {
        Object v = o.get(key);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        throw new IllegalStateException("expected boolean at '" + key + "', got " + typeName(v));
    }

    public static boolean boolOr(Map<String, Object> o, String key, boolean def) {
        Boolean b = boolBoxed(o, key);
        return b == null ? def : b;
    }

    public static List<String> strList(Map<String, Object> o, String key) {
        List<Object> a = asArray(o.get(key));
        if (a == null) return null;
        List<String> r = new ArrayList<>(a.size());
        for (Object v : a) {
            if (v != null && !(v instanceof String)) {
                throw new IllegalStateException("expected string in '" + key + "' array, got " + typeName(v));
            }
            r.add((String) v);
        }
        return r;
    }

    public static List<Integer> intList(Map<String, Object> o, String key) {
        List<Object> a = asArray(o.get(key));
        if (a == null) return null;
        List<Integer> r = new ArrayList<>(a.size());
        for (Object v : a) {
            if (v instanceof Number n) r.add(n.intValue());
            else throw new IllegalStateException("expected int in '" + key + "' array, got " + typeName(v));
        }
        return r;
    }

    public static <T> List<T> list(Map<String, Object> o, String key, Function<Object, T> mapper) {
        List<Object> a = asArray(o.get(key));
        if (a == null) return null;
        List<T> r = new ArrayList<>(a.size());
        for (Object v : a) r.add(mapper.apply(v));
        return r;
    }

    public static Map<String, String> strMap(Map<String, Object> o, String key) {
        Map<String, Object> in = asObject(o.get(key));
        if (in == null) return null;
        Map<String, String> r = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            Object v = e.getValue();
            if (v != null && !(v instanceof String)) {
                throw new IllegalStateException("expected string in '" + key + "' map, got " + typeName(v));
            }
            r.put(e.getKey(), (String) v);
        }
        return r;
    }

    public static <T> Map<String, T> map(Map<String, Object> o, String key, Function<Object, T> mapper) {
        Map<String, Object> in = asObject(o.get(key));
        if (in == null) return null;
        Map<String, T> r = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            r.put(e.getKey(), mapper.apply(e.getValue()));
        }
        return r;
    }

    /** Builds a Map ready for {@link JsonWriter}; null values are skipped. */
    public static Map<String, Object> obj() {
        return new LinkedHashMap<>();
    }

    /** {@code put} that drops null values — gives us jackson NON_NULL semantics. */
    public static void put(Map<String, Object> o, String key, Object value) {
        if (value != null) o.put(key, value);
    }

    /** Like {@link #put} but always emits the key (used for `false` etc. that we want explicit). */
    public static void putAlways(Map<String, Object> o, String key, Object value) {
        o.put(key, value);
    }

    /** Encodes a list of bean-backed objects via their {@code toJson} method. */
    public static <T> List<Object> encList(List<T> items, Function<T, Object> mapper) {
        if (items == null) return null;
        List<Object> r = new ArrayList<>(items.size());
        for (T item : items) r.add(mapper.apply(item));
        return r;
    }

    private static String typeName(Object v) {
        if (v == null) return "null";
        return v.getClass().getSimpleName();
    }
}
