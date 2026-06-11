package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.ipc.NotifySocket;
import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.process.MainProcess;
import com.ternbusty.takoyaki.process.NamespaceFlags;
import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.state.State;
import com.ternbusty.takoyaki.syscall.Constants;
import com.ternbusty.takoyaki.syscall.Libc;
import com.ternbusty.takoyaki.syscall.PosixIO;
import com.ternbusty.takoyaki.util.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "create", description = "Create a new container")
public final class CreateCommand implements Callable<Integer> {
    @ParentCommand
    TakoyakiRoot root;

    @Option(names = {"-b", "--bundle"}, defaultValue = ".", description = "Bundle path")
    String bundle;

    @Option(names = {"--pid-file"}, description = "PID file path")
    String pidFile;

    @Option(names = {"--console-socket"}, description = "Console socket (accepted, not used)")
    String consoleSocket;

    @Option(names = {"--no-pivot"}, description = "Use chroot instead of pivot_root (accepted)")
    boolean noPivot;

    @Option(names = {"--no-new-keyring"}, description = "Do not create a new keyring (accepted)")
    boolean noNewKeyring;

    @Option(names = {"--preserve-fds"}, defaultValue = "0", description = "Preserve additional FDs")
    int preserveFds;

    @Parameters(index = "0", description = "Container ID")
    String containerId;

    @Override
    public Integer call() {
        String rootPath = root.rootPath;
        if (State.exists(rootPath, containerId)) {
            Logger.error("container " + containerId + " already exists");
            return 1;
        }

        // Resolve bundle to an absolute path so later commands (start/delete) can
        // re-open config.json regardless of their cwd.
        try {
            bundle = Path.of(bundle).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            Logger.error("invalid bundle path: " + e.getMessage());
            return 1;
        }

        Spec spec;
        try {
            spec = Json.readFile(Path.of(bundle, "config.json"), Spec.class);
        } catch (Exception e) {
            Logger.error("failed to load config.json: " + e.getMessage());
            return 1;
        }

        // OCI spec: the runtime MUST reject configs that contain invalid /
        // unsupported values. ociVersion must be a semver-like string. Anything
        // else (e.g. "invalid" — runtime-tools misc_props test) is rejected.
        if (spec.ociVersion == null
                || !spec.ociVersion.matches("\\d+\\.\\d+(\\.\\d+)?(-[\\w.+-]+)?")) {
            Logger.error("invalid ociVersion: " + spec.ociVersion);
            return 1;
        }

        // process.args MUST be non-empty per the OCI spec. Catching this here
        // means a malformed config errors at create time instead of leaving
        // the init process parked waiting for a start that will then fail
        // mysteriously. youki and runc both reject this at create.
        if (spec.process == null || spec.process.args == null
                || spec.process.args.isEmpty()) {
            Logger.error("invalid spec: process.args must be a non-empty array");
            return 1;
        }

        String rootfsPath = spec.root.path.startsWith("/")
                ? spec.root.path
                : bundle + "/" + spec.root.path;
        Logger.debug("rootfs=" + rootfsPath);

        String notifySocketPath = "/tmp/takoyaki-" + containerId + ".sock";
        int notifyListenerFd = NotifySocket.createListener(notifySocketPath);

        int[] syncFds = new int[2];
        int[] mainFds = new int[2];
        try (Arena arena = Arena.ofConfined()) {
            if (PosixIO.socketpair(arena, Constants.AF_UNIX, Constants.SOCK_STREAM, 0, syncFds) < 0) {
                Logger.error("socketpair sync failed: " + Libc.strerror(Libc.errno()));
                return 1;
            }
            if (PosixIO.socketpair(arena, Constants.AF_UNIX, Constants.SOCK_STREAM, 0, mainFds) < 0) {
                Logger.error("socketpair main failed: " + Libc.strerror(Libc.errno()));
                return 1;
            }
        }
        int mainParentFd = mainFds[0];
        int mainChildFd = mainFds[1];

        int cloneFlags = NamespaceFlags.fromSpec(spec.linux != null ? spec.linux.namespaces : null);
        Logger.debug("clone flags=0x" + Integer.toHexString(cloneFlags));

        String exePath;
        try (Arena arena = Arena.ofConfined()) {
            exePath = PosixIO.readlink(arena, "/proc/self/exe");
        }
        if (exePath == null) {
            Logger.error("readlink /proc/self/exe failed");
            return 1;
        }

        List<String> envList = new ArrayList<>();
        for (Map.Entry<String, String> e : System.getenv().entrySet()) {
            String k = e.getKey();
            if (k.startsWith("_TAKOYAKI_")) continue;
            envList.add(k + "=" + e.getValue());
        }
        envList.add("_TAKOYAKI_IS_BOOTSTRAP=1");
        envList.add("_TAKOYAKI_SYNCPIPE=" + syncFds[1]);
        envList.add("_TAKOYAKI_CLONE_FLAGS=" + Integer.toHexString(cloneFlags));
        envList.add("_TAKOYAKI_MAIN_SENDER_FD=" + mainChildFd);
        envList.add("_TAKOYAKI_NOTIFY_LISTENER_FD=" + notifyListenerFd);
        envList.add("_TAKOYAKI_BUNDLE_PATH=" + bundle);
        envList.add("_TAKOYAKI_ROOTFS_PATH=" + rootfsPath);
        envList.add("_TAKOYAKI_CONTAINER_ID=" + containerId);
        if (root.debug) envList.add("_TAKOYAKI_BOOTSTRAP_DEBUG=1");
        if (consoleSocket != null) envList.add("_TAKOYAKI_CONSOLE_SOCKET=" + consoleSocket);
        if (noNewKeyring) envList.add("_TAKOYAKI_NO_NEW_KEYRING=1");

        // Namespaces with an explicit `path` field: open the path on the host so
        // bootstrap.c can join via setns() instead of unshare(). The fd survives
        // fork+execve because we don't set CLOEXEC. Names match `setns` nstype
        // constants in bootstrap.c's switch table.
        if (spec.linux != null && spec.linux.namespaces != null) {
            StringBuilder nsFds = new StringBuilder();
            try (Arena openArena = Arena.ofConfined()) {
                for (Spec.Namespace ns : spec.linux.namespaces) {
                    if (ns.path == null || ns.path.isEmpty()) continue;
                    int fd = PosixIO.open(openArena, ns.path, Constants.O_RDONLY, 0);
                    if (fd < 0) {
                        Logger.error("open ns path " + ns.path + " failed: " + Libc.strerror(Libc.errno()));
                        return 1;
                    }
                    if (nsFds.length() > 0) nsFds.append(',');
                    nsFds.append(ns.type).append(':').append(fd);
                }
            }
            if (nsFds.length() > 0) {
                envList.add("_TAKOYAKI_NS_FDS=" + nsFds.toString());
            }
        }

        // Mount idmap: set up helper user namespaces on the HOST (where we can use
        // host pids to address /proc), keep the userns_fd open, pass it to the init
        // via env var (fd inherits across fork+execve because we don't set CLOEXEC).
        // Doing this from the host side avoids the /proc<->container-pid mismatch
        // that prevents the init (which runs inside the container's pid namespace)
        // from addressing its forked helpers via host-mounted /proc.
        if (spec.mounts != null) {
            StringBuilder fdMap = new StringBuilder();
            for (Spec.Mount m : spec.mounts) {
                if (m.uidMappings == null || m.uidMappings.isEmpty()) continue;
                int fd = com.ternbusty.takoyaki.rootfs.IdmapHelper.setupHostSide(
                        m.uidMappings, m.gidMappings);
                if (fd < 0) {
                    Logger.warn("idmap helper setup failed for " + m.destination
                            + "; mount will fall back to plain bind");
                    continue;
                }
                if (fdMap.length() > 0) fdMap.append(',');
                fdMap.append(java.util.Base64.getEncoder().encodeToString(
                        m.destination.getBytes())).append(':').append(fd);
            }
            if (fdMap.length() > 0) {
                envList.add("_TAKOYAKI_IDMAP_FDS=" + fdMap.toString());
            }
        }

        // Seccomp notify listener: connect on the host side (where the listener
        // socket path actually resolves) and pass the connected fd to the init via
        // env. After the init pivots into the container rootfs the listener path is
        // no longer reachable, so this has to happen here.
        if (spec.linux != null && spec.linux.seccomp != null
                && spec.linux.seccomp.listenerPath != null
                && !spec.linux.seccomp.listenerPath.isEmpty()) {
            int fd = com.ternbusty.takoyaki.seccomp.SeccompListener.connectHostSide(
                    spec.linux.seccomp.listenerPath);
            if (fd >= 0) {
                envList.add("_TAKOYAKI_SECCOMP_LISTENER_FD=" + fd);
            } else {
                Logger.warn("could not connect to seccomp listener " + spec.linux.seccomp.listenerPath
                        + " from host; SCMP_ACT_NOTIFY rules will block forever");
            }
        }

        // timens offsets must be written in stage-1 of bootstrap.c BEFORE execve into
        // Java. The kernel rejects /proc/self/timens_offsets writes after exec, so the
        // Java side can never do it. Pass the offsets through env vars instead.
        if (spec.linux != null && spec.linux.timeOffsets != null) {
            Spec.TimeOffset bt = spec.linux.timeOffsets.get("boottime");
            if (bt != null) {
                envList.add("_TAKOYAKI_TIMENS_BOOTTIME_SECS=" + bt.secs);
                envList.add("_TAKOYAKI_TIMENS_BOOTTIME_NSEC=" + bt.nanosecs);
            }
            Spec.TimeOffset mt = spec.linux.timeOffsets.get("monotonic");
            if (mt != null) {
                envList.add("_TAKOYAKI_TIMENS_MONOTONIC_SECS=" + mt.secs);
                envList.add("_TAKOYAKI_TIMENS_MONOTONIC_NSEC=" + mt.nanosecs);
            }
        }
        String[] envp = envList.toArray(new String[0]);
        String[] argv = new String[]{exePath, "__init__"};

        Arena execArena = Arena.ofShared();
        PosixIO.ExecvePayload payload = PosixIO.ExecvePayload.build(execArena, exePath, argv, envp);

        int forkPid = PosixIO.fork();
        if (forkPid < 0) {
            Logger.error("fork failed: " + Libc.strerror(Libc.errno()));
            return 1;
        }
        if (forkPid == 0) {
            PosixIO.close(syncFds[0]);
            PosixIO.close(mainParentFd);
            PosixIO.invokeExecve(payload);
            PosixIO._exit(1);
            return 1;
        }

        PosixIO.close(syncFds[1]);
        PosixIO.close(mainChildFd);

        MainProcess.run(forkPid, syncFds[0], spec, containerId,
                bundle, rootPath, pidFile, notifyListenerFd, mainParentFd);
        return 0;
    }
}
