package com.ternbusty.takoyaki.rootfs;

import com.ternbusty.takoyaki.syscall.Constants;
import com.ternbusty.takoyaki.syscall.Libc;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.foreign.Arena;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RootfsMaskAndReadonlyTest {

    // ---- maskPaths -----------------------------------------------------------

    @Test
    void maskPathsNullIsNoOp() {
        // Spec without maskedPaths must not poke any syscall.
        try (MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            Rootfs.maskPaths(null);
            lm.verifyNoInteractions();
        }
    }

    @Test
    void maskPathsBindMountsDevNullOverEachPath() {
        // Per OCI spec, masking a FILE is done by bind-mounting /dev/null over
        // it. The kernel sees no content beneath. This is the default branch
        // (rc == 0 from the first mount call).
        try (MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            lm.when(() -> Libc.mount(any(Arena.class), anyString(), anyString(),
                    nullable(String.class), anyLong(), nullable(String.class)))
                    .thenReturn(0);

            Rootfs.maskPaths(List.of("/proc/kcore", "/sys/firmware"));

            lm.verify(() -> Libc.mount(any(Arena.class),
                    eq("/dev/null"), eq("/proc/kcore"),
                    isNull(), eq(Constants.MS_BIND), isNull()));
            lm.verify(() -> Libc.mount(any(Arena.class),
                    eq("/dev/null"), eq("/sys/firmware"),
                    isNull(), eq(Constants.MS_BIND), isNull()));
        }
    }

    @Test
    void maskPathsFallsBackToTmpfsWhenBindOverFileFailsAndTargetIsDir() {
        // /dev/null is a regular file, so bind-mounting it over a DIRECTORY
        // returns ENOTDIR. The runtime must fall back to mounting an empty
        // tmpfs read-only over the directory.
        try (MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            // First call (bind /dev/null) fails.
            lm.when(() -> Libc.mount(any(Arena.class), eq("/dev/null"), anyString(),
                    isNull(), eq(Constants.MS_BIND), isNull())).thenReturn(-1);
            // errno set to something that ISN'T ENOENT so we fall through to tmpfs.
            lm.when(Libc::errno).thenReturn(20 /* ENOTDIR */);
            lm.when(() -> Libc.strerror(anyInt())).thenReturn("error");
            // The tmpfs fallback must succeed.
            lm.when(() -> Libc.mount(any(Arena.class), eq("tmpfs"), anyString(),
                    eq("tmpfs"), eq(Constants.MS_RDONLY), isNull())).thenReturn(0);

            Rootfs.maskPaths(List.of("/proc/scsi"));

            lm.verify(() -> Libc.mount(any(Arena.class), eq("tmpfs"),
                    eq("/proc/scsi"), eq("tmpfs"),
                    eq(Constants.MS_RDONLY), isNull()));
        }
    }

    @Test
    void maskPathsSkipsTargetThatDoesNotExist() {
        // ENOENT after the first mount means the path isn't in the rootfs.
        // We skip rather than masking nothing or panicking. Critically, the
        // tmpfs fallback must NOT run for ENOENT.
        try (MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            lm.when(() -> Libc.mount(any(Arena.class), anyString(), anyString(),
                    nullable(String.class), anyLong(), nullable(String.class)))
                    .thenReturn(-1);
            lm.when(Libc::errno).thenReturn(Constants.ENOENT);

            Rootfs.maskPaths(List.of("/not/in/rootfs"));

            // ONLY the first /dev/null bind attempt should have happened.
            lm.verify(() -> Libc.mount(any(Arena.class), eq("/dev/null"),
                    eq("/not/in/rootfs"), isNull(), eq(Constants.MS_BIND), isNull()));
            // No tmpfs fallback for ENOENT.
            lm.verify(() -> Libc.mount(any(Arena.class), eq("tmpfs"),
                    anyString(), eq("tmpfs"), anyLong(), nullable(String.class)),
                    never());
        }
    }

    @Test
    void maskPathsBothMountsFailingIsLoggedNotThrown() {
        // If even the tmpfs fallback fails, the runtime must not crash. The
        // container will still come up just without that mask.
        try (MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            lm.when(() -> Libc.mount(any(Arena.class), anyString(), anyString(),
                    nullable(String.class), anyLong(), nullable(String.class)))
                    .thenReturn(-1);
            lm.when(Libc::errno).thenReturn(13 /* EACCES */);
            lm.when(() -> Libc.strerror(anyInt())).thenReturn("error");

            assertDoesNotThrow(() -> Rootfs.maskPaths(List.of("/proc/sysrq-trigger")));
        }
    }

    // ---- readonlyRemount ----------------------------------------------------

    @Test
    void readonlyRemountNullIsNoOp() {
        try (MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            Rootfs.readonlyRemount(null);
            lm.verifyNoInteractions();
        }
    }

    @Test
    void readonlyRemountBindsThenRemountsReadOnly() {
        // The runtime contract: first a self-bind (MS_BIND|MS_REC) so the
        // remount won't affect the host, then a remount with MS_RDONLY added.
        // The kernel REQUIRES two calls — MS_RDONLY on a fresh bind is silently
        // dropped.
        try (MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            lm.when(() -> Libc.mount(any(Arena.class), anyString(), anyString(),
                    isNull(), anyLong(), isNull())).thenReturn(0);

            Rootfs.readonlyRemount(List.of("/proc/sys"));

            // First call: self-bind.
            lm.verify(() -> Libc.mount(any(Arena.class),
                    eq("/proc/sys"), eq("/proc/sys"), isNull(),
                    eq(Constants.MS_BIND | Constants.MS_REC), isNull()));
            // Second call: remount with MS_RDONLY.
            long expected = Constants.MS_BIND | Constants.MS_REC
                    | Constants.MS_REMOUNT | Constants.MS_RDONLY;
            lm.verify(() -> Libc.mount(any(Arena.class),
                    eq("/proc/sys"), eq("/proc/sys"), isNull(),
                    eq(expected), isNull()));
        }
    }

    @Test
    void readonlyRemountSkipsEnoentWithoutAttemptingRemount() {
        // ENOENT on the self-bind means the path isn't in this rootfs. Don't
        // try to remount what we didn't bind.
        try (MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            lm.when(() -> Libc.mount(any(Arena.class), anyString(), anyString(),
                    isNull(), anyLong(), isNull())).thenReturn(-1);
            lm.when(Libc::errno).thenReturn(Constants.ENOENT);

            Rootfs.readonlyRemount(List.of("/not/here"));

            // First call happened, second (remount) must NOT.
            lm.verify(() -> Libc.mount(any(Arena.class),
                    eq("/not/here"), eq("/not/here"), isNull(),
                    eq(Constants.MS_BIND | Constants.MS_REC), isNull()));
            long remountFlags = Constants.MS_BIND | Constants.MS_REC
                    | Constants.MS_REMOUNT | Constants.MS_RDONLY;
            lm.verify(() -> Libc.mount(any(Arena.class), anyString(), anyString(),
                    isNull(), eq(remountFlags), isNull()), never());
        }
    }
}
