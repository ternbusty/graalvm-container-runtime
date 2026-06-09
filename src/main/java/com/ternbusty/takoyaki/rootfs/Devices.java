package com.ternbusty.takoyaki.rootfs;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.syscall.Constants;
import com.ternbusty.takoyaki.syscall.Libc;
import com.ternbusty.takoyaki.syscall.PosixIO;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Create additional devices declared in spec.linux.devices.
 *
 * In a user namespace mknod(2) is typically denied; we fall back to bind-mounting
 * the host device file the same way the default /dev/null etc. are handled.
 */
public final class Devices {
    private Devices() {}

    public static void create(String rootfsPath, List<Spec.LinuxDevice> devices) {
        if (devices == null || devices.isEmpty()) return;
        try (Arena arena = Arena.ofConfined()) {
            for (Spec.LinuxDevice d : devices) {
                if (d.path == null || d.type == null) continue;
                String target = rootfsPath + d.path;
                try { Files.createDirectories(Path.of(target).getParent()); }
                catch (Exception ignored) {}
                int typeBits = switch (d.type) {
                    case "c", "u" -> Constants.S_IFCHR;
                    case "b" -> Constants.S_IFBLK;
                    case "p" -> Constants.S_IFIFO;
                    default -> { Logger.warn("unsupported device type: " + d.type); yield 0; }
                };
                if (typeBits == 0) continue;
                int mode = (d.fileMode == null ? 0666 : d.fileMode.intValue()) | typeBits;
                long dev = makedev(
                        d.major == null ? 0 : d.major.longValue(),
                        d.minor == null ? 0 : d.minor.longValue());

                int rc = Libc.mknod(arena, target, mode, dev);
                if (rc == 0) {
                    Logger.debug("mknod " + d.path + " (" + d.type + " " + d.major + ":" + d.minor + ")");
                    continue;
                }
                // mknod denied (e.g. user namespace) - fall back to bind mount from host.
                String hostPath = d.path;
                if (PosixIO.access(arena, hostPath, Constants.F_OK) != 0) {
                    Logger.debug("device " + d.path + " not on host either, skipping");
                    continue;
                }
                int fd = PosixIO.open(arena, target, Constants.O_RDWR | Constants.O_CREAT, 0666);
                if (fd >= 0) PosixIO.close(fd);
                if (Libc.mount(arena, hostPath, target, null, Constants.MS_BIND, null) != 0) {
                    Logger.debug("bind " + d.path + " from host failed: " + Libc.strerror(Libc.errno()));
                } else {
                    Logger.debug("bind mounted " + d.path + " from host");
                }
            }
        }
    }

    /** Encode (major, minor) into a Linux dev_t (glibc convention). */
    private static long makedev(long major, long minor) {
        return ((major & 0xfffff000L) << 32)
             | ((major & 0x00000fffL) << 8)
             | ((minor & 0xffffff00L) << 12)
             | (minor & 0x000000ffL);
    }
}
