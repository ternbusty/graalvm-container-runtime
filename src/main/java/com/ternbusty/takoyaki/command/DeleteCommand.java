package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.cgroup.Cgroup;
import com.ternbusty.takoyaki.config.KontainerConfig;
import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.state.State;
import com.ternbusty.takoyaki.syscall.Constants;
import com.ternbusty.takoyaki.syscall.Libc;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Command(name = "delete", description = "Delete a container")
public final class DeleteCommand implements Callable<Integer> {
    @ParentCommand
    TakoyakiRoot root;

    @Option(names = {"-f", "--force"}, description = "Force deletion")
    boolean force;

    @Parameters(index = "0", description = "Container ID")
    String containerId;

    @Override
    public Integer call() {
        if (!State.exists(root.rootPath, containerId)) {
            if (force) {
                Logger.info("container " + containerId + " does not exist (force)");
                return 0;
            }
            Logger.error("container " + containerId + " does not exist");
            return 1;
        }
        State state;
        try {
            state = State.load(root.rootPath, containerId).refreshStatus();
        } catch (Exception e) {
            Logger.error("failed to load state: " + e.getMessage());
            return 1;
        }
        if (!state.statusEnum().canDelete()) {
            if (!force) {
                Logger.error("cannot delete container in '" + state.status + "' state (use --force)");
                return 1;
            }
            if (state.pid != null) {
                Libc.kill(state.pid, Constants.SIGKILL);
            }
        }

        try {
            KontainerConfig kc = KontainerConfig.load(root.rootPath, containerId);
            if (kc.cgroupPath != null) Cgroup.cleanup(kc.cgroupPath);
        } catch (IOException e) {
            Logger.debug("no kontainer config or cgroup cleanup skipped: " + e.getMessage());
        }

        try {
            Files.deleteIfExists(Path.of("/tmp/takoyaki-" + containerId + ".sock"));
        } catch (IOException e) {
            Logger.warn("failed to remove notify socket: " + e.getMessage());
        }

        Path dir = State.containerDir(root.rootPath, containerId);
        try {
            if (Files.exists(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                }
            }
            Logger.info("container " + containerId + " deleted");
            return 0;
        } catch (IOException e) {
            Logger.error("failed to delete dir: " + e.getMessage());
            return 1;
        }
    }
}
