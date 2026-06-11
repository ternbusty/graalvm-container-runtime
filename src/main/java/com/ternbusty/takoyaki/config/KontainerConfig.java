package com.ternbusty.takoyaki.config;

import com.ternbusty.takoyaki.syscall.Fs;
import com.ternbusty.takoyaki.util.Json;
import com.ternbusty.takoyaki.util.json.JsonMap;

import java.io.IOException;
import java.util.Map;

public final class KontainerConfig {
    public String cgroupPath;

    public KontainerConfig() {}

    public KontainerConfig(String cgroupPath) {
        this.cgroupPath = cgroupPath;
    }

    public static String path(String rootPath, String containerId) {
        return rootPath + "/" + containerId + "/config.json";
    }

    public void save(String rootPath, String containerId) throws IOException {
        String p = path(rootPath, containerId);
        Fs.createDirectories(Fs.parent(p));
        Json.writeFile(p, toJson());
    }

    public static KontainerConfig load(String rootPath, String containerId) throws IOException {
        return Json.readFile(path(rootPath, containerId), KontainerConfig::fromJson);
    }

    public static KontainerConfig fromJson(Object node) {
        if (node == null) return null;
        Map<String, Object> o = JsonMap.asObject(node);
        KontainerConfig c = new KontainerConfig();
        c.cgroupPath = JsonMap.str(o, "cgroupPath");
        return c;
    }

    public Object toJson() {
        Map<String, Object> o = JsonMap.obj();
        JsonMap.put(o, "cgroupPath", cgroupPath);
        return o;
    }
}
