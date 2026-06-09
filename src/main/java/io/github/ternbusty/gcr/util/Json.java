package io.github.ternbusty.gcr.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Json {
    private Json() {}

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.INDENT_OUTPUT, true);

    public static <T> T readFile(Path path, Class<T> cls) throws IOException {
        return MAPPER.readValue(Files.readAllBytes(path), cls);
    }

    public static void writeFile(Path path, Object value) throws IOException {
        Files.write(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(value));
    }

    public static String encode(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T decode(String s, Class<T> cls) {
        try {
            return MAPPER.readValue(s, cls);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
