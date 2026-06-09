package com.ternbusty.takoyaki.process;

import com.ternbusty.takoyaki.capability.Capability;
import com.ternbusty.takoyaki.ipc.NotifySocket;
import com.ternbusty.takoyaki.ipc.SyncChannel;
import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.rootfs.Rootfs;
import com.ternbusty.takoyaki.seccomp.Seccomp;
import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.syscall.CloseRange;
import com.ternbusty.takoyaki.syscall.Constants;
import com.ternbusty.takoyaki.syscall.Groups;
import com.ternbusty.takoyaki.syscall.Libc;
import com.ternbusty.takoyaki.syscall.PosixIO;
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

            if (spec.hasNamespace("mount")) {
                Rootfs.prepare(rootfsPath, spec);
                Rootfs.pivot(rootfsPath);
            } else {
                Logger.debug("no mount namespace, skipping rootfs prep");
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
            if (Libc.setgid(targetGid) != 0) {
                Logger.warn("setgid " + targetGid + " failed: " + Libc.strerror(Libc.errno()));
            }
            if (Libc.setuid(targetUid) != 0) {
                Logger.warn("setuid " + targetUid + " failed: " + Libc.strerror(Libc.errno()));
            }
            Logger.debug("set uid=" + targetUid + " gid=" + targetGid);

            if (caps != null) {
                Capability.clearKeepCaps();
                Capability.applyFinalSets(caps);
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
