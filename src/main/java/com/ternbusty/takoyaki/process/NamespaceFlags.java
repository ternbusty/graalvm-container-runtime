package com.ternbusty.takoyaki.process;

import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.syscall.Constants;

import java.util.List;

public final class NamespaceFlags {
    private NamespaceFlags() {}

    public static int fromSpec(List<Spec.Namespace> namespaces) {
        if (namespaces == null) return 0;
        int flags = 0;
        for (Spec.Namespace ns : namespaces) {
            // Namespaces with an explicit `path` field are joined via setns()
            // by bootstrap.c, not created via unshare — leave them out of the
            // clone flags.
            if (ns.path != null && !ns.path.isEmpty()) continue;
            flags |= toFlag(ns.type);
        }
        return flags;
    }

    public static int toFlag(String type) {
        if (type == null) return 0;
        return switch (type) {
            case "mount" -> Constants.CLONE_NEWNS;
            case "network" -> Constants.CLONE_NEWNET;
            case "uts" -> Constants.CLONE_NEWUTS;
            case "ipc" -> Constants.CLONE_NEWIPC;
            case "pid" -> Constants.CLONE_NEWPID;
            case "user" -> Constants.CLONE_NEWUSER;
            case "cgroup" -> Constants.CLONE_NEWCGROUP;
            case "time" -> Constants.CLONE_NEWTIME;
            default -> 0;
        };
    }
}
