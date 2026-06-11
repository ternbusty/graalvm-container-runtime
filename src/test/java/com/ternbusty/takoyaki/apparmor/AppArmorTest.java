package com.ternbusty.takoyaki.apparmor;

import com.ternbusty.takoyaki.syscall.Fs;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AppArmorTest {

    @Test
    void nullProfileIsNoOp() {
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            AppArmor.apply(null);
            fm.verifyNoInteractions();
        }
    }

    @Test
    void emptyProfileIsNoOp() {
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            AppArmor.apply("");
            fm.verifyNoInteractions();
        }
    }

    @Test
    void unconfinedSentinelIsNoOp() {
        // OCI spec says apparmorProfile="unconfined" means *do nothing*.
        // We must NOT treat it as a real profile name and try to load it.
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            AppArmor.apply("unconfined");
            fm.verifyNoInteractions();
        }
    }

    @Test
    void neitherAttrPathExistsLogsWarnButDoesNotThrow() {
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            fm.when(() -> Fs.exists(anyString())).thenReturn(false);
            assertDoesNotThrow(() -> AppArmor.apply("test-profile"));
            // Both candidate paths must have been probed.
            fm.verify(() -> Fs.exists(eq("/proc/self/attr/apparmor/exec")));
            fm.verify(() -> Fs.exists(eq("/proc/self/attr/exec")));
        }
    }
}
