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

public final class Rootfs {
    private Rootfs() {}

    private static final String[] DEVICES = {"null", "zero", "random", "urandom", "tty", "full"};

    public static void prepare(String rootfsPath, Spec spec) {
        try (Arena arena = Arena.ofConfined()) {
            if (PosixIO.access(arena, rootfsPath, Constants.F_OK) != 0) {
                throw new RuntimeException("rootfs not found: " + rootfsPath);
            }

            Logger.debug("set / propagation to slave");
            if (Libc.mount(arena, null, "/", null,
                    Constants.MS_SLAVE | Constants.MS_REC, null) != 0) {
                Logger.warn("mount / MS_SLAVE failed: " + Libc.strerror(Libc.errno()));
            }

            // Always bind-mount the rootfs to itself so it becomes its own mount,
            // which pivot_root requires. For overlay rootfs (containerd) this is also
            // important to detach the mount from the host's shared propagation.
            Logger.debug("bind mount rootfs: " + rootfsPath);
            if (Libc.mount(arena, rootfsPath, rootfsPath, null,
                    Constants.MS_BIND | Constants.MS_REC, null) != 0) {
                throw new RuntimeException("bind mount rootfs failed: " + Libc.strerror(Libc.errno()));
            }
            // pivot_root requires the new root and its parent to not have MS_SHARED
            // propagation. Force the rootfs mount to private so it satisfies the rule
            // regardless of what the host had.
            if (Libc.mount(arena, null, rootfsPath, null,
                    Constants.MS_PRIVATE, null) != 0) {
                Logger.debug("set rootfs private failed: " + Libc.strerror(Libc.errno()));
            }
            // Remount rootfs with MS_NOSUID so setuid binaries inside the container
            // can't gain extra privileges through the host's mount layer.
            if (Libc.mount(arena, null, rootfsPath, null,
                    Constants.MS_BIND | Constants.MS_REMOUNT | Constants.MS_NOSUID, null) != 0) {
                Logger.debug("rootfs MS_NOSUID remount failed: " + Libc.strerror(Libc.errno()));
            } else {
                Logger.debug("rootfs marked MS_NOSUID");
            }

            mountProc(arena, rootfsPath);
            mountDev(arena, rootfsPath);
            mountSys(arena, rootfsPath, spec);

            if (spec.mounts != null) {
                applyOciMounts(arena, rootfsPath, spec.mounts);
            }
        }
    }

    private static void mountProc(Arena arena, String rootfsPath) {
        String p = rootfsPath + "/proc";
        if (PosixIO.access(arena, p, Constants.F_OK) != 0) {
            try { Files.createDirectories(Path.of(p)); }
            catch (IOException e) { Logger.warn("mkdir proc: " + e.getMessage()); return; }
        }
        if (Libc.mount(arena, "proc", p, "proc",
                Constants.MS_NOSUID | Constants.MS_NODEV | Constants.MS_NOEXEC, null) != 0) {
            Logger.warn("mount /proc: " + Libc.strerror(Libc.errno()));
        } else {
            Logger.debug("mounted /proc");
        }
    }

    private static void mountDev(Arena arena, String rootfsPath) {
        String dev = rootfsPath + "/dev";
        if (PosixIO.access(arena, dev, Constants.F_OK) != 0) {
            try { Files.createDirectories(Path.of(dev)); }
            catch (IOException e) { Logger.warn("mkdir dev: " + e.getMessage()); return; }
        }
        if (Libc.mount(arena, "tmpfs", dev, "tmpfs",
                Constants.MS_NOSUID | Constants.MS_NOEXEC, "mode=755") != 0) {
            Logger.warn("mount /dev tmpfs: " + Libc.strerror(Libc.errno()));
            return;
        }
        Logger.debug("mounted /dev (tmpfs)");
        for (String d : DEVICES) bindDevice(arena, dev, d);

        // /dev/shm
        String shm = dev + "/shm";
        try { Files.createDirectories(Path.of(shm)); } catch (IOException ignored) {}
        if (Libc.mount(arena, "shm", shm, "tmpfs",
                Constants.MS_NOSUID | Constants.MS_NODEV | Constants.MS_NOEXEC,
                "mode=1777,size=65536k") != 0) {
            Logger.warn("mount /dev/shm: " + Libc.strerror(Libc.errno()));
        } else {
            Logger.debug("mounted /dev/shm");
        }
        // /dev/pts and /dev/ptmx
        String pts = dev + "/pts";
        try { Files.createDirectories(Path.of(pts)); } catch (IOException ignored) {}
        if (Libc.mount(arena, "devpts", pts, "devpts",
                Constants.MS_NOSUID | Constants.MS_NOEXEC, "newinstance,ptmxmode=0666,mode=0620") != 0) {
            Logger.debug("mount /dev/pts: " + Libc.strerror(Libc.errno()));
        }
    }

    private static void bindDevice(Arena arena, String devPath, String name) {
        String target = devPath + "/" + name;
        int fd = PosixIO.open(arena, target, Constants.O_RDWR | Constants.O_CREAT, 0666);
        if (fd >= 0) PosixIO.close(fd);
        else if (Libc.errno() != Constants.EEXIST) {
            Logger.warn("create " + target + ": " + Libc.strerror(Libc.errno()));
            return;
        }
        if (Libc.mount(arena, "/dev/" + name, target, null,
                Constants.MS_BIND, null) != 0) {
            if (Libc.errno() != Constants.EBUSY) {
                Logger.warn("bind /dev/" + name + ": " + Libc.strerror(Libc.errno()));
            }
        } else {
            Logger.debug("bind mounted /dev/" + name);
        }
    }

    private static void mountSys(Arena arena, String rootfsPath, Spec spec) {
        String sys = rootfsPath + "/sys";
        if (PosixIO.access(arena, sys, Constants.F_OK) != 0) {
            try { Files.createDirectories(Path.of(sys)); }
            catch (IOException e) { Logger.warn("mkdir sys: " + e.getMessage()); return; }
        }
        long flags = Constants.MS_NOSUID | Constants.MS_NODEV | Constants.MS_NOEXEC | Constants.MS_RDONLY;
        if (Libc.mount(arena, "sysfs", sys, "sysfs", flags, null) != 0) {
            int e = Libc.errno();
            if (e == Constants.EPERM) {
                Logger.debug("sysfs mount EPERM (user ns?), bind from host /sys");
                if (Libc.mount(arena, "/sys", sys, null,
                        Constants.MS_BIND | Constants.MS_REC, null) != 0) {
                    Logger.warn("bind /sys: " + Libc.strerror(Libc.errno()));
                }
            } else {
                Logger.warn("mount /sys: " + Libc.strerror(e));
            }
        } else {
            Logger.debug("mounted /sys");
        }

        // /sys/fs/cgroup
        String cg = sys + "/fs/cgroup";
        try { Files.createDirectories(Path.of(cg)); } catch (IOException ignored) {}
        String containerCgPath = spec != null && spec.linux != null && spec.linux.cgroupsPath != null
                ? (spec.linux.cgroupsPath.startsWith("/") ? spec.linux.cgroupsPath
                                                          : "/" + spec.linux.cgroupsPath)
                : readContainerCgroupPath();
        if (containerCgPath != null) {
            String src = "/sys/fs/cgroup" + containerCgPath;
            if (PosixIO.access(arena, src, Constants.F_OK) == 0) {
                if (Libc.mount(arena, src, cg, null,
                        Constants.MS_BIND | Constants.MS_REC, null) == 0) {
                    Libc.mount(arena, null, cg, null,
                            Constants.MS_BIND | Constants.MS_REMOUNT | Constants.MS_RDONLY
                                    | Constants.MS_NOSUID | Constants.MS_NODEV | Constants.MS_NOEXEC,
                            null);
                    Logger.debug("bound /sys/fs/cgroup from " + src);
                } else {
                    Logger.warn("bind /sys/fs/cgroup from " + src + ": " + Libc.strerror(Libc.errno()));
                }
            } else {
                Logger.debug("cgroup source " + src + " does not exist (yet)");
            }
        }
    }

    private static String readContainerCgroupPath() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/cgroup"))) {
                if (line.startsWith("0::")) {
                    String p = line.substring(3).trim();
                    return p.isEmpty() ? null : p;
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    private static boolean isMountPoint(String path) {
        try {
            Path p = Path.of(path);
            Path parent = p.getParent();
            if (parent == null) return true;
            return Files.getFileStore(p).equals(Files.getFileStore(parent)) ? false : true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void applyOciMounts(Arena arena, String rootfsPath, List<Spec.Mount> mounts) {
        for (Spec.Mount m : mounts) {
            if (m.destination == null) continue;
            // skip already-handled paths
            if (m.destination.equals("/proc") || m.destination.equals("/dev")
                || m.destination.equals("/sys") || m.destination.equals("/dev/shm")
                || m.destination.equals("/dev/pts") || m.destination.equals("/dev/mqueue")
                || m.destination.equals("/sys/fs/cgroup")) continue;
            String target = rootfsPath + m.destination;
            String type = m.type != null ? m.type : "none";
            boolean isBind = m.options != null && (m.options.contains("bind") || m.options.contains("rbind"));
            long flags = 0;
            long propagation = 0;
            StringBuilder data = new StringBuilder();
            if (m.options != null) {
                for (String o : m.options) {
                    switch (o) {
                        case "bind": flags |= Constants.MS_BIND; break;
                        case "rbind": flags |= Constants.MS_BIND | Constants.MS_REC; break;
                        case "ro": flags |= Constants.MS_RDONLY; break;
                        case "nosuid": flags |= Constants.MS_NOSUID; break;
                        case "noexec": flags |= Constants.MS_NOEXEC; break;
                        case "nodev": flags |= Constants.MS_NODEV; break;
                        case "noatime": flags |= Constants.MS_NOATIME; break;
                        case "relatime": flags |= Constants.MS_RELATIME; break;
                        case "strictatime": flags |= Constants.MS_STRICTATIME; break;
                        case "nosymfollow": flags |= Constants.MS_NOSYMFOLLOW; break;
                        case "rec": flags |= Constants.MS_REC; break;
                        case "shared":      propagation = Constants.MS_SHARED;      break;
                        case "rshared":     propagation = Constants.MS_SHARED | Constants.MS_REC; break;
                        case "slave":       propagation = Constants.MS_SLAVE;       break;
                        case "rslave":      propagation = Constants.MS_SLAVE | Constants.MS_REC; break;
                        case "private":     propagation = Constants.MS_PRIVATE;     break;
                        case "rprivate":    propagation = Constants.MS_PRIVATE | Constants.MS_REC; break;
                        case "unbindable":  propagation = Constants.MS_UNBINDABLE;  break;
                        case "runbindable": propagation = Constants.MS_UNBINDABLE | Constants.MS_REC; break;
                        default:
                            if (data.length() > 0) data.append(",");
                            data.append(o);
                    }
                }
            }
            try { Files.createDirectories(Path.of(target)); } catch (IOException ignored) {}

            // Id-mapped mounts: if uidMappings/gidMappings are present we route the
            // bind through open_tree + mount_setattr(MOUNT_ATTR_IDMAP) + move_mount.
            if (m.uidMappings != null && !m.uidMappings.isEmpty() && isBind) {
                if (IdmapHelper.apply(m, target)) {
                    Logger.debug("idmap mounted " + m.destination);
                    continue;
                }
                Logger.warn("idmap mount failed for " + m.destination + ", falling back to plain bind");
            }

            int rc = Libc.mount(arena, m.source, target, isBind ? null : type, flags,
                    data.length() > 0 ? data.toString() : null);
            if (rc != 0) {
                Logger.debug("optional mount " + m.destination + " failed: "
                        + Libc.strerror(Libc.errno()));
                continue;
            }
            Logger.debug("mounted " + m.destination + " (type=" + type + ")");
            // bind mounts ignore MS_RDONLY (and other access flags) on the initial
            // mount; the kernel just bind-attaches the source as-is. A second
            // MS_BIND|MS_REMOUNT with the desired flags is required to actually
            // enforce read-only / nosuid / nodev / noexec on the bind.
            if (isBind && (flags & (Constants.MS_RDONLY | Constants.MS_NOSUID
                    | Constants.MS_NODEV | Constants.MS_NOEXEC)) != 0) {
                long remountFlags = Constants.MS_BIND | Constants.MS_REMOUNT
                        | (flags & (Constants.MS_RDONLY | Constants.MS_NOSUID
                                  | Constants.MS_NODEV | Constants.MS_NOEXEC
                                  | Constants.MS_NOATIME | Constants.MS_RELATIME
                                  | Constants.MS_STRICTATIME | Constants.MS_NOSYMFOLLOW));
                if (Libc.mount(arena, null, target, null, remountFlags, null) != 0) {
                    Logger.debug("bind remount with access flags on " + m.destination
                            + " failed: " + Libc.strerror(Libc.errno()));
                }
            }
            // Apply per-mount propagation if requested. propagation flag has to be set
            // alone via a second mount() call.
            if (propagation != 0) {
                if (Libc.mount(arena, null, target, null, propagation, null) != 0) {
                    Logger.debug("propagation set on " + m.destination + " failed: "
                            + Libc.strerror(Libc.errno()));
                }
            }
        }
    }

    public static void pivot(String newRoot) {
        try (Arena arena = Arena.ofConfined()) {
            Logger.debug("pivot_root to " + newRoot);
            int newrootFd = PosixIO.open(arena, newRoot,
                    Constants.O_DIRECTORY | Constants.O_RDONLY, 0);
            if (newrootFd < 0) {
                throw new RuntimeException("open " + newRoot + ": " + Libc.strerror(Libc.errno()));
            }
            try {
                if (Libc.pivotRoot(arena, newRoot, newRoot) != 0) {
                    throw new RuntimeException("pivot_root: " + Libc.strerror(Libc.errno()));
                }
                if (Libc.mount(arena, null, "/", null,
                        Constants.MS_SLAVE | Constants.MS_REC, null) != 0) {
                    Logger.warn("remount / as slave failed: " + Libc.strerror(Libc.errno()));
                }
                if (Libc.umount2(arena, "/", Constants.MNT_DETACH) != 0) {
                    Logger.warn("umount2 / failed: " + Libc.strerror(Libc.errno()));
                }
                if (PosixIO.fchdir(newrootFd) != 0) {
                    throw new RuntimeException("fchdir: " + Libc.strerror(Libc.errno()));
                }
            } finally {
                PosixIO.close(newrootFd);
            }
            if (Libc.chdir(arena, "/") != 0) {
                throw new RuntimeException("chdir /: " + Libc.strerror(Libc.errno()));
            }
            Logger.debug("pivot_root completed");
        }
    }

    public static void setRootReadonly() {
        try (Arena arena = Arena.ofConfined()) {
            // Preserve MS_NOSUID we set earlier — MS_REMOUNT replaces the flag set
            // wholesale, so we must include every flag we want to keep on.
            long flags = Constants.MS_BIND | Constants.MS_REMOUNT
                    | Constants.MS_RDONLY | Constants.MS_NOSUID;
            if (Libc.mount(arena, null, "/", null, flags, null) != 0) {
                Logger.warn("remount / readonly failed: " + Libc.strerror(Libc.errno()));
            } else {
                Logger.debug("/ set readonly+nosuid");
            }
        }
    }

    /**
     * Mask sensitive paths by bind-mounting /dev/null over files and a tmpfs over
     * directories. Used to hide /proc/kcore etc.
     */
    public static void maskPaths(List<String> paths) {
        if (paths == null) return;
        try (Arena arena = Arena.ofConfined()) {
            for (String p : paths) {
                int rc = Libc.mount(arena, "/dev/null", p, null, Constants.MS_BIND, null);
                if (rc == 0) {
                    Logger.debug("masked " + p + " with /dev/null");
                    continue;
                }
                int err = Libc.errno();
                if (err == Constants.ENOENT) continue; // skip nonexistent
                // Likely a directory — mask with tmpfs.
                rc = Libc.mount(arena, "tmpfs", p, "tmpfs", Constants.MS_RDONLY, null);
                if (rc != 0) {
                    Logger.debug("mask " + p + " failed: " + Libc.strerror(Libc.errno()));
                } else {
                    Logger.debug("masked " + p + " with tmpfs");
                }
            }
        }
    }

    /** Bind-remount each path read-only. Used for /proc/bus, /proc/sys etc. */
    public static void readonlyRemount(List<String> paths) {
        if (paths == null) return;
        try (Arena arena = Arena.ofConfined()) {
            for (String p : paths) {
                // First bind it to itself so we can remount RO without affecting host.
                if (Libc.mount(arena, p, p, null, Constants.MS_BIND | Constants.MS_REC, null) != 0) {
                    int err = Libc.errno();
                    if (err == Constants.ENOENT) continue;
                    Logger.debug("rebind " + p + ": " + Libc.strerror(err));
                    continue;
                }
                long flags = Constants.MS_BIND | Constants.MS_REC | Constants.MS_REMOUNT
                        | Constants.MS_RDONLY;
                if (Libc.mount(arena, p, p, null, flags, null) != 0) {
                    Logger.debug("readonly remount " + p + ": " + Libc.strerror(Libc.errno()));
                } else {
                    Logger.debug("readonly " + p);
                }
            }
        }
    }
}
