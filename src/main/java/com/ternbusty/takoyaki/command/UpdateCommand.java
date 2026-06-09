package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.cgroup.Cgroup;
import com.ternbusty.takoyaki.config.KontainerConfig;
import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.util.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "update", description = "Update container resources")
public final class UpdateCommand implements Callable<Integer> {
    @ParentCommand TakoyakiRoot root;

    @Option(names = {"-r", "--resources"}, description = "Path to a JSON file with new resources")
    String resourcesPath;

    @Option(names = "--memory", description = "Memory limit in bytes")
    Long memory;

    @Option(names = "--cpu-quota", description = "CPU CFS quota in microseconds")
    Long cpuQuota;

    @Option(names = "--cpu-period", description = "CPU CFS period in microseconds")
    Long cpuPeriod;

    @Option(names = "--cpu-shares", description = "CPU shares (weight)")
    Long cpuShares;

    @Option(names = "--pids-limit", description = "Maximum number of pids")
    Long pidsLimit;

    @Parameters(index = "0", description = "Container ID")
    String containerId;

    @Override
    public Integer call() {
        String cgroupPath;
        try {
            cgroupPath = KontainerConfig.load(root.rootPath, containerId).cgroupPath;
        } catch (Exception e) {
            Logger.error("no kontainer config: " + e.getMessage());
            return 1;
        }
        if (cgroupPath == null) {
            Logger.error("container has no cgroupsPath");
            return 1;
        }

        Spec.LinuxResources r = new Spec.LinuxResources();
        if (resourcesPath != null) {
            try {
                Spec.LinuxResources parsed = Json.readFile(Path.of(resourcesPath),
                        Spec.LinuxResources.class);
                r = parsed;
            } catch (Exception e) {
                Logger.error("failed to read resources file: " + e.getMessage());
                return 1;
            }
        }
        if (memory != null) {
            if (r.memory == null) r.memory = new Spec.LinuxMemory();
            r.memory.limit = memory;
        }
        if (cpuQuota != null || cpuPeriod != null || cpuShares != null) {
            if (r.cpu == null) r.cpu = new Spec.LinuxCpu();
            if (cpuQuota != null) r.cpu.quota = cpuQuota;
            if (cpuPeriod != null) r.cpu.period = cpuPeriod;
            if (cpuShares != null) r.cpu.shares = cpuShares;
        }
        if (pidsLimit != null) {
            if (r.pids == null) r.pids = new Spec.LinuxPids();
            r.pids.limit = pidsLimit;
        }

        // Reuse the existing cgroup directory; just rewrite limits.
        Spec.Linux linux = new Spec.Linux();
        linux.resources = r;
        Cgroup.applyLimitsOnly(cgroupPath, r);
        Logger.info("updated resources for " + containerId);
        return 0;
    }
}
