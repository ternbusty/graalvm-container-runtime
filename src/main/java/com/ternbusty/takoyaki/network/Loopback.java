package com.ternbusty.takoyaki.network;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.syscall.Constants;
import com.ternbusty.takoyaki.syscall.Libc;
import com.ternbusty.takoyaki.syscall.PosixIO;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Bring up the lo interface inside the container's network namespace.
 *
 * Layout of struct ifreq (relevant fields):
 *   char ifr_name[IFNAMSIZ];   // 16 bytes
 *   short ifr_flags;           // at offset 16
 */
public final class Loopback {
    private static final int IFNAMSIZ = 16;
    // ifreq union is much larger but only ifr_name + a short flags is needed for SIOCSIFFLAGS.
    private static final int IFREQ_SIZE = 40;

    private Loopback() {}

    public static void up() {
        try (Arena arena = Arena.ofConfined()) {
            int fd = PosixIO.socket(Constants.AF_INET, Constants.SOCK_DGRAM, 0);
            if (fd < 0) {
                Logger.debug("loopback: socket failed: " + Libc.strerror(Libc.errno()));
                return;
            }
            try {
                MemorySegment ifr = arena.allocate(IFREQ_SIZE, 8);
                byte[] name = "lo\0".getBytes();
                for (int i = 0; i < name.length && i < IFNAMSIZ; i++) {
                    ifr.set(ValueLayout.JAVA_BYTE, i, name[i]);
                }
                // Get current flags
                if (Libc.ioctl(fd, Constants.SIOCGIFFLAGS, ifr) != 0) {
                    Logger.debug("loopback: SIOCGIFFLAGS failed: " + Libc.strerror(Libc.errno()));
                    return;
                }
                short flags = ifr.get(ValueLayout.JAVA_SHORT, IFNAMSIZ);
                flags |= (short) Constants.IFF_UP;
                ifr.set(ValueLayout.JAVA_SHORT, IFNAMSIZ, flags);
                if (Libc.ioctl(fd, Constants.SIOCSIFFLAGS, ifr) != 0) {
                    Logger.debug("loopback: SIOCSIFFLAGS failed: " + Libc.strerror(Libc.errno()));
                    return;
                }
                Logger.debug("loopback: lo is up");
            } finally {
                PosixIO.close(fd);
            }
        }
    }
}
