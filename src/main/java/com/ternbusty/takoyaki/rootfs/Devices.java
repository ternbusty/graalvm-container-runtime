package com.ternbusty.takoyaki.rootfs;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.syscall.Constants;
import com.ternbusty.takoyaki.syscall.Fs;
import com.ternbusty.takoyaki.syscall.PosixIO;
import com.ternbusty.takoyaki.syscall.SyscallHost;
import com.ternbusty.takoyaki.syscall.Syscalls;

import java.lang.foreign.Arena;
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
        Syscalls sc = SyscallHost.current();
        try (Arena arena = Arena.ofConfined()) {
            for (Spec.LinuxDevice d : devices) {
                if (d.path == null || d.type == null) continue;
                String target = rootfsPath + d.path;
                try { Fs.createDirectories(Fs.parent(target)); }
                catch (Exception ignored) {}
                int typeBits = typeBits(d.type);
                if (typeBits == 0) { Logger.warn("unsupported device type: " + d.type); continue; }
                int mode = (d.fileMode == null ? 0666 : d.fileMode.intValue()) | typeBits;
                long dev = makedev(
                        d.major == null ? 0 : d.major.longValue(),
                        d.minor == null ? 0 : d.minor.longValue());

                int rc = sc.mknod(target, mode, dev);
                if (rc == 0) {
                    // mknod respects the umask, so a spec fileMode like 0660 lands
                    // as 0640 with the default 0022 umask. Re-chmod to the requested
                    // mode (the type bits aren't allowed in chmod, so mask them out).
                    if (d.fileMode != null) {
                        int chmodRc = Fs.chmod(target, d.fileMode.intValue() & 0777);
                        if (chmodRc != 0) {
                            Logger.debug("chmod " + d.path + " failed");
                        }
                    }
                    Logger.debug("mknod " + d.path + " (" + d.type + " " + d.major + ":" + d.minor + ")");
                    continue;
                }
                // mknod denied (e.g. user namespace) - fall back to bind mount from host.
                String hostPath = d.path;
                if (sc.access(hostPath, Constants.F_OK) != 0) {
                    Logger.debug("device " + d.path + " not on host either, skipping");
                    continue;
                }
                int fd = PosixIO.open(arena, target, Constants.O_RDWR | Constants.O_CREAT, 0666);
                if (fd >= 0) PosixIO.close(fd);
                if (sc.mount(hostPath, target, null, Constants.MS_BIND, null) != 0) {
                    Logger.debug("bind " + d.path + " from host failed: " + sc.strerror(sc.errno()));
                } else {
                    Logger.debug("bind mounted " + d.path + " from host");
                }
            }
        }
    }

    /**
     * Translate the spec's device-type letter into the kernel's S_IF* type bits
     * that mknod(2) wants OR'd into the mode argument. Returns 0 for unknown
     * types so the caller can skip with a warning. Package-visible for tests.
     */
    static int typeBits(String type) {
        return switch (type) {
            case "c", "u" -> Constants.S_IFCHR;
            case "b"      -> Constants.S_IFBLK;
            case "p"      -> Constants.S_IFIFO;
            default       -> 0;
        };
    }

    /** Encode (major, minor) into a Linux dev_t (glibc convention). Package-visible for tests. */
    static long makedev(long major, long minor) {
        return ((major & 0xfffff000L) << 32)
             | ((major & 0x00000fffL) << 8)
             | ((minor & 0xffffff00L) << 12)
             | (minor & 0x000000ffL);
    }
}
