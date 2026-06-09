package com.ternbusty.takoyaki.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

@Command(name = "resume", description = "Resume a paused container")
public final class ResumeCommand implements Callable<Integer> {
    @ParentCommand TakoyakiRoot root;

    @Parameters(index = "0", description = "Container ID")
    String containerId;

    @Override
    public Integer call() {
        return Freeze.write(root.rootPath, containerId, "0", "resume");
    }
}
