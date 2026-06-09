package com.ternbusty.takoyaki.rootfs;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.syscall.Constants;
import com.ternbusty.takoyaki.syscall.Libc;
import com.ternbusty.takoyaki.syscall.PosixIO;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Create a temporary user namespace populated with the given uid/gid mappings,
 * return its /proc/<helper>/ns/user fd, and apply MOUNT_ATTR_IDMAP to a clone of the
 * source path before move-mounting it to the destination.
 *
 * Mirrors what runc and youki do for per-mount id-mapped mounts.
 */
public final class IdmapHelper {
    private IdmapHelper() {}

    /** Apply an id-mapped bind mount from {@code source} to {@code destination}. */
    public static boolean apply(Spec.Mount m, String destination) {
        if (m.uidMappings == null || m.uidMappings.isEmpty()) return false;
        int usernsFd = openMappedUserNs(m.uidMappings, m.gidMappings);
        if (usernsFd < 0) return false;
        try {
            return IdmapMount.apply(m.source, usernsFd, destination);
        } finally {
            PosixIO.close(usernsFd);
        }
    }

    /**
     * Spawn a helper child via fork+unshare(CLONE_NEWUSER), write the mappings to
     * /proc/<child>/uid_map and /proc/<child>/gid_map from the parent, then keep
     * /proc/<child>/ns/user open in the parent. The child blocks on a pipe until
     * the parent has finished.
     */
    private static int openMappedUserNs(List<Spec.IdMapping> uidMaps,
                                        List<Spec.IdMapping> gidMaps) {
        try (Arena arena = Arena.ofConfined()) {
            int[] sync = new int[2];
            if (PosixIO.socketpair(arena, Constants.AF_UNIX, Constants.SOCK_STREAM, 0, sync) < 0) {
                Logger.warn("idmap helper socketpair failed: " + Libc.strerror(Libc.errno()));
                return -1;
            }
            int pid = PosixIO.fork();
            if (pid < 0) {
                Logger.warn("idmap helper fork failed: " + Libc.strerror(Libc.errno()));
                return -1;
            }
            if (pid == 0) {
                PosixIO.close(sync[0]);
                Libc.unshare(Constants.CLONE_NEWUSER);
                // Tell parent we're in the new userns, then wait for it to finish.
                try (Arena a2 = Arena.ofConfined()) {
                    byte[] one = new byte[]{1};
                    PosixIO.write(a2, sync[1], one);
                    byte[] go = new byte[1];
                    PosixIO.read(a2, sync[1], go);
                }
                PosixIO._exit(0);
                return -1;
            }
            PosixIO.close(sync[1]);
            try (Arena a2 = Arena.ofConfined()) {
                byte[] one = new byte[1];
                PosixIO.read(a2, sync[0], one);
            }
            // Write maps from the parent's privileged context.
            writeMappings(pid, uidMaps, "uid_map");
            writeMappings(pid, gidMaps, "gid_map");
            int fd = PosixIO.open(arena, "/proc/" + pid + "/ns/user", Constants.O_RDONLY, 0);
            // Release helper child.
            try (Arena a2 = Arena.ofConfined()) {
                byte[] go = new byte[]{1};
                PosixIO.write(a2, sync[0], go);
            }
            PosixIO.close(sync[0]);
            if (fd < 0) {
                Logger.warn("open helper userns fd failed: " + Libc.strerror(Libc.errno()));
            }
            return fd;
        }
    }

    private static void writeMappings(int pid, List<Spec.IdMapping> maps, String file) {
        if (maps == null || maps.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (Spec.IdMapping m : maps) {
            sb.append(m.containerID).append(' ')
              .append(m.hostID).append(' ')
              .append(m.size).append('\n');
        }
        try {
            Files.writeString(Path.of("/proc/" + pid + "/" + file), sb.toString());
        } catch (IOException e) {
            Logger.warn("write /proc/" + pid + "/" + file + " failed: " + e.getMessage());
        }
    }
}
