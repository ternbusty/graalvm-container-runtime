package com.ternbusty.takoyaki.keyring;

import com.ternbusty.takoyaki.syscall.Libc;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KeyringTest {

    @Test
    void joinNewSessionInvokesKeyctlSyscall() {
        // Verify the keyctl(2) syscall fires with KEYCTL_JOIN_SESSION_KEYRING
        // (= 1) as the command. The exact NR number depends on architecture
        // (aarch64=219, x86_64=250) so we don't pin it.
        try (MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            lm.when(() -> Libc.syscall(anyLong(), anyLong(), anyLong(),
                    anyLong(), anyLong(), anyLong())).thenReturn(42L);

            Keyring.joinNewSession("takoyaki-7");

            lm.verify(() -> Libc.syscall(
                    anyLong(),
                    eq(1L /* KEYCTL_JOIN_SESSION_KEYRING */),
                    anyLong(), eq(0L), eq(0L), eq(0L)));
        }
    }

    @Test
    void anonymousSessionPassesNullName() {
        // name == null → arg = 0 (NULL ptr to the kernel, which means an
        // anonymous new session keyring).
        try (MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            lm.when(() -> Libc.syscall(anyLong(), anyLong(), anyLong(),
                    anyLong(), anyLong(), anyLong())).thenReturn(0L);

            Keyring.joinNewSession(null);

            lm.verify(() -> Libc.syscall(
                    anyLong(), eq(1L), eq(0L), eq(0L), eq(0L), eq(0L)));
        }
    }

    @Test
    void negativeSyscallReturnIsLoggedNotPropagated() {
        // EPERM from the kernel must surface as a debug log, not a thrown
        // exception — the container should still come up.
        try (MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            lm.when(() -> Libc.syscall(anyLong(), anyLong(), anyLong(),
                    anyLong(), anyLong(), anyLong())).thenReturn(-1L);
            lm.when(Libc::errno).thenReturn(1 /*EPERM*/);
            lm.when(() -> Libc.strerror(anyInt())).thenReturn("Operation not permitted");

            assertDoesNotThrow(() -> Keyring.joinNewSession("anything"));
        }
    }
}
