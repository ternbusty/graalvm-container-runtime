package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.config.KontainerConfig;
import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.state.State;
import com.ternbusty.takoyaki.syscall.Fs;
import com.ternbusty.takoyaki.util.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class PsCommand {
    private PsCommand() {}

    public static int run(String rootPath, String containerId, String format) {
        State state;
        try {
            state = State.load(rootPath, containerId).refreshStatus();
        } catch (Exception e) {
            Logger.error("failed to load state: " + e.getMessage());
            return 1;
        }
        String cgroupPath = null;
        try {
            cgroupPath = KontainerConfig.load(rootPath, containerId).cgroupPath;
        } catch (IOException ignored) {}

        List<Integer> pids = new ArrayList<>();
        if (cgroupPath != null) {
            String norm = cgroupPath.startsWith("/") ? cgroupPath.substring(1) : cgroupPath;
            String procs = "/sys/fs/cgroup/" + norm + "/cgroup.procs";
            try {
                for (String line : Fs.readAllLines(procs)) {
                    String t = line.trim();
                    if (!t.isEmpty()) pids.add(Integer.parseInt(t));
                }
            } catch (IOException e) {
                Logger.debug("read " + procs + " failed: " + e.getMessage());
            }
        }
        if (pids.isEmpty() && state.pid != null) pids.add(state.pid);

        if ("json".equals(format)) {
            System.out.println(Json.encode(pids));
            return 0;
        }
        System.out.printf("%-8s %s%n", "PID", "CMD");
        for (int pid : pids) {
            String cmd = "";
            try {
                cmd = Fs.readString("/proc/" + pid + "/cmdline")
                        .replace('\0', ' ').trim();
            } catch (IOException ignored) {}
            System.out.printf("%-8d %s%n", pid, cmd);
        }
        return 0;
    }
}
