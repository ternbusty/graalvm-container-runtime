package com.ternbusty.takoyaki.process;

import com.ternbusty.takoyaki.cgroup.Cgroup;
import com.ternbusty.takoyaki.config.KontainerConfig;
import com.ternbusty.takoyaki.ipc.SyncChannel;
import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.state.ContainerStatus;
import com.ternbusty.takoyaki.state.State;
import com.ternbusty.takoyaki.syscall.Constants;
import com.ternbusty.takoyaki.syscall.Libc;
import com.ternbusty.takoyaki.syscall.PosixIO;
import com.ternbusty.takoyaki.syscall.Rlimit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class MainProcess {
    private MainProcess() {}

    public static void run(int stage1Pid, int syncFd, Spec spec, String containerId,
                           String bundlePath, String rootPath, String pidFile,
                           int notifyListenerFd, int mainSenderFd) {
        Logger.setContext("main");
        Logger.debug("main proc started; stage1=" + stage1Pid);
        try {
            String mntNs = java.nio.file.Files.readSymbolicLink(java.nio.file.Path.of("/proc/self/ns/mnt")).toString();
            Logger.debug("main mnt_ns=" + mntNs);
        } catch (Exception e) {}

        try {
            if (spec.linux != null && spec.linux.cgroupsPath != null) {
                Cgroup.setup(stage1Pid, spec.linux.cgroupsPath, spec.linux);
            }
            if (spec.process != null && spec.process.rlimits != null) {
                Rlimit.apply(stage1Pid, spec.process.rlimits);
            }

            boolean hasUserNs = spec.hasNamespace("user");
            if (hasUserNs) {
                int req = SyncChannel.readInt32(syncFd);
                if (req != SyncChannel.MSG_USERMAP_PLS) {
                    throw new RuntimeException("expected USERMAP_PLS, got 0x" + Integer.toHexString(req));
                }
                int bootstrapPid = SyncChannel.readInt32(syncFd);
                Logger.debug("writing uid/gid map for pid " + bootstrapPid);

                String uidMap = buildIdMapping(spec.linux != null ? spec.linux.uidMappings : null);
                String gidMap = buildIdMapping(spec.linux != null ? spec.linux.gidMappings : null);

                long euid = uidFromProc();
                boolean privileged = (euid == 0);
                if (!privileged) {
                    try {
                        Files.writeString(Path.of("/proc/" + bootstrapPid + "/setgroups"), "deny\n");
                    } catch (IOException e) {
                        Logger.warn("setgroups write failed: " + e.getMessage());
                    }
                }
                Files.writeString(Path.of("/proc/" + bootstrapPid + "/uid_map"), uidMap);
                Files.writeString(Path.of("/proc/" + bootstrapPid + "/gid_map"), gidMap);

                SyncChannel.writeInt32(syncFd, SyncChannel.MSG_USERMAP_ACK);
                Logger.debug("user map written");
            }

            int stage2Pid = SyncChannel.readInt32(syncFd);
            Logger.debug("received stage-2 pid=" + stage2Pid);
            PosixIO.close(syncFd);
            PosixIO.close(notifyListenerFd);

            if (spec.linux != null && spec.linux.cgroupsPath != null) {
                Cgroup.setup(stage2Pid, spec.linux.cgroupsPath, spec.linux);
            }

            int initReady = SyncChannel.readInt32(mainSenderFd);
            if (initReady != SyncChannel.MSG_INIT_READY) {
                throw new RuntimeException("expected INIT_READY, got 0x" + Integer.toHexString(initReady));
            }
            PosixIO.close(mainSenderFd);
            Logger.debug("init ready");

            State state = State.create(spec.ociVersion, containerId,
                    ContainerStatus.CREATED, stage2Pid, bundlePath, null);
            state.save(rootPath);

            new KontainerConfig(spec.linux != null ? spec.linux.cgroupsPath : null)
                    .save(rootPath, containerId);

            if (pidFile != null) {
                Files.writeString(Path.of(pidFile), Integer.toString(stage2Pid));
            }

            Logger.info("container " + containerId + " created with init pid " + stage2Pid);
            System.exit(0);
        } catch (Exception e) {
            Logger.error("main proc failed: " + e.getMessage());
            e.printStackTrace(System.err);
            PosixIO.close(syncFd);
            PosixIO.close(notifyListenerFd);
            PosixIO._exit(1);
        }
    }

    private static String buildIdMapping(List<Spec.IdMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            long euid = uidFromProc();
            return "0 " + euid + " 1\n";
        }
        StringBuilder sb = new StringBuilder();
        for (Spec.IdMapping m : mappings) {
            sb.append(m.containerID).append(' ')
              .append(m.hostID).append(' ')
              .append(m.size).append('\n');
        }
        return sb.toString();
    }

    private static long uidFromProc() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("Uid:")) {
                    String[] parts = line.split("\\s+");
                    return Long.parseLong(parts[1]);
                }
            }
        } catch (IOException ignored) {}
        return 0;
    }
}
