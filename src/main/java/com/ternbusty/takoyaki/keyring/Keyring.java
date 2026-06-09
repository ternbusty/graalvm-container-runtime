package com.ternbusty.takoyaki.keyring;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.syscall.Libc;

/**
 * Join a fresh kernel session keyring so the container can't see (or be affected by)
 * keys from the runtime's session.
 *
 *   keyctl(KEYCTL_JOIN_SESSION_KEYRING, "container-name", 0, 0, 0)
 *
 * runc does this by default; opting out via --no-new-keyring leaves the inherited
 * keyring intact.
 *
 * Done in init AFTER privilege drop but BEFORE execve. The keyring is per-thread so
 * we must run it from the thread that will exec.
 */
public final class Keyring {
    // glibc-side syscall numbers (same on aarch64 and x86_64).
    private static final long NR_keyctl_aarch64 = 219L;
    private static final long NR_keyctl_x86_64  = 250L;
    private static final int KEYCTL_JOIN_SESSION_KEYRING = 1;

    private Keyring() {}

    public static void joinNewSession(String name) {
        long nr = is64() && isAarch64() ? NR_keyctl_aarch64 : NR_keyctl_x86_64;
        // Pass name as a C string via syscall arg2; for the simple case of an
        // anonymous new keyring, name can be NULL.
        long arg = 0L;
        java.lang.foreign.Arena arena = null;
        try {
            if (name != null) {
                arena = java.lang.foreign.Arena.ofConfined();
                arg = arena.allocateFrom(name).address();
            }
            long rc = Libc.syscall(nr, (long) KEYCTL_JOIN_SESSION_KEYRING, arg, 0, 0, 0);
            if (rc < 0) {
                Logger.debug("keyctl(JOIN_SESSION_KEYRING) failed: " + Libc.strerror(Libc.errno()));
            } else {
                Logger.debug("joined new session keyring serial=" + rc);
            }
        } finally {
            if (arena != null) arena.close();
        }
    }

    private static boolean is64() { return true; }
    private static boolean isAarch64() {
        String a = System.getProperty("os.arch", "").toLowerCase();
        return a.contains("aarch64") || a.contains("arm64");
    }
}
