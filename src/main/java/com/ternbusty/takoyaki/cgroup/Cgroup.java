package com.ternbusty.takoyaki.cgroup;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.syscall.Fs;

import java.io.IOException;

public final class Cgroup {
    private static final String CGROUP_ROOT = "/sys/fs/cgroup";

    private Cgroup() {}

    public static void setup(int pid, String cgroupPath, Spec.Linux linux) {
        if (cgroupPath == null) return;
        String norm = cgroupPath.startsWith("/") ? cgroupPath.substring(1) : cgroupPath;
        String full = CGROUP_ROOT + "/" + norm;
        try {
            Fs.createDirectories(full);
        } catch (IOException e) {
            Logger.warn("create cgroup dir failed: " + e.getMessage());
            return;
        }

        // Ensure controllers are enabled in the parent's subtree_control so this cgroup
        // can use them. Walk from root downward.
        enableControllers(full, linux != null ? linux.resources : null);

        applyLimits(full, linux != null ? linux.resources : null);

        try {
            Fs.writeString(full + "/cgroup.procs", Integer.toString(pid));
            Logger.debug("added pid " + pid + " to cgroup " + full);
        } catch (IOException e) {
            Logger.warn("add pid to cgroup failed: " + e.getMessage());
        }

        // eBPF device cgroup for resources.devices (cgroup v2 only path).
        if (linux != null && linux.resources != null && linux.resources.devices != null
                && !linux.resources.devices.isEmpty()) {
            DeviceCgroup.apply(cgroupPath, linux.resources.devices);
        }
    }

    private static void enableControllers(String full, Spec.LinuxResources r) {
        java.util.List<String> needed = new java.util.ArrayList<>();
        if (r != null) {
            if (r.memory != null) needed.add("memory");
            if (r.cpu != null) {
                needed.add("cpu");
                if (r.cpu.cpus != null || r.cpu.mems != null) needed.add("cpuset");
            }
            if (r.pids != null) needed.add("pids");
        }
        if (needed.isEmpty()) return;
        // Walk up from `full` to CGROUP_ROOT to enable controllers in subtree_control.
        java.util.List<String> chain = new java.util.ArrayList<>();
        String cur = Fs.parent(full);
        while (cur != null && cur.startsWith(CGROUP_ROOT)) {
            chain.add(0, cur);
            if (cur.equals(CGROUP_ROOT)) break;
            cur = Fs.parent(cur);
        }
        for (String p : chain) {
            String sc = p + "/cgroup.subtree_control";
            for (String ctrl : needed) {
                try {
                    Fs.writeString(sc, "+" + ctrl);
                } catch (IOException ignored) {}
            }
        }
    }

    /** Re-apply resource limits to an existing cgroup (e.g. via `update`). */
    public static void applyLimitsOnly(String cgroupPath, Spec.LinuxResources r) {
        String norm = cgroupPath.startsWith("/") ? cgroupPath.substring(1) : cgroupPath;
        applyLimits(CGROUP_ROOT + "/" + norm, r);
    }

    private static void applyLimits(String full, Spec.LinuxResources r) {
        if (r == null) return;
        if (r.memory != null) {
            if (r.memory.limit != null) writeIfPossible(full + "/memory.max",
                    r.memory.limit == -1L ? "max" : r.memory.limit.toString());
            if (r.memory.swap != null && r.memory.limit != null) {
                long swap = (r.memory.swap == -1L || r.memory.limit == -1L)
                        ? -1L
                        : r.memory.swap - r.memory.limit;
                writeIfPossible(full + "/memory.swap.max", swap == -1L ? "max" : Long.toString(swap));
            }
            if (r.memory.reservation != null) writeIfPossible(full + "/memory.low",
                    r.memory.reservation == -1L ? "max" : r.memory.reservation.toString());
        }
        if (r.cpu != null) {
            if (r.cpu.cpus != null && !r.cpu.cpus.isEmpty()) {
                writeIfPossible(full + "/cpuset.cpus", r.cpu.cpus);
            }
            if (r.cpu.mems != null && !r.cpu.mems.isEmpty()) {
                writeIfPossible(full + "/cpuset.mems", r.cpu.mems);
            }
            if (r.cpu.shares != null && r.cpu.shares > 0) {
                long w = 1 + ((r.cpu.shares - 2L) * 9999L / 262142L);
                if (w > 10000L) w = 10000L;
                writeIfPossible(full + "/cpu.weight", Long.toString(w));
            }
            if (r.cpu.quota != null || r.cpu.period != null) {
                String q = r.cpu.quota == null || r.cpu.quota <= 0 ? "max" : Long.toString(r.cpu.quota);
                String p = r.cpu.period == null ? "100000" : Long.toString(r.cpu.period);
                writeIfPossible(full + "/cpu.max", q + " " + p);
            }
        }
        if (r.pids != null && r.pids.limit > 0) {
            writeIfPossible(full + "/pids.max", Long.toString(r.pids.limit));
        }
    }

    private static void writeIfPossible(String p, String v) {
        try {
            Fs.writeString(p, v);
            Logger.debug("set " + Fs.name(p) + "=" + v);
        } catch (IOException e) {
            Logger.warn("write " + p + " (" + v + "): " + e.getMessage());
        }
    }

    public static void cleanup(String cgroupPath) {
        if (cgroupPath == null) return;
        String norm = cgroupPath.startsWith("/") ? cgroupPath.substring(1) : cgroupPath;
        String full = CGROUP_ROOT + "/" + norm;
        if (!Fs.exists(full)) return;

        // cgroup v2: writing "1" to cgroup.kill sends SIGKILL to every process
        // in this cgroup (Linux 5.14+). Best-effort — older kernels don't have
        // the file and the parent's SIGKILL on state.pid handles that path.
        try {
            Fs.writeString(full + "/cgroup.kill", "1");
        } catch (IOException ignored) {}

        // rmdir(2) on a cgroup v2 directory returns EBUSY ("Device or resource
        // busy") even briefly after the cgroup empties — the kernel runs an
        // async tear-down (cgroup_destroy_locked schedules work). Polling
        // cgroup.procs isn't enough to gate rmdir; we need to retry rmdir
        // itself. runc does the same in libcontainer/cgroups/fs2.
        IOException last = null;
        long deadlineNs = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadlineNs) {
            try {
                Fs.delete(full);
                Logger.debug("cgroup dir removed: " + full);
                return;
            } catch (IOException e) {
                if (!Fs.exists(full)) return;
                last = e;
                try { Thread.sleep(20); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        Logger.warn("cgroup cleanup failed (" + full + "): "
                + (last != null ? last.getMessage() : "deadline elapsed"));
    }
}
