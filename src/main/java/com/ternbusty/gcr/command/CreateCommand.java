package com.ternbusty.gcr.command;

import com.ternbusty.gcr.ipc.NotifySocket;
import com.ternbusty.gcr.logger.Logger;
import com.ternbusty.gcr.process.MainProcess;
import com.ternbusty.gcr.process.NamespaceFlags;
import com.ternbusty.gcr.spec.Spec;
import com.ternbusty.gcr.state.State;
import com.ternbusty.gcr.syscall.Constants;
import com.ternbusty.gcr.syscall.Libc;
import com.ternbusty.gcr.syscall.PosixIO;
import com.ternbusty.gcr.util.Json;
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
    GcrRoot root;

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

        Spec spec;
        try {
            spec = Json.readFile(Path.of(bundle, "config.json"), Spec.class);
        } catch (Exception e) {
            Logger.error("failed to load config.json: " + e.getMessage());
            return 1;
        }

        String rootfsPath = spec.root.path.startsWith("/")
                ? spec.root.path
                : bundle + "/" + spec.root.path;
        Logger.debug("rootfs=" + rootfsPath);

        String notifySocketPath = "/tmp/gcr-" + containerId + ".sock";
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
            if (k.startsWith("_GCR_")) continue;
            envList.add(k + "=" + e.getValue());
        }
        envList.add("_GCR_IS_BOOTSTRAP=1");
        envList.add("_GCR_SYNCPIPE=" + syncFds[1]);
        envList.add("_GCR_CLONE_FLAGS=" + Integer.toHexString(cloneFlags));
        envList.add("_GCR_MAIN_SENDER_FD=" + mainChildFd);
        envList.add("_GCR_NOTIFY_LISTENER_FD=" + notifyListenerFd);
        envList.add("_GCR_BUNDLE_PATH=" + bundle);
        envList.add("_GCR_ROOTFS_PATH=" + rootfsPath);
        if (root.debug) envList.add("_GCR_BOOTSTRAP_DEBUG=1");
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
