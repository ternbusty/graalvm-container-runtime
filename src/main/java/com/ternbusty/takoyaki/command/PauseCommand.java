package com.ternbusty.takoyaki.command;

public final class PauseCommand {
    private PauseCommand() {}

    public static int run(String rootPath, String containerId) {
        return Freeze.write(rootPath, containerId, "1", "pause");
    }
}
