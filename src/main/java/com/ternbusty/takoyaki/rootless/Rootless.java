package com.ternbusty.takoyaki.rootless;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.syscall.Libc;

import java.util.ArrayList;
import java.util.List;

/**
 * Rootless container helpers.
 *
 * When running as a non-root user, only the unprivileged user himself (uid 0 inside
 * the new userns) can be mapped without external help. To map a *range* of uids
 * (e.g. for sub-uids from /etc/subuid), the setuid newuidmap/newgidmap binaries
 * from the shadow-utils package must be invoked because the kernel won't accept
 * /proc/PID/uid_map writes from an unprivileged caller.
 *
 * runc and youki use the same approach.
 */
public final class Rootless {
    private Rootless() {}

    public static boolean isRootless() {
        return Libc.geteuid() != 0;
    }

    /** Write uid_map for `pid` via newuidmap if available, else fall back to direct write. */
    public static boolean writeUidMap(int pid, List<Spec.IdMapping> mappings) {
        return writeViaHelper(pid, mappings, "newuidmap");
    }

    public static boolean writeGidMap(int pid, List<Spec.IdMapping> mappings) {
        return writeViaHelper(pid, mappings, "newgidmap");
    }

    private static boolean writeViaHelper(int pid, List<Spec.IdMapping> mappings, String helper) {
        if (mappings == null || mappings.isEmpty()) return true;
        List<String> cmd = new ArrayList<>();
        cmd.add(helper);
        cmd.add(Integer.toString(pid));
        for (Spec.IdMapping m : mappings) {
            cmd.add(Long.toString(m.containerID));
            cmd.add(Long.toString(m.hostID));
            cmd.add(Long.toString(m.size));
        }
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            int rc = p.waitFor();
            if (rc != 0) {
                String out = new String(p.getInputStream().readAllBytes());
                Logger.warn(helper + " failed (rc=" + rc + "): " + out);
                return false;
            }
            Logger.debug(helper + " ok for pid " + pid);
            return true;
        } catch (Exception e) {
            Logger.debug(helper + " not usable: " + e.getMessage());
            return false;
        }
    }
}
