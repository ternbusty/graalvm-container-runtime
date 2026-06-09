package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.config.KontainerConfig;
import com.ternbusty.takoyaki.logger.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Shared helper for pause / resume — writes cgroup.freeze (cgroup v2). */
final class Freeze {
    private Freeze() {}

    static int write(String rootPath, String containerId, String value, String op) {
        String cgroupPath;
        try {
            cgroupPath = KontainerConfig.load(rootPath, containerId).cgroupPath;
        } catch (IOException e) {
            Logger.error("no cgroup recorded for " + containerId);
            return 1;
        }
        if (cgroupPath == null) {
            Logger.error("container has no cgroupsPath, cannot " + op);
            return 1;
        }
        String norm = cgroupPath.startsWith("/") ? cgroupPath.substring(1) : cgroupPath;
        Path freeze = Path.of("/sys/fs/cgroup", norm, "cgroup.freeze");
        try {
            Files.writeString(freeze, value);
            Logger.info(op + " ok for " + containerId);
            return 0;
        } catch (IOException e) {
            Logger.error(op + " failed: " + e.getMessage());
            return 1;
        }
    }
}
