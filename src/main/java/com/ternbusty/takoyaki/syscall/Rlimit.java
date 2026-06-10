package com.ternbusty.takoyaki.syscall;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.spec.Spec;

import java.util.List;

public final class Rlimit {
    private Rlimit() {}

    public static void apply(int pid, List<Spec.POSIXRlimit> rlimits) {
        if (rlimits == null || rlimits.isEmpty()) return;
        Syscalls sc = SyscallHost.current();
        for (Spec.POSIXRlimit r : rlimits) {
            int resource = resourceId(r.type);
            if (resource < 0) {
                Logger.warn("unknown rlimit type: " + r.type);
                continue;
            }
            int rc = sc.prlimit64(pid, resource, r.soft, r.hard);
            if (rc != 0) {
                Logger.warn("prlimit64 " + r.type + " failed: " + sc.strerror(sc.errno()));
            } else {
                Logger.debug("rlimit " + r.type + " soft=" + r.soft + " hard=" + r.hard);
            }
        }
    }

    private static int resourceId(String type) {
        return switch (type) {
            case "RLIMIT_CPU" -> Constants.RLIMIT_CPU;
            case "RLIMIT_FSIZE" -> Constants.RLIMIT_FSIZE;
            case "RLIMIT_DATA" -> Constants.RLIMIT_DATA;
            case "RLIMIT_STACK" -> Constants.RLIMIT_STACK;
            case "RLIMIT_CORE" -> Constants.RLIMIT_CORE;
            case "RLIMIT_RSS" -> Constants.RLIMIT_RSS;
            case "RLIMIT_NPROC" -> Constants.RLIMIT_NPROC;
            case "RLIMIT_NOFILE" -> Constants.RLIMIT_NOFILE;
            case "RLIMIT_MEMLOCK" -> Constants.RLIMIT_MEMLOCK;
            case "RLIMIT_AS" -> Constants.RLIMIT_AS;
            case "RLIMIT_LOCKS" -> Constants.RLIMIT_LOCKS;
            case "RLIMIT_SIGPENDING" -> Constants.RLIMIT_SIGPENDING;
            case "RLIMIT_MSGQUEUE" -> Constants.RLIMIT_MSGQUEUE;
            case "RLIMIT_NICE" -> Constants.RLIMIT_NICE;
            case "RLIMIT_RTPRIO" -> Constants.RLIMIT_RTPRIO;
            case "RLIMIT_RTTIME" -> Constants.RLIMIT_RTTIME;
            default -> -1;
        };
    }
}
