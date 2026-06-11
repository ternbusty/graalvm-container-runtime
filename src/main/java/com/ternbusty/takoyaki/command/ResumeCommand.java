package com.ternbusty.takoyaki.command;

public final class ResumeCommand {
    private ResumeCommand() {}

    public static int run(String rootPath, String containerId) {
        return Freeze.write(rootPath, containerId, "0", "resume");
    }
}
