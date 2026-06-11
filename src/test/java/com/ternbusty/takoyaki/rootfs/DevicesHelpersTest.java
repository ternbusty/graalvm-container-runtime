package com.ternbusty.takoyaki.rootfs;

import com.ternbusty.takoyaki.syscall.Constants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure helpers extracted out of Devices.create. typeBits maps the spec's
 * single-letter device type to the kernel's S_IF* bits.
 *
 * <p>permsForMode was removed when the chmod step switched from
 * {@code Files.setPosixFilePermissions} (which pulled in
 * {@code java.nio.file}) to a direct {@code Fs.chmod(path, octal)} call.
 */
class DevicesHelpersTest {

    @Test
    void charDeviceMapsToSIfchr() {
        assertEquals(Constants.S_IFCHR, Devices.typeBits("c"));
    }

    @Test
    void unbufferedCharDeviceAlsoMapsToSIfchr() {
        assertEquals(Constants.S_IFCHR, Devices.typeBits("u"));
    }

    @Test
    void blockDeviceMapsToSIfblk() {
        assertEquals(Constants.S_IFBLK, Devices.typeBits("b"));
    }

    @Test
    void fifoMapsToSIfifo() {
        assertEquals(Constants.S_IFIFO, Devices.typeBits("p"));
    }

    @Test
    void unknownTypeReturnsZeroAsSkipSentinel() {
        assertEquals(0, Devices.typeBits(""));
        assertEquals(0, Devices.typeBits("socket"));
        assertEquals(0, Devices.typeBits("garbage"));
    }
}
