package com.ternbusty.takoyaki.syscall;

import com.ternbusty.takoyaki.spec.Spec;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.foreign.Arena;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RlimitTest {

    private Spec.POSIXRlimit r(String type, long soft, long hard) {
        Spec.POSIXRlimit p = new Spec.POSIXRlimit();
        p.type = type;
        p.soft = soft;
        p.hard = hard;
        return p;
    }

    @Test
    void nullListDoesNothing() {
        try (MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            Rlimit.apply(123, null);
            lm.verifyNoInteractions();
        }
    }

    @Test
    void emptyListDoesNothing() {
        try (MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            Rlimit.apply(123, List.of());
            lm.verifyNoInteractions();
        }
    }

    @Test
    void eachKnownTypeRoutesToTheRightResourceId() {
        // OCI process.rlimits is keyed by RLIMIT_* strings; we translate them
        // to the kernel's `resource` enum and call prlimit64. This test pins
        // the (string → kernel id) table — getting any one wrong would break
        // runtime-tools' process_rlimits assertions silently.
        try (MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            lm.when(() -> Libc.prlimit64(any(Arena.class), anyInt(), anyInt(),
                                          anyLong(), anyLong())).thenReturn(0);

            Rlimit.apply(7777, List.of(
                    r("RLIMIT_NOFILE", 3000, 4000),
                    r("RLIMIT_AS",     1L<<30, 2L<<30),
                    r("RLIMIT_STACK",  9L<<30, 10L<<30),
                    r("RLIMIT_CPU",    60, 120),
                    r("RLIMIT_CORE",   3L<<30, 4L<<30)));

            lm.verify(() -> Libc.prlimit64(any(Arena.class), eq(7777),
                    eq(Constants.RLIMIT_NOFILE), eq(3000L), eq(4000L)));
            lm.verify(() -> Libc.prlimit64(any(Arena.class), eq(7777),
                    eq(Constants.RLIMIT_AS),     eq(1L<<30), eq(2L<<30)));
            lm.verify(() -> Libc.prlimit64(any(Arena.class), eq(7777),
                    eq(Constants.RLIMIT_STACK),  eq(9L<<30), eq(10L<<30)));
            lm.verify(() -> Libc.prlimit64(any(Arena.class), eq(7777),
                    eq(Constants.RLIMIT_CPU),    eq(60L), eq(120L)));
            lm.verify(() -> Libc.prlimit64(any(Arena.class), eq(7777),
                    eq(Constants.RLIMIT_CORE),   eq(3L<<30), eq(4L<<30)));
        }
    }

    @Test
    void unknownRlimitTypeIsSkippedNotFatal() {
        try (MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            lm.when(() -> Libc.prlimit64(any(), anyInt(), anyInt(), anyLong(), anyLong()))
                    .thenReturn(0);
            // Mix a known type with a garbage one; the known one must still go
            // through and the unknown must NOT raise.
            assertDoesNotThrow(() -> Rlimit.apply(7777, List.of(
                    r("RLIMIT_NOFILE", 100, 200),
                    r("RLIMIT_BOGUS",  1, 2))));
            lm.verify(() -> Libc.prlimit64(any(), eq(7777),
                    eq(Constants.RLIMIT_NOFILE), eq(100L), eq(200L)));
            // The bogus type must NOT generate a prlimit64 call.
            lm.verifyNoMoreInteractions();
        }
    }

    @Test
    void prlimitFailureWarnsButContinuesIteration() {
        // First prlimit64 fails, second one should still be attempted.
        try (MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            lm.when(() -> Libc.prlimit64(any(), eq(1234),
                    eq(Constants.RLIMIT_NOFILE), anyLong(), anyLong())).thenReturn(-1);
            lm.when(Libc::errno).thenReturn(1 /*EPERM*/);
            lm.when(() -> Libc.strerror(anyInt())).thenReturn("Operation not permitted");
            lm.when(() -> Libc.prlimit64(any(), eq(1234),
                    eq(Constants.RLIMIT_AS), anyLong(), anyLong())).thenReturn(0);

            Rlimit.apply(1234, List.of(
                    r("RLIMIT_NOFILE", 1, 1),
                    r("RLIMIT_AS",     2, 2)));

            lm.verify(() -> Libc.prlimit64(any(), eq(1234),
                    eq(Constants.RLIMIT_AS), eq(2L), eq(2L)));
        }
    }
}
