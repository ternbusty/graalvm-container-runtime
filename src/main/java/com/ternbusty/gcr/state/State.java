package com.ternbusty.gcr.state;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ternbusty.gcr.logger.Logger;
import com.ternbusty.gcr.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class State {
    public String ociVersion;
    public String id;
    public String status;
    public Integer pid;
    public String bundle;
    public Map<String, String> annotations;
    public String created;

    public State() {}

    public State(String ociVersion, String id, ContainerStatus status,
                 Integer pid, String bundle, Map<String, String> annotations, String created) {
        this.ociVersion = ociVersion;
        this.id = id;
        this.status = status.value;
        this.pid = pid;
        this.bundle = bundle;
        this.annotations = annotations;
        this.created = created;
    }

    public ContainerStatus statusEnum() {
        return ContainerStatus.fromString(status);
    }

    public State withStatus(ContainerStatus s) {
        return new State(ociVersion, id, s, pid, bundle, annotations, created);
    }

    public static Path containerDir(String rootPath, String containerId) {
        return Path.of(rootPath, containerId);
    }

    public static Path statePath(String rootPath, String containerId) {
        return containerDir(rootPath, containerId).resolve("state.json");
    }

    public static boolean exists(String rootPath, String containerId) {
        return Files.exists(statePath(rootPath, containerId));
    }

    public static State load(String rootPath, String containerId) throws IOException {
        Path p = statePath(rootPath, containerId);
        Logger.debug("loading state from " + p);
        return Json.readFile(p, State.class);
    }

    public void save(String rootPath) throws IOException {
        Path dir = containerDir(rootPath, id);
        Files.createDirectories(dir);
        Path p = dir.resolve("state.json");
        Logger.debug("saving state to " + p);
        Json.writeFile(p, this);
    }

    public static State create(String ociVersion, String containerId,
                               ContainerStatus status, Integer pid, String bundle,
                               Map<String, String> annotations) {
        String created = OffsetDateTime.now(ZoneOffset.UTC)
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'"));
        return new State(ociVersion, containerId, status, pid, bundle, annotations, created);
    }

    public State refreshStatus() {
        if (pid == null) {
            return statusEnum() == ContainerStatus.STOPPED ? this : withStatus(ContainerStatus.STOPPED);
        }
        if (!isProcessAlive(pid)) {
            return statusEnum() == ContainerStatus.STOPPED ? this : withStatus(ContainerStatus.STOPPED);
        }
        return this;
    }

    private static boolean isProcessAlive(int pid) {
        Path stat = Path.of("/proc", String.valueOf(pid), "stat");
        if (!Files.exists(stat)) return false;
        try {
            String content = Files.readString(stat);
            int lp = content.lastIndexOf(')');
            if (lp < 0 || lp + 2 >= content.length()) return false;
            char st = content.charAt(lp + 2);
            return st != 'Z' && st != 'X';
        } catch (IOException e) {
            return false;
        }
    }
}
