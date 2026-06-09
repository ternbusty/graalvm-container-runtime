package com.ternbusty.takoyaki.command;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "takoyaki",
        mixinStandardHelpOptions = true,
        version = "takoyaki 0.1.0",
        description = "GraalVM-based low-level container runtime (runc compatible)",
        subcommands = {
                CreateCommand.class,
                StartCommand.class,
                StateCommand.class,
                KillCommand.class,
                DeleteCommand.class,
        }
)
public final class TakoyakiRoot {
    @Option(names = "--root", description = "Root directory for container state",
            defaultValue = "/run/takoyaki")
    public String rootPath;

    @Option(names = {"--debug"}, description = "Enable debug logging")
    public boolean debug;

    @Option(names = {"--log"}, description = "Log file path")
    public String logFile;

    @Option(names = {"--log-format"}, description = "Log format (text or json)")
    public String logFormat;

    @Option(names = {"--systemd-cgroup"}, description = "Use systemd cgroup (accepted, no-op)")
    public boolean systemdCgroup;

    @Option(names = {"--rootless"}, description = "Rootless mode (accepted, auto)")
    public String rootless;

    @Option(names = {"--criu"}, description = "CRIU binary path (accepted, no-op)")
    public String criu;
}
