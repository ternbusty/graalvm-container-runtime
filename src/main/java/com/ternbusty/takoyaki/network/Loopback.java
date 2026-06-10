package com.ternbusty.takoyaki.network;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.syscall.SyscallHost;
import com.ternbusty.takoyaki.syscall.Syscalls;

/**
 * Bring up the lo interface inside the container's network namespace.
 *
 * The SIOCGIFFLAGS / SIOCSIFFLAGS ioctl dance lives inside
 * {@link com.ternbusty.takoyaki.syscall.LinuxSyscalls#ifUp(String)} so this
 * class only expresses intent — "bring lo up" — and {@link Syscalls#ifUp}
 * picks how (ioctl today, rtnetlink later).
 */
public final class Loopback {
    private Loopback() {}

    public static void up() {
        Syscalls sc = SyscallHost.current();
        if (sc.ifUp("lo") != 0) {
            Logger.debug("loopback: ifUp(lo) failed: " + sc.strerror(sc.errno()));
        } else {
            Logger.debug("loopback: lo is up");
        }
    }
}
