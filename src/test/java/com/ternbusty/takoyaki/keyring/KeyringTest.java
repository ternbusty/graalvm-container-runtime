package com.ternbusty.takoyaki.keyring;

import com.ternbusty.takoyaki.syscall.RecordingSyscalls;
import com.ternbusty.takoyaki.syscall.SyscallHost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rewritten to drive the Syscalls trait fake. The previous version pinned the
 * raw keyctl syscall number / command int directly via mockStatic(Libc.class).
 * That logic now lives inside LinuxSyscalls. The fake intercepts at the
 * semantic level — "did we ask to join a new session keyring?".
 */
class KeyringTest {

    @Test
    void joinNewSessionInvokesKeyctlOnce() {
        // Single call per joinNewSession invocation. No retry, no fan-out.
        RecordingSyscalls rec = new RecordingSyscalls().stubKeyctlJoinReturn(42L);
        try (var s = SyscallHost.install(rec)) {
            Keyring.joinNewSession("takoyaki-7");
        }
        assertEquals(1, rec.keyctlJoinCalls().size());
        assertEquals("takoyaki-7", rec.keyctlJoinCalls().get(0).name());
    }

    @Test
    void anonymousSessionPassesNullName() {
        // name == null is "anonymous new session keyring" per kernel semantics.
        // We must propagate the null, NOT substitute a default string.
        RecordingSyscalls rec = new RecordingSyscalls().stubKeyctlJoinReturn(0L);
        try (var s = SyscallHost.install(rec)) {
            Keyring.joinNewSession(null);
        }
        assertEquals(1, rec.keyctlJoinCalls().size());
        assertNull(rec.keyctlJoinCalls().get(0).name(),
                "null name must pass through as null");
    }

    @Test
    void negativeReturnIsLoggedNotPropagated() {
        // EPERM from the kernel must surface as a debug log, not a thrown
        // exception. The container should still come up.
        RecordingSyscalls rec = new RecordingSyscalls()
                .stubKeyctlJoinReturn(-1L)
                .stubErrno(1 /*EPERM*/);

        try (var s = SyscallHost.install(rec)) {
            assertDoesNotThrow(() -> Keyring.joinNewSession("anything"));
        }
        assertEquals(1, rec.keyctlJoinCalls().size());
    }
}
