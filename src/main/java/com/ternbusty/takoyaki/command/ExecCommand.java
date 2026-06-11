package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.state.State;
import com.ternbusty.takoyaki.syscall.Libc;
import com.ternbusty.takoyaki.syscall.PosixIO;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Run an additional command inside an existing container by joining all of its
 * namespaces with setns(2). Equivalent to `runc exec`.
 *
 * Limitations vs runc/youki: we don't re-apply seccomp / capabilities for the new
 * process; it inherits whatever the init has, which is usually what the user wants.
 */
public final class ExecCommand {
    private ExecCommand() {}

    public static int run(String rootPath, String containerId, String user, String cwd,
                          List<String> envs, List<String> command) {
        State state;
        try {
            state = State.load(rootPath, containerId).refreshStatus();
        } catch (Exception e) {
            Logger.error("failed to load state: " + e.getMessage());
            return 1;
        }
        if (state.pid == null) {
            Logger.error("container has no pid");
            return 1;
        }
        if (command == null || command.isEmpty()) {
            Logger.error("no command specified");
            return 1;
        }

        int initPid = state.pid;
        // Join container namespaces in this order: user first (so we have rights),
        // then the rest. user_ns may be absent.
        String[] nsTypes = {"user", "ipc", "uts", "net", "pid", "mnt"};
        try (Arena arena = Arena.ofConfined()) {
            for (String t : nsTypes) {
                String path = "/proc/" + initPid + "/ns/" + t;
                if (!java.nio.file.Files.exists(Path.of(path))) continue;
                int fd = PosixIO.open(arena,
                        path,
                        com.ternbusty.takoyaki.syscall.Constants.O_RDONLY, 0);
                if (fd < 0) {
                    Logger.warn("open " + path + " failed: " + Libc.strerror(Libc.errno()));
                    continue;
                }
                if (Libc.setns(fd, 0) != 0) {
                    Logger.warn("setns " + t + " failed: " + Libc.strerror(Libc.errno()));
                }
                PosixIO.close(fd);
            }
        }

        // After joining PID namespace we need to fork — the current process still
        // belongs to the original pid ns; only children see the new one.
        int childPid = PosixIO.fork();
        if (childPid < 0) {
            Logger.error("fork failed: " + Libc.strerror(Libc.errno()));
            return 1;
        }
        if (childPid == 0) {
            try (Arena arena = Arena.ofConfined()) {
                if (user != null) {
                    int u, g = -1;
                    String[] uv = user.split(":");
                    u = Integer.parseInt(uv[0]);
                    if (uv.length > 1) g = Integer.parseInt(uv[1]);
                    if (g >= 0) Libc.setresgid(g, g, g);
                    Libc.setresuid(u, u, u);
                }
                if (cwd != null) Libc.chdir(arena, cwd);

                String[] argv = command.toArray(new String[0]);

                List<String> envList = new ArrayList<>();
                if (envs != null) envList.addAll(envs);
                if (envList.isEmpty()) {
                    // Inherit a sane default PATH.
                    envList.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
                    envList.add("HOME=/root");
                }
                String[] envp = envList.toArray(new String[0]);
                PosixIO.ExecvePayload payload = PosixIO.ExecvePayload.build(arena, argv[0], argv, envp);
                PosixIO.invokeExecve(payload);
                Logger.error("execve failed: " + Libc.strerror(Libc.errno()));
            }
            PosixIO._exit(127);
            return 127;
        }

        // Parent: wait for the exec'd command to finish.
        // We use waitpid via libc since FFM gives us syscall access.
        return Wait.waitForChild(childPid);
    }
}
