package io.github.ternbusty.gcr.command;

import io.github.ternbusty.gcr.ipc.NotifySocket;
import io.github.ternbusty.gcr.logger.Logger;
import io.github.ternbusty.gcr.state.ContainerStatus;
import io.github.ternbusty.gcr.state.State;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

@Command(name = "start", description = "Start a created container")
public final class StartCommand implements Callable<Integer> {
    @ParentCommand
    GcrRoot root;

    @Parameters(index = "0", description = "Container ID")
    String containerId;

    @Override
    public Integer call() {
        State state;
        try {
            state = State.load(root.rootPath, containerId).refreshStatus();
        } catch (Exception e) {
            Logger.error("failed to load state: " + e.getMessage());
            return 1;
        }
        if (!state.statusEnum().canStart()) {
            Logger.error("cannot start container in '" + state.status + "' state");
            return 1;
        }
        try {
            NotifySocket.sendStart("/tmp/gcr-" + containerId + ".sock");
            state.withStatus(ContainerStatus.RUNNING).save(root.rootPath);
            Logger.info("container " + containerId + " started");
            return 0;
        } catch (Exception e) {
            Logger.error("failed to start: " + e.getMessage());
            return 1;
        }
    }
}
