package io.github.ternbusty.gcr.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.ternbusty.gcr.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class KontainerConfig {
    public String cgroupPath;

    public KontainerConfig() {}

    public KontainerConfig(String cgroupPath) {
        this.cgroupPath = cgroupPath;
    }

    public static Path path(String rootPath, String containerId) {
        return Path.of(rootPath, containerId, "config.json");
    }

    public void save(String rootPath, String containerId) throws IOException {
        Path p = path(rootPath, containerId);
        Files.createDirectories(p.getParent());
        Json.writeFile(p, this);
    }

    public static KontainerConfig load(String rootPath, String containerId) throws IOException {
        return Json.readFile(path(rootPath, containerId), KontainerConfig.class);
    }
}
