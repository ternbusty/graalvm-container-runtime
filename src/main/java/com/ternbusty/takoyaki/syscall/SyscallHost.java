package com.ternbusty.takoyaki.syscall;

/**
 * Per-thread holder of the active {@link Syscalls} implementation.
 *
 * Production threads always see {@link LinuxSyscalls}. A test installs an
 * alternate impl with {@link #install(Syscalls)} and restores via the returned
 * {@link Scope} (try-with-resources friendly).
 *
 * Per-thread, not global, so JUnit can run test classes in parallel without
 * trampling each other's fakes.
 */
public final class SyscallHost {
    private static final ThreadLocal<Syscalls> CURRENT =
            ThreadLocal.withInitial(LinuxSyscalls::new);

    private SyscallHost() {}

    /** Get the active impl for this thread. */
    public static Syscalls current() {
        return CURRENT.get();
    }

    /**
     * Install {@code impl} for this thread until the returned scope is closed.
     *
     * <pre>
     * try (var scope = SyscallHost.install(new RecordingSyscalls())) {
     *     codeUnderTest();
     * }
     * </pre>
     */
    public static Scope install(Syscalls impl) {
        Syscalls prev = CURRENT.get();
        CURRENT.set(impl);
        return () -> CURRENT.set(prev);
    }

    /** AutoCloseable without a checked exception, for use in test try-blocks. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override void close();
    }
}
