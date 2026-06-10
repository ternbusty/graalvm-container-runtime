package com.ternbusty.takoyaki.syscall;

import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.syscall.RecordingSyscalls.PrlimitCall;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

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
        RecordingSyscalls rec = new RecordingSyscalls();
        try (var s = SyscallHost.install(rec)) {
            Rlimit.apply(123, null);
        }
        assertTrue(rec.prlimitCalls().isEmpty());
    }

    @Test
    void emptyListDoesNothing() {
        RecordingSyscalls rec = new RecordingSyscalls();
        try (var s = SyscallHost.install(rec)) {
            Rlimit.apply(123, List.of());
        }
        assertTrue(rec.prlimitCalls().isEmpty());
    }

    @Test
    void eachKnownTypeRoutesToTheRightResourceId() {
        // OCI process.rlimits is keyed by RLIMIT_* strings. We translate them
        // to the kernel's `resource` enum and call prlimit64. This test pins
        // the (string -> kernel id) table. Getting any one wrong would break
        // runtime-tools' process_rlimits assertions silently.
        RecordingSyscalls rec = new RecordingSyscalls();
        try (var s = SyscallHost.install(rec)) {
            Rlimit.apply(7777, List.of(
                    r("RLIMIT_NOFILE", 3000, 4000),
                    r("RLIMIT_AS",     1L<<30, 2L<<30),
                    r("RLIMIT_STACK",  9L<<30, 10L<<30),
                    r("RLIMIT_CPU",    60, 120),
                    r("RLIMIT_CORE",   3L<<30, 4L<<30)));
        }

        List<PrlimitCall> calls = rec.prlimitCalls();
        assertEquals(5, calls.size());
        assertEquals(new PrlimitCall(7777, Constants.RLIMIT_NOFILE, 3000L, 4000L), calls.get(0));
        assertEquals(new PrlimitCall(7777, Constants.RLIMIT_AS,     1L<<30, 2L<<30), calls.get(1));
        assertEquals(new PrlimitCall(7777, Constants.RLIMIT_STACK,  9L<<30, 10L<<30), calls.get(2));
        assertEquals(new PrlimitCall(7777, Constants.RLIMIT_CPU,    60L, 120L), calls.get(3));
        assertEquals(new PrlimitCall(7777, Constants.RLIMIT_CORE,   3L<<30, 4L<<30), calls.get(4));
    }

    @Test
    void unknownRlimitTypeIsSkippedNotFatal() {
        // Mix a known type with a garbage one. The known one must still go
        // through and the unknown must NOT raise.
        RecordingSyscalls rec = new RecordingSyscalls();
        try (var s = SyscallHost.install(rec)) {
            assertDoesNotThrow(() -> Rlimit.apply(7777, List.of(
                    r("RLIMIT_NOFILE", 100, 200),
                    r("RLIMIT_BOGUS",  1, 2))));
        }

        // Exactly one call: the known type only.
        assertEquals(1, rec.prlimitCalls().size());
        assertEquals(new PrlimitCall(7777, Constants.RLIMIT_NOFILE, 100L, 200L),
                rec.prlimitCalls().get(0));
    }

    @Test
    void prlimitFailureWarnsButContinuesIteration() {
        // First prlimit64 fails, second one should still be attempted.
        // Use a sequence-aware stub: first call -> -1, second call -> 0.
        int[] callIdx = {0};
        RecordingSyscalls rec = new RecordingSyscalls()
                .stubPrlimitReturn(0)   // will be overridden below
                .stubErrno(1 /*EPERM*/);
        // Re-stub with a sequence supplier (last writer wins for stub knobs).
        rec.stubPrlimitReturn(() -> IntStream.of(-1, 0).toArray()[callIdx[0]++]);

        try (var s = SyscallHost.install(rec)) {
            Rlimit.apply(1234, List.of(
                    r("RLIMIT_NOFILE", 1, 1),
                    r("RLIMIT_AS",     2, 2)));
        }

        assertEquals(2, rec.prlimitCalls().size(),
                "second prlimit must be attempted even though first failed");
        assertEquals(new PrlimitCall(1234, Constants.RLIMIT_NOFILE, 1L, 1L),
                rec.prlimitCalls().get(0));
        assertEquals(new PrlimitCall(1234, Constants.RLIMIT_AS, 2L, 2L),
                rec.prlimitCalls().get(1));
    }
}
