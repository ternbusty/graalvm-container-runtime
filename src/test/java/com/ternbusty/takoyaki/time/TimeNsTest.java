package com.ternbusty.takoyaki.time;

import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.syscall.Fs;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TimeNsTest {

    private static Spec.TimeOffset off(long secs, long ns) {
        Spec.TimeOffset t = new Spec.TimeOffset();
        t.secs = secs;
        t.nanosecs = ns;
        return t;
    }

    @Test
    void nullOffsetsIsNoOp() {
        // Both null and empty must skip the write entirely. Otherwise we'd hit
        // /proc/self/timens_offsets with an empty string, which the kernel
        // rejects after any fork, breaking re-entry.
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            TimeNs.applyOffsets(null);
            fm.verifyNoInteractions();
        }
    }

    @Test
    void emptyOffsetsIsNoOp() {
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            TimeNs.applyOffsets(Map.of());
            fm.verifyNoInteractions();
        }
    }

    @Test
    void monotonicMapsToClockId1AndIsFormattedAsThreeFieldsPerLine() {
        // Wire format per kernel docs: "<clock_id> <secs> <nanosecs>\n"
        // CLOCK_MONOTONIC = 1.
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            TimeNs.applyOffsets(Map.of("monotonic", off(42L, 12345L)));

            fm.verify(() -> Fs.writeString(
                    eq("/proc/self/timens_offsets"),
                    eq("1 42 12345\n")));
        }
    }

    @Test
    void boottimeMapsToClockId7() {
        // CLOCK_BOOTTIME = 7.
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            TimeNs.applyOffsets(Map.of("boottime", off(7L, 0L)));

            fm.verify(() -> Fs.writeString(
                    eq("/proc/self/timens_offsets"),
                    eq("7 7 0\n")));
        }
    }

    @Test
    void unsupportedClockIsSkippedAndDoesNotWrite() {
        // "realtime" is explicitly forbidden by the kernel for timens, so the spec
        // limits us to monotonic/boottime. Anything else must be ignored.
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            TimeNs.applyOffsets(Map.of("realtime", off(1L, 0L)));
            fm.verify(() -> Fs.writeString(anyString(), anyString()), never());
        }
    }

    @Test
    void mixedSupportedAndUnsupportedOnlyEmitsSupported() {
        // The kernel write is a single atomic call. So even if some entries are
        // garbage we still write the valid ones in one go.
        Map<String, Spec.TimeOffset> in = new LinkedHashMap<>();
        in.put("monotonic", off(10L, 1L));
        in.put("nonsense",  off(99L, 99L));
        in.put("boottime",  off(20L, 2L));

        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            TimeNs.applyOffsets(in);

            // LinkedHashMap preserves insertion order, so monotonic precedes boottime.
            fm.verify(() -> Fs.writeString(
                    eq("/proc/self/timens_offsets"),
                    eq("1 10 1\n7 20 2\n")));
        }
    }

    @Test
    void allEntriesUnsupportedSkipsWriteEntirely() {
        // If after filtering nothing remains, we must NOT write an empty string.
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            Map<String, Spec.TimeOffset> in = new LinkedHashMap<>();
            in.put("realtime", off(1L, 0L));
            in.put("garbage",  off(2L, 0L));

            TimeNs.applyOffsets(in);

            fm.verify(() -> Fs.writeString(anyString(), anyString()), never());
        }
    }

    @Test
    void writeFailureIsLoggedNotPropagated() {
        // EACCES / EBUSY (the kernel rejects the write after any fork) must
        // surface as a debug log, not a thrown exception. Throwing here would
        // kill init startup over a non-fatal feature.
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            fm.when(() -> Fs.writeString(anyString(), anyString()))
                    .thenThrow(new IOException("EBUSY"));

            assertDoesNotThrow(() -> TimeNs.applyOffsets(
                    Map.of("monotonic", off(0L, 0L))));
        }
    }
}
