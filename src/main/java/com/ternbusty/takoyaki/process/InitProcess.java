package com.ternbusty.takoyaki.process;

import com.ternbusty.takoyaki.apparmor.AppArmor;
import com.ternbusty.takoyaki.capability.Capability;
import com.ternbusty.takoyaki.ipc.NotifySocket;
import com.ternbusty.takoyaki.ipc.SyncChannel;
import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.network.Loopback;
import com.ternbusty.takoyaki.rootfs.Devices;
import com.ternbusty.takoyaki.rootfs.Rootfs;
import com.ternbusty.takoyaki.rootfs.UserDb;
import com.ternbusty.takoyaki.seccomp.Seccomp;
import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.syscall.CloseRange;
import com.ternbusty.takoyaki.syscall.Constants;
import com.ternbusty.takoyaki.syscall.Groups;
import com.ternbusty.takoyaki.syscall.Libc;
import com.ternbusty.takoyaki.syscall.PosixIO;
import com.ternbusty.takoyaki.sysctl.Sysctl;
import com.ternbusty.takoyaki.util.Json;

import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;

public final class InitProcess {
    private InitProcess() {}

    public static void run() {
        Logger.setContext("init");
        Logger.debug("init started, pid=" + Libc.getpid() + " ppid=" + Libc.getppid());
        try {
            String mntNs = Files.readSymbolicLink(Path.of("/proc/self/ns/mnt")).toString();
            String pidNs = Files.readSymbolicLink(Path.of("/proc/self/ns/pid")).toString();
            Logger.debug("init mnt_ns=" + mntNs + " pid_ns=" + pidNs);
        } catch (Exception e) {
            Logger.warn("could not read ns: " + e.getMessage());
        }

        String bundlePath = System.getenv("_TAKOYAKI_BUNDLE_PATH");
        String rootfsPath = System.getenv("_TAKOYAKI_ROOTFS_PATH");
        String mainSenderFdStr = System.getenv("_TAKOYAKI_MAIN_SENDER_FD");
        String notifyListenerFdStr = System.getenv("_TAKOYAKI_NOTIFY_LISTENER_FD");

        if (bundlePath == null || rootfsPath == null || mainSenderFdStr == null
                || notifyListenerFdStr == null) {
            Logger.error("missing required env vars");
            PosixIO._exit(1);
            return;
        }

        int mainSenderFd = Integer.parseInt(mainSenderFdStr);
        int notifyListenerFd = Integer.parseInt(notifyListenerFdStr);

        Spec spec;
        try {
            spec = Json.readFile(Path.of(bundlePath, "config.json"), Spec.class);
        } catch (Exception e) {
            Logger.error("failed to load spec: " + e.getMessage());
            PosixIO._exit(1);
            return;
        }

        try (Arena arena = Arena.ofConfined()) {
            // libseccomp.so.2 is linked at NEEDED so ld.so loads it at process startup,
            // before pivot_root replaces the rootfs. No explicit preload required.

            // Become subreaper so orphaned descendants are reaped by this init
            // (PID 1 inside the container's pid namespace).
            if (Libc.prctl(Constants.PR_SET_CHILD_SUBREAPER, 1, 0, 0, 0) != 0) {
                Logger.debug("PR_SET_CHILD_SUBREAPER failed: " + Libc.strerror(Libc.errno()));
            }

            if (spec.hasNamespace("mount")) {
                Rootfs.prepare(rootfsPath, spec);
                // Additional devices declared in spec.linux.devices, before pivot_root.
                if (spec.linux != null && spec.linux.devices != null) {
                    Devices.create(rootfsPath, spec.linux.devices);
                }
                Rootfs.pivot(rootfsPath);
            } else {
                Logger.debug("no mount namespace, skipping rootfs prep");
            }

            // Bring up loopback so localhost works inside the network namespace.
            if (spec.hasNamespace("network")) {
                Loopback.up();
            }

            // Apply spec.linux.sysctl after mounts are in place (so /proc/sys is visible).
            if (spec.linux != null && spec.linux.sysctl != null) {
                Sysctl.apply(spec.linux.sysctl);
            }

            String cwd = spec.process != null && spec.process.cwd != null ? spec.process.cwd : "/";
            if (Libc.chdir(arena, cwd) != 0) {
                Logger.warn("chdir " + cwd + " failed: " + Libc.strerror(Libc.errno()));
            }

            if (spec.hostname != null) {
                if (Libc.sethostname(arena, spec.hostname) != 0) {
                    Logger.warn("sethostname failed: " + Libc.strerror(Libc.errno()));
                } else {
                    Logger.debug("hostname set to " + spec.hostname);
                }
            }

            // Mask sensitive paths and remount others read-only BEFORE the root is made RO,
            // so the bind / remount itself can still succeed.
            if (spec.linux != null) {
                Rootfs.maskPaths(spec.linux.maskedPaths);
                Rootfs.readonlyRemount(spec.linux.readonlyPaths);
            }

            // Generate /etc/passwd and /etc/group entries while still writable.
            if (spec.process != null) {
                UserDb.ensure(spec.process.user,
                        spec.process.user == null ? null : spec.process.user.additionalGids);
            }

            if (spec.root != null && spec.root.readonly) {
                Rootfs.setRootReadonly();
            }

            if (spec.process != null && spec.process.umask != null) {
                Libc.umask(spec.process.umask.intValue());
            }

            if (Boolean.TRUE.equals(spec.process != null ? spec.process.noNewPrivileges : null)) {
                if (Libc.prctl(Constants.PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) {
                    Logger.warn("PR_SET_NO_NEW_PRIVS failed");
                } else {
                    Logger.debug("no_new_privileges set");
                }
            }

            Spec.LinuxCapabilities caps = spec.process != null ? spec.process.capabilities : null;
            if (caps != null) {
                Capability.applyBoundingSet(caps);
                Capability.setKeepCaps();
            }

            if (spec.process != null && spec.process.user != null
                    && spec.process.user.additionalGids != null) {
                Groups.setAdditional(spec.process.user.additionalGids);
            }

            int targetGid = spec.process != null && spec.process.user != null ? spec.process.user.gid : 0;
            int targetUid = spec.process != null && spec.process.user != null ? spec.process.user.uid : 0;
            // setresgid/setresuid drops real/effective/saved IDs all at once so the
            // process can't restore privileges via saved UID.
            if (Libc.setresgid(targetGid, targetGid, targetGid) != 0) {
                Logger.warn("setresgid " + targetGid + " failed: " + Libc.strerror(Libc.errno()));
            }
            if (Libc.setresuid(targetUid, targetUid, targetUid) != 0) {
                Logger.warn("setresuid " + targetUid + " failed: " + Libc.strerror(Libc.errno()));
            }
            Logger.debug("set uid=" + targetUid + " gid=" + targetGid);

            if (caps != null) {
                Capability.clearKeepCaps();
                Capability.applyFinalSets(caps);
            }

            // Re-set non-dumpable so /proc inspection by attached processes can't leak.
            if (Libc.prctl(Constants.PR_SET_DUMPABLE, 0, 0, 0, 0) != 0) {
                Logger.debug("PR_SET_DUMPABLE,0 failed: " + Libc.strerror(Libc.errno()));
            }

            // AppArmor profile must be staged BEFORE seccomp/execve. With no_new_privs the
            // kernel rejects further label changes after exec(2), so this is the latest
            // safe point to do it (after dropping privileges, before seccomp filter).
            if (spec.process != null && spec.process.apparmorProfile != null) {
                AppArmor.apply(spec.process.apparmorProfile);
            }

            if (spec.linux != null && spec.linux.seccomp != null) {
                Seccomp.apply(spec.linux.seccomp);
            }

            SyncChannel.writeInt32(mainSenderFd, SyncChannel.MSG_INIT_READY);
            PosixIO.close(mainSenderFd);

            CloseRange.closeAllAbove(0);

            Logger.debug("waiting for start signal on notify fd " + notifyListenerFd);
            NotifySocket.waitForStart(notifyListenerFd);
            PosixIO.close(notifyListenerFd);

            if (spec.process == null || spec.process.args == null || spec.process.args.isEmpty()) {
                Logger.error("process.args is empty");
                PosixIO._exit(1);
                return;
            }

            Libc.clearenv();
            if (spec.process.env != null) {
                for (String entry : spec.process.env) {
                    int eq = entry.indexOf('=');
                    if (eq > 0) {
                        Libc.setenv(arena, entry.substring(0, eq), entry.substring(eq + 1), true);
                    }
                }
            }

            String[] argv = spec.process.args.toArray(new String[0]);
            Logger.info("executing: " + String.join(" ", argv));
            Libc.execvp(arena, argv[0], argv);
            Logger.error("execvp failed: " + Libc.strerror(Libc.errno()));
            PosixIO._exit(127);
        } catch (Exception e) {
            Logger.error("init failed: " + e.getMessage());
            PosixIO._exit(1);
        }
    }
}
