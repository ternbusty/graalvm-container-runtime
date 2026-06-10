package com.ternbusty.takoyaki.syscall;

/**
 * Abstraction over every kernel-touching call takoyaki makes.
 *
 * This is the Java analogue of youki's {@code Syscall} trait. Production code
 * never calls {@link Libc} or {@link PosixIO} statics directly; it goes
 * through {@link SyscallHost#current()} and gets either {@link LinuxSyscalls}
 * (real path) or {@link com.ternbusty.takoyaki.syscall.RecordingSyscalls}
 * (test fake that captures every call).
 *
 * The benefit over Mockito {@code mockStatic} is that fork/clone3/unshare-driven
 * paths become unit-testable: the fake records "we called unshare(CLONE_NEWNS)"
 * without actually unsharing the test JVM. The downside is every callsite has
 * to be migrated. We grow this interface as more callsites move over.
 *
 * Method shape: take plain Java types, never expose {@link java.lang.foreign.Arena}.
 * Implementations handle Arena lifetime internally.
 */
public interface Syscalls {

    // ---- mount(2) family ----------------------------------------------------

    /**
     * Wrap mount(2). {@code source} or {@code data} may be null; pass through
     * as NULL in that case. Returns 0 on success, -1 on failure (errno set).
     */
    int mount(String source, String target, String fstype, long flags, String data);

    /** Wrap umount2(2). */
    int umount2(String target, int flags);

    // ---- errno reporting ----------------------------------------------------

    /** Last syscall errno seen by THIS Syscalls impl. */
    int errno();

    /** Human-readable name for an errno value. */
    String strerror(int errnum);

    // ---- signals ------------------------------------------------------------

    /** kill(pid, sig). Returns 0 on success, -1 on failure. */
    int kill(int pid, int sig);

    // ---- raw syscall --------------------------------------------------------

    /**
     * Raw syscall(2). Used for kernel calls that don't have glibc wrappers
     * (keyctl, etc). Argument count is fixed at 5 to keep the fake simple.
     */
    long syscall(long nr, long a1, long a2, long a3, long a4, long a5);

    // ---- resource limits ----------------------------------------------------

    /**
     * prlimit64(pid, resource, soft, hard). Sets a single rlimit on the target
     * pid. Resource is the RLIMIT_* int (caller maps OCI strings -> int).
     */
    int prlimit64(int pid, int resource, long soft, long hard);

    // ---- network interface --------------------------------------------------

    /**
     * Bring a network interface up by adding IFF_UP via SIOCGIFFLAGS /
     * SIOCSIFFLAGS. Implementations encapsulate the ioctl dance so callers
     * (Loopback, future bridge setup) only express intent.
     */
    int ifUp(String ifaceName);

    // ---- keyring ------------------------------------------------------------

    /**
     * keyctl(KEYCTL_JOIN_SESSION_KEYRING, name). Returns the new keyring
     * serial on success, -1 on failure.
     */
    long keyctlJoinSessionKeyring(String name);

    // ---- file system primitives --------------------------------------------

    /**
     * mknod(path, mode, dev). {@code mode} carries both the type bits
     * (S_IFCHR / S_IFBLK / S_IFIFO) and the permission bits. Returns 0 on
     * success, -1 on failure (EPERM in user namespaces is the common one).
     */
    int mknod(String path, int mode, long dev);

    /**
     * access(path, mode). Used to probe device-node presence on the host
     * before falling back to a bind mount. Returns 0 if accessible.
     */
    int access(String path, int mode);
}
