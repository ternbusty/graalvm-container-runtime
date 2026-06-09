package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.config.KontainerConfig;
import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.state.State;
import com.ternbusty.takoyaki.util.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "ps", description = "List processes in a container")
public final class PsCommand implements Callable<Integer> {
    @ParentCommand
    TakoyakiRoot root;

    @Option(names = {"-f", "--format"}, defaultValue = "table",
            description = "Output format (table or json)")
    String format;

    @Parameters(index = "0", description = "Container ID")
    String containerId;

    @Override
    public Integer call() {
        State state;
        try {
            state = State.load(root.rootPath, containerId).refreshStatus();
        } catch (Exception e) {
            Logger.error("failed to load state: " + e.getMessage());
            return 1;
        }
        String cgroupPath = null;
        try {
            cgroupPath = KontainerConfig.load(root.rootPath, containerId).cgroupPath;
        } catch (IOException ignored) {}

        List<Integer> pids = new ArrayList<>();
        if (cgroupPath != null) {
            String norm = cgroupPath.startsWith("/") ? cgroupPath.substring(1) : cgroupPath;
            Path procs = Path.of("/sys/fs/cgroup", norm, "cgroup.procs");
            try {
                for (String line : Files.readAllLines(procs)) {
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
                cmd = Files.readString(Path.of("/proc", String.valueOf(pid), "cmdline"))
                        .replace('\0', ' ').trim();
            } catch (IOException ignored) {}
            System.out.printf("%-8d %s%n", pid, cmd);
        }
        return 0;
    }
}
