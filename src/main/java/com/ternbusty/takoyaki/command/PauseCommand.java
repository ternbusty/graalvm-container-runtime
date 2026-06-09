package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.config.KontainerConfig;
import com.ternbusty.takoyaki.logger.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "pause", description = "Pause a container by freezing its cgroup")
public final class PauseCommand implements Callable<Integer> {
    @ParentCommand TakoyakiRoot root;

    @Parameters(index = "0", description = "Container ID")
    String containerId;

    @Override
    public Integer call() {
        return Freeze.write(root.rootPath, containerId, "1", "pause");
    }
}
