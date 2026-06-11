package com.ternbusty.takoyaki.util;

import com.ternbusty.takoyaki.syscall.Fs;
import com.ternbusty.takoyaki.util.json.JsonParser;
import com.ternbusty.takoyaki.util.json.JsonWriter;

import java.io.IOException;
import java.util.function.Function;

/**
 * Thin facade over {@link JsonParser} and {@link JsonWriter} matched to
 * the callsites in takoyaki.
 *
 * <p>String paths only — we sidestep {@link java.nio.file.Path} so the
 * {@code java.nio.file.FileSystemProvider} chain stays out of the image.
 */
public final class Json {
    private Json() {}

    public static <T> T readFile(String path, Function<Object, T> fromJson) throws IOException {
        return fromJson.apply(JsonParser.parse(Fs.readAllBytes(path)));
    }

    public static void writeFile(String path, Object jsonTree) throws IOException {
        Fs.writeString(path, JsonWriter.toPretty(jsonTree));
    }

    public static String encode(Object jsonTree) {
        return JsonWriter.toPretty(jsonTree);
    }

    public static <T> T decode(String s, Function<Object, T> fromJson) {
        return fromJson.apply(JsonParser.parse(s));
    }
}
