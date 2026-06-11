package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.config.KontainerConfig;
import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.syscall.Fs;
import com.ternbusty.takoyaki.util.Json;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stream resource-usage statistics from the container's cgroup. Without --stats we
 * fall through to a polling loop similar to `runc events`. The format is JSON Lines.
 */
public final class EventsCommand {
    private EventsCommand() {}

    public static int run(String rootPath, String containerId, boolean once, int intervalSec) {
        String cgroupPath;
        try {
            cgroupPath = KontainerConfig.load(rootPath, containerId).cgroupPath;
        } catch (IOException e) {
            Logger.error("no cgroup recorded: " + e.getMessage());
            return 1;
        }
        if (cgroupPath == null) {
            Logger.error("no cgroupsPath for container");
            return 1;
        }
        String norm = cgroupPath.startsWith("/") ? cgroupPath.substring(1) : cgroupPath;
        String cg = "/sys/fs/cgroup/" + norm;
        do {
            Map<String, Object> snap = snapshot(cg, containerId);
            System.out.println(Json.encode(snap));
            if (once) break;
            try { Thread.sleep(intervalSec * 1000L); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (true);
        return 0;
    }

    private static Map<String, Object> snapshot(String cg, String id) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("id", id);
        ev.put("type", "stats");
        Map<String, Object> data = new HashMap<>();
        Map<String, Object> memory = new HashMap<>();
        memory.put("usage", readLong(cg + "/memory.current"));
        memory.put("limit", readTrim(cg + "/memory.max"));
        memory.put("events", readKvFile(cg + "/memory.events"));
        data.put("memory", memory);
        Map<String, Object> cpu = new HashMap<>();
        cpu.put("stat", readKvFile(cg + "/cpu.stat"));
        data.put("cpu", cpu);
        Map<String, Object> pids = new HashMap<>();
        pids.put("current", readLong(cg + "/pids.current"));
        pids.put("limit", readTrim(cg + "/pids.max"));
        data.put("pids", pids);
        ev.put("data", data);
        return ev;
    }

    private static Long readLong(String p) {
        String s = readTrim(p);
        if (s == null) return null;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return null; }
    }

    private static String readTrim(String p) {
        try { return Fs.readString(p).trim(); }
        catch (IOException e) { return null; }
    }

    private static Map<String, Long> readKvFile(String p) {
        Map<String, Long> kv = new LinkedHashMap<>();
        try {
            for (String line : Fs.readAllLines(p)) {
                String[] parts = line.split("\\s+");
                if (parts.length == 2) {
                    try { kv.put(parts[0], Long.parseLong(parts[1])); } catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException ignored) {}
        return kv;
    }
}
