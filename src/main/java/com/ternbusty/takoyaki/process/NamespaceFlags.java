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
            flags |= switch (ns.type) {
                case "mount" -> Constants.CLONE_NEWNS;
                case "network" -> Constants.CLONE_NEWNET;
                case "uts" -> Constants.CLONE_NEWUTS;
                case "ipc" -> Constants.CLONE_NEWIPC;
                case "pid" -> Constants.CLONE_NEWPID;
                case "user" -> Constants.CLONE_NEWUSER;
                case "cgroup" -> Constants.CLONE_NEWCGROUP;
                default -> 0;
            };
        }
        return flags;
    }
}
