package io.github.ternbusty.gcr.command;

import io.github.ternbusty.gcr.cgroup.Cgroup;
import io.github.ternbusty.gcr.config.KontainerConfig;
import io.github.ternbusty.gcr.logger.Logger;
import io.github.ternbusty.gcr.state.State;
import io.github.ternbusty.gcr.syscall.Constants;
import io.github.ternbusty.gcr.syscall.Libc;
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
    GcrRoot root;

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
            Files.deleteIfExists(Path.of("/tmp/gcr-" + containerId + ".sock"));
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
