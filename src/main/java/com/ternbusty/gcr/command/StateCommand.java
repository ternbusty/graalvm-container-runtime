package com.ternbusty.gcr.command;

import com.ternbusty.gcr.logger.Logger;
import com.ternbusty.gcr.state.State;
import com.ternbusty.gcr.util.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

@Command(name = "state", description = "Display container state")
public final class StateCommand implements Callable<Integer> {
    @ParentCommand
    GcrRoot root;

    @Parameters(index = "0", description = "Container ID")
    String containerId;

    @Override
    public Integer call() {
        try {
            State s = State.load(root.rootPath, containerId).refreshStatus();
            System.out.println(Json.encode(s));
            return 0;
        } catch (Exception e) {
            Logger.error("failed to load state: " + e.getMessage());
            return 1;
        }
    }
}
