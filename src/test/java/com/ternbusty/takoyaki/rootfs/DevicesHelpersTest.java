package com.ternbusty.takoyaki.rootfs;

import com.ternbusty.takoyaki.syscall.Constants;
import org.junit.jupiter.api.Test;

import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure helpers extracted out of Devices.create. typeBits maps the spec's
 * single-letter device type to the kernel's S_IF* bits; permsForMode does
 * the awkward "9 bits to PosixFilePermission set" translation that previously
 * lived as 9 inline if-statements.
 */
class DevicesHelpersTest {

    // ---- typeBits -----------------------------------------------------------

    @Test
    void charDeviceMapsToSIfchr() {
        // "c" is the standard char device letter, used by /dev/null /dev/zero
        // /dev/random etc. Must produce S_IFCHR.
        assertEquals(Constants.S_IFCHR, Devices.typeBits("c"));
    }

    @Test
    void unbufferedCharDeviceAlsoMapsToSIfchr() {
        // "u" is the OCI spec's "unbuffered char device" — the kernel treats
        // it identically to "c" for mknod purposes. Tested separately to pin
        // the alias relationship.
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
        // The caller treats 0 as "skip this device with a warning". A bug
        // that returned -1 or threw would crash init on a typo'd spec.
        assertEquals(0, Devices.typeBits(""));
        assertEquals(0, Devices.typeBits("socket"));
        assertEquals(0, Devices.typeBits("garbage"));
    }

    // ---- permsForMode -------------------------------------------------------

    @Test
    void permsForMode0644IsOwnerRWAndOthersRead() {
        // Most common file mode. Sanity check it produces what Files API expects.
        var perms = Devices.permsForMode(0644);
        assertEquals(EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.OTHERS_READ
        ), perms);
    }

    @Test
    void permsForMode0666IsAllRW() {
        // The default for /dev/null and /dev/zero per the OCI default-device list.
        var perms = Devices.permsForMode(0666);
        assertEquals(EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE
        ), perms);
    }

    @Test
    void permsForMode0755IsExecBits() {
        var perms = Devices.permsForMode(0755);
        assertEquals(EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
        ), perms);
    }

    @Test
    void permsForMode0000IsEmpty() {
        // Edge case: mode=000 is a valid (if useless) permission. Must not
        // throw and must produce an empty set.
        assertTrue(Devices.permsForMode(0000).isEmpty());
    }

    @Test
    void permsForMode0777IsAll() {
        // All 9 bits set, all 9 PosixFilePermission values present.
        assertEquals(EnumSet.allOf(PosixFilePermission.class),
                Devices.permsForMode(0777));
    }

    @Test
    void permsForModeHighBitsAreIgnored() {
        // Caller masks with 0777 before calling, but defend in depth. The
        // sticky / setuid bits (0o4000, 0o2000, 0o1000) and the type bits
        // (S_IFCHR=0o20000 etc.) must not produce extra permissions.
        var lowOnly = Devices.permsForMode(0666);
        var withHighBits = Devices.permsForMode(0177666);
        assertEquals(lowOnly, withHighBits,
                "permsForMode must only look at the bottom 9 bits");
    }

    @Test
    void permsForMode0240IsGroupWriteOwnerWriteOnly() {
        // Weird mode to make sure each bit lights up its own perm independently.
        // 0o240 = 010 100 000 = OWNER_WRITE, GROUP_READ.
        var perms = Devices.permsForMode(0240);
        assertEquals(EnumSet.of(
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ
        ), perms);
    }
}
