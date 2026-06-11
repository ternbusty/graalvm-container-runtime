package com.ternbusty.takoyaki.rootfs;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.syscall.Constants;
import com.ternbusty.takoyaki.syscall.Fs;
import com.ternbusty.takoyaki.syscall.Libc;
import com.ternbusty.takoyaki.syscall.PosixIO;
import com.ternbusty.takoyaki.syscall.SyscallHost;
import com.ternbusty.takoyaki.syscall.Syscalls;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.util.List;

public final class Rootfs {
    private Rootfs() {}

    private static final String[] DEVICES = {"null", "zero", "random", "urandom", "tty", "full"};

    public static void prepare(String rootfsPath, Spec spec) {
        prepare(rootfsPath, spec, java.util.Collections.emptyMap());
    }

    public static void prepare(String rootfsPath, Spec spec,
                               java.util.Map<String, Integer> idmapFds) {
        try (Arena arena = Arena.ofConfined()) {
            if (PosixIO.access(arena, rootfsPath, Constants.F_OK) != 0) {
                throw new RuntimeException("rootfs not found: " + rootfsPath);
            }

            Logger.debug("set / propagation to slave");
            if (Libc.mount(arena, null, "/", null,
                    Constants.MS_SLAVE | Constants.MS_REC, null) != 0) {
                Logger.warn("mount / MS_SLAVE failed: " + Libc.strerror(Libc.errno()));
            }

            Logger.debug("bind mount rootfs: " + rootfsPath);
            if (Libc.mount(arena, rootfsPath, rootfsPath, null,
                    Constants.MS_BIND | Constants.MS_REC, null) != 0) {
                throw new RuntimeException("bind mount rootfs failed: " + Libc.strerror(Libc.errno()));
            }
            if (Libc.mount(arena, null, rootfsPath, null,
                    Constants.MS_PRIVATE, null) != 0) {
                Logger.debug("set rootfs private failed: " + Libc.strerror(Libc.errno()));
            }
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
                applyOciMounts(arena, rootfsPath, spec.mounts, idmapFds);
            }
        }
    }

    private static void mountProc(Arena arena, String rootfsPath) {
        String p = rootfsPath + "/proc";
        if (PosixIO.access(arena, p, Constants.F_OK) != 0) {
            try { Fs.createDirectories(p); }
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
            try { Fs.createDirectories(dev); }
            catch (IOException e) { Logger.warn("mkdir dev: " + e.getMessage()); return; }
        }
        if (Libc.mount(arena, "tmpfs", dev, "tmpfs",
                Constants.MS_NOSUID | Constants.MS_NOEXEC, "mode=755") != 0) {
            Logger.warn("mount /dev tmpfs: " + Libc.strerror(Libc.errno()));
            return;
        }
        Logger.debug("mounted /dev (tmpfs)");
        for (String d : DEVICES) bindDevice(arena, dev, d);

        String pts = dev + "/pts";
        try { Fs.createDirectories(pts); } catch (IOException ignored) {}
        if (Libc.mount(arena, "devpts", pts, "devpts",
                Constants.MS_NOSUID | Constants.MS_NOEXEC, "newinstance,ptmxmode=0666,mode=0620") != 0) {
            Logger.debug("mount /dev/pts: " + Libc.strerror(Libc.errno()));
        }

        String shm = dev + "/shm";
        try { Fs.createDirectories(shm); } catch (IOException ignored) {}
        if (Libc.mount(arena, "shm", shm, "tmpfs",
                Constants.MS_NOSUID | Constants.MS_NODEV | Constants.MS_NOEXEC,
                "mode=1777,size=65536k") != 0) {
            Logger.warn("mount /dev/shm: " + Libc.strerror(Libc.errno()));
        } else {
            Logger.debug("mounted /dev/shm");
        }

        String mqueue = dev + "/mqueue";
        try { Fs.createDirectories(mqueue); } catch (IOException ignored) {}
        if (Libc.mount(arena, "mqueue", mqueue, "mqueue",
                Constants.MS_NOSUID | Constants.MS_NODEV | Constants.MS_NOEXEC, null) != 0) {
            Logger.debug("mount /dev/mqueue: " + Libc.strerror(Libc.errno()));
        } else {
            Logger.debug("mounted /dev/mqueue");
        }

        // OCI default symlinks under /dev that runtime-tools verifies.
        symlinkIfMissing(dev + "/ptmx", "pts/ptmx");
        symlinkIfMissing(dev + "/fd", "/proc/self/fd");
        symlinkIfMissing(dev + "/stdin", "/proc/self/fd/0");
        symlinkIfMissing(dev + "/stdout", "/proc/self/fd/1");
        symlinkIfMissing(dev + "/stderr", "/proc/self/fd/2");
        Logger.debug("created /dev default symlinks");
    }

    private static void symlinkIfMissing(String linkPath, String target) {
        if (Fs.exists(linkPath)) return;
        int rc = Fs.createSymbolicLink(linkPath, target);
        if (rc != 0) {
            Logger.debug("symlink " + linkPath + " -> " + target + " failed: "
                    + Libc.strerror(Libc.errno()));
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
            try { Fs.createDirectories(sys); }
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
        try { Fs.createDirectories(cg); } catch (IOException ignored) {}
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
            for (String line : Fs.readAllLines("/proc/self/cgroup")) {
                if (line.startsWith("0::")) {
                    String p = line.substring(3).trim();
                    return p.isEmpty() ? null : p;
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    /**
     * Apply spec.mounts in order. Package-visible so the unit test can drive
     * this directly against a RecordingSyscalls fake.
     */
    static void applyOciMounts(Arena arena, String rootfsPath, List<Spec.Mount> mounts,
                               java.util.Map<String, Integer> idmapFds) {
        Syscalls sc = SyscallHost.current();
        for (Spec.Mount m : mounts) {
            if (m.destination == null) continue;
            // skip already-handled paths
            if (m.destination.equals("/proc") || m.destination.equals("/dev")
                || m.destination.equals("/sys") || m.destination.equals("/dev/shm")
                || m.destination.equals("/dev/pts") || m.destination.equals("/dev/mqueue")
                || m.destination.equals("/sys/fs/cgroup")) continue;
            String target = rootfsPath + m.destination;
            String type = m.type != null ? m.type : "none";
            MountOptions.Parsed parsed = MountOptions.parse(m.options);
            long flags = parsed.flags;
            long propagation = parsed.propagation;
            String data = parsed.data;
            boolean isBind = parsed.isBind;
            try { Fs.createDirectories(target); } catch (IOException ignored) {}

            if (m.uidMappings != null && !m.uidMappings.isEmpty() && isBind) {
                Integer prepFd = idmapFds.get(m.destination);
                boolean done;
                if (prepFd != null) {
                    done = IdmapHelper.applyWithFd(m, prepFd, target);
                    if (done) Logger.debug("idmap mounted " + m.destination
                            + " using host-prepared fd " + prepFd);
                } else {
                    done = IdmapHelper.apply(m, target);
                    if (done) Logger.debug("idmap mounted " + m.destination
                            + " using in-init helper");
                }
                if (done) continue;
                Logger.warn("idmap mount failed for " + m.destination + ", falling back to plain bind");
            }

            int rc = sc.mount(m.source, target, isBind ? null : type, flags, data);
            if (rc != 0) {
                Logger.debug("optional mount " + m.destination + " failed: "
                        + sc.strerror(sc.errno()));
                continue;
            }
            Logger.debug("mounted " + m.destination + " (type=" + type + ")");
            if (isBind && (flags & (Constants.MS_RDONLY | Constants.MS_NOSUID
                    | Constants.MS_NODEV | Constants.MS_NOEXEC)) != 0) {
                long remountFlags = Constants.MS_BIND | Constants.MS_REMOUNT
                        | (flags & (Constants.MS_RDONLY | Constants.MS_NOSUID
                                  | Constants.MS_NODEV | Constants.MS_NOEXEC
                                  | Constants.MS_NOATIME | Constants.MS_RELATIME
                                  | Constants.MS_STRICTATIME | Constants.MS_NOSYMFOLLOW));
                if (sc.mount(null, target, null, remountFlags, null) != 0) {
                    Logger.debug("bind remount with access flags on " + m.destination
                            + " failed: " + sc.strerror(sc.errno()));
                }
            }
            if (propagation != 0) {
                if (sc.mount(null, target, null, propagation, null) != 0) {
                    Logger.debug("propagation set on " + m.destination + " failed: "
                            + sc.strerror(sc.errno()));
                }
            }
        }
    }

    public static void pivot(String newRoot) {
        pivot(newRoot, null);
    }

    public static void pivot(String newRoot, String rootfsPropagation) {
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
            if (rootfsPropagation != null) {
                long prop = switch (rootfsPropagation) {
                    case "shared"      -> Constants.MS_SHARED;
                    case "rshared"     -> Constants.MS_SHARED | Constants.MS_REC;
                    case "slave"       -> Constants.MS_SLAVE;
                    case "rslave"      -> Constants.MS_SLAVE | Constants.MS_REC;
                    case "private"     -> Constants.MS_PRIVATE;
                    case "rprivate"    -> Constants.MS_PRIVATE | Constants.MS_REC;
                    case "unbindable"  -> Constants.MS_UNBINDABLE;
                    case "runbindable" -> Constants.MS_UNBINDABLE | Constants.MS_REC;
                    default            -> 0L;
                };
                if (prop != 0) {
                    if (Libc.mount(arena, null, "/", null, prop, null) != 0) {
                        Logger.warn("set / to " + rootfsPropagation + " failed: "
                                + Libc.strerror(Libc.errno()));
                    } else {
                        Logger.debug("/ propagation set to " + rootfsPropagation);
                    }
                }
            }
            Logger.debug("pivot_root completed");
        }
    }

    public static void setRootReadonly() {
        try (Arena arena = Arena.ofConfined()) {
            long flags = Constants.MS_BIND | Constants.MS_REMOUNT
                    | Constants.MS_RDONLY | Constants.MS_NOSUID;
            if (Libc.mount(arena, null, "/", null, flags, null) != 0) {
                Logger.warn("remount / readonly failed: " + Libc.strerror(Libc.errno()));
            } else {
                Logger.debug("/ set readonly+nosuid");
            }
        }
    }

    public static void maskPaths(List<String> paths) {
        if (paths == null) return;
        Syscalls sc = SyscallHost.current();
        for (String p : paths) {
            int rc = sc.mount("/dev/null", p, null, Constants.MS_BIND, null);
            if (rc == 0) {
                Logger.debug("masked " + p + " with /dev/null");
                continue;
            }
            int err = sc.errno();
            if (err == Constants.ENOENT) continue;
            rc = sc.mount("tmpfs", p, "tmpfs", Constants.MS_RDONLY, null);
            if (rc != 0) {
                Logger.debug("mask " + p + " failed: " + sc.strerror(sc.errno()));
            } else {
                Logger.debug("masked " + p + " with tmpfs");
            }
        }
    }

    public static void readonlyRemount(List<String> paths) {
        if (paths == null) return;
        Syscalls sc = SyscallHost.current();
        for (String p : paths) {
            if (sc.mount(p, p, null, Constants.MS_BIND | Constants.MS_REC, null) != 0) {
                int err = sc.errno();
                if (err == Constants.ENOENT) continue;
                Logger.debug("rebind " + p + ": " + sc.strerror(err));
                continue;
            }
            long flags = Constants.MS_BIND | Constants.MS_REC | Constants.MS_REMOUNT
                    | Constants.MS_RDONLY;
            if (sc.mount(p, p, null, flags, null) != 0) {
                Logger.debug("readonly remount " + p + ": " + sc.strerror(sc.errno()));
            } else {
                Logger.debug("readonly " + p);
            }
        }
    }
}
