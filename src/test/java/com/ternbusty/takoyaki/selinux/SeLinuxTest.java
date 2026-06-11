package com.ternbusty.takoyaki.selinux;

import com.ternbusty.takoyaki.syscall.Fs;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeLinuxTest {

    @Test
    void nullLabelIsNoOp() {
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            SeLinux.apply(null);
            fm.verifyNoInteractions();
        }
    }

    @Test
    void emptyLabelIsNoOp() {
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            SeLinux.apply("");
            fm.verifyNoInteractions();
        }
    }

    @Test
    void selinuxNotMountedIsSkippedSilently() {
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            fm.when(() -> Fs.exists(anyString())).thenReturn(false);
            SeLinux.apply("system_u:system_r:container_t:s0");
            // Skip path: nothing should have been written.
            fm.verify(() -> Fs.writeString(anyString(), anyString()), never());
        }
    }

    @Test
    void labelIsWrittenToAttrExecWhenSelinuxIsMounted() {
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            fm.when(() -> Fs.exists(eq("/sys/fs/selinux"))).thenReturn(true);

            SeLinux.apply("system_u:system_r:container_t:s0");

            fm.verify(() -> Fs.writeString(
                    eq("/proc/self/attr/exec"),
                    eq("system_u:system_r:container_t:s0")));
        }
    }

    @Test
    void writeFailureIsLoggedNotPropagated() {
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            fm.when(() -> Fs.exists(anyString())).thenReturn(true);
            fm.when(() -> Fs.writeString(anyString(), anyString()))
                    .thenThrow(new java.io.IOException("EPERM"));
            assertDoesNotThrow(() -> SeLinux.apply("label"));
        }
    }

    @Test
    void applyKeyCreateNullIsNoOp() {
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            SeLinux.applyKeyCreate(null);
            SeLinux.applyKeyCreate("");
            fm.verifyNoInteractions();
        }
    }
}
