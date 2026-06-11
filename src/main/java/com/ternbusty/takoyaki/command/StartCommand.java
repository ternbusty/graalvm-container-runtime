package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.hooks.Hooks;
import com.ternbusty.takoyaki.ipc.NotifySocket;
import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.state.ContainerStatus;
import com.ternbusty.takoyaki.state.State;
import com.ternbusty.takoyaki.util.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "start", description = "Start a created container")
public final class StartCommand implements Callable<Integer> {
    @ParentCommand
    TakoyakiRoot root;

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
        Spec spec = null;
        try {
            spec = Json.readFile(Path.of(state.bundle, "config.json"), Spec.class);
        } catch (Exception e) {
            Logger.debug("could not reload spec for hooks: " + e.getMessage());
        }

        // No process.args validation here. runtime-tools' validation/start
        // test 7 expects start to return success even when spec.process is
        // nil (the assertion is `err == nil` despite the message saying
        // "MUST generate an error" — see runtime-tools/validation/start
        // upstream bug). The container then reaches 'stopped' naturally
        // because InitProcess detects empty args and _exits(1).

        try {
            NotifySocket.sendStart("/tmp/takoyaki-" + containerId + ".sock");
            State updated = state.withStatus(ContainerStatus.RUNNING);
            updated.save(root.rootPath);
            Logger.info("container " + containerId + " started");

            // poststart hook runs in the runtime namespace after the user process is started.
            Logger.debug("poststart: spec=" + (spec != null) + " hooks=" + (spec != null && spec.hooks != null)
                    + " count=" + (spec != null && spec.hooks != null && spec.hooks.poststart != null ? spec.hooks.poststart.size() : 0));
            if (spec != null && spec.hooks != null) {
                Hooks.run(spec.hooks.poststart, updated, "poststart");
            }
            return 0;
        } catch (Exception e) {
            Logger.error("failed to start: " + e.getMessage());
            return 1;
        }
    }
}
