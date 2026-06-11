package com.ternbusty.takoyaki.sysctl;

import com.ternbusty.takoyaki.syscall.Fs;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SysctlTest {

    @Test
    void nullMapIsNoOp() {
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            Sysctl.apply(null);
            fm.verifyNoInteractions();
        }
    }

    @Test
    void emptyMapIsNoOp() {
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            Sysctl.apply(Map.of());
            fm.verifyNoInteractions();
        }
    }

    @Test
    void dotsInKeyAreTurnedIntoSlashes() {
        // OCI sysctl keys use dots (net.ipv4.ip_forward) but the kernel's
        // virtual files use slashes (/proc/sys/net/ipv4/ip_forward). This
        // translation is the entire job of Sysctl.apply.
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            Sysctl.apply(Map.of("net.ipv4.ip_forward", "1"));

            fm.verify(() -> Fs.writeString(
                    eq("/proc/sys/net/ipv4/ip_forward"),
                    eq("1")));
        }
    }

    @Test
    void writeFailureIsLoggedNotPropagated() {
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            fm.when(() -> Fs.writeString(anyString(), anyString()))
                    .thenThrow(new IOException("EROFS"));

            // The runtime warns and continues, not crash, when a sysctl is denied
            // (host kernel rejects, namespace forbids, ...).
            assertDoesNotThrow(() -> Sysctl.apply(Map.of("kernel.something", "1")));
        }
    }

    @Test
    void allEntriesAreAttemptedEvenIfOneFails() {
        // Use LinkedHashMap so we control ordering of the assertions below.
        Map<String, String> in = new LinkedHashMap<>();
        in.put("net.ipv4.bad_key",   "1");
        in.put("net.ipv4.good_key", "2");

        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            fm.when(() -> Fs.writeString(eq("/proc/sys/net/ipv4/bad_key"), anyString()))
                    .thenThrow(new IOException("EROFS"));

            Sysctl.apply(in);

            // Even though the first entry threw, the loop must have attempted
            // the second one.
            fm.verify(() -> Fs.writeString(
                    eq("/proc/sys/net/ipv4/good_key"),
                    eq("2")));
        }
    }
}
