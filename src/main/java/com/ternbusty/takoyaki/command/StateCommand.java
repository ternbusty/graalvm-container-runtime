package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.state.State;
import com.ternbusty.takoyaki.util.Json;

public final class StateCommand {
    private StateCommand() {}

    public static int run(String rootPath, String containerId) {
        try {
            State s = State.load(rootPath, containerId).refreshStatus();
            System.out.println(Json.encode(s));
            return 0;
        } catch (Exception e) {
            Logger.error("failed to load state: " + e.getMessage());
            return 1;
        }
    }
}
