package com.ternbusty.takoyaki;

import com.ternbusty.takoyaki.command.TakoyakiRoot;
import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.process.InitProcess;
import picocli.CommandLine;

public final class Main {
    public static void main(String[] args) {
        // Startup tracing. Set _TAKOYAKI_TRACE_STARTUP=1 to dump per-phase
        // wall-clock breakdown to stderr. We capture each timestamp with
        // System.nanoTime() (monotonic, ~10 ns resolution) and print at the
        // end so the print itself doesn't perturb measurements.
        boolean trace = "1".equals(System.getenv("_TAKOYAKI_TRACE_STARTUP"));
        long t0 = trace ? System.nanoTime() : 0;

        // Stage-2 (post-bootstrap re-exec) is detected via the sentinel argv
        // we set when re-execing /proc/self/exe in bootstrap.c. (There used
        // to be a parallel C-side flag exposed via FFM, but it was always
        // 0 and the symbol wasn't dynamically findable in stripped PIEs.)
        boolean stage2 = args.length == 1 && "__init__".equals(args[0]);
        if (stage2) {
            if ("1".equals(System.getenv("_TAKOYAKI_BOOTSTRAP_DEBUG"))) {
                Logger.setLevel(Logger.Level.DEBUG);
            }
            InitProcess.run();
            return;
        }

        // Optimization 3: short-circuit --version before picocli runs to
        // avoid the ~50 ms CommandSpec build cost for a string print.
        if (args.length == 1 && "--version".equals(args[0]) && !trace) {
            System.out.println("takoyaki 0.1.1");
            System.exit(0);
        }
        long t1 = trace ? System.nanoTime() : 0;

        for (int i = 0; i < args.length; i++) {
            if ("--debug".equals(args[i])) {
                Logger.setLevel(Logger.Level.DEBUG);
            } else if ("--log".equals(args[i]) && i + 1 < args.length) {
                Logger.setLogFile(args[i + 1]);
            } else if ("--log-format".equals(args[i]) && i + 1 < args.length) {
                if ("json".equalsIgnoreCase(args[i + 1])) {
                    Logger.setFormat(Logger.Format.JSON);
                }
            }
        }
        long t2 = trace ? System.nanoTime() : 0;

        TakoyakiRoot rootCmd = new TakoyakiRoot();
        long t3 = trace ? System.nanoTime() : 0;

        CommandLine cmd = new CommandLine(rootCmd);
        // Optimization 4: only register the subcommand we actually need.
        // This avoids picocli reflecting over all 12 subcommand classes when
        // only one runs per invocation.
        registerSubcommand(cmd, args);
        long t4 = trace ? System.nanoTime() : 0;

        cmd.setUnmatchedArgumentsAllowed(false);
        long t5 = trace ? System.nanoTime() : 0;

        int exitCode = cmd.execute(args);
        long t6 = trace ? System.nanoTime() : 0;

        if (trace) {
            // Print to stderr so it doesn't pollute --version stdout etc.
            // Each delta is microseconds for readability at the sub-ms scale.
            java.io.PrintStream err = System.err;
            err.printf("[trace] T01 stage2 check          : %7.3f ms%n", (t1 - t0) / 1e6);
            err.printf("[trace] T12 arg pre-parse loop    : %7.3f ms%n", (t2 - t1) / 1e6);
            err.printf("[trace] T23 new TakoyakiRoot()    : %7.3f ms%n", (t3 - t2) / 1e6);
            err.printf("[trace] T34 new CommandLine(root) : %7.3f ms%n", (t4 - t3) / 1e6);
            err.printf("[trace] T45 setUnmatchedArgs(false): %7.3f ms%n", (t5 - t4) / 1e6);
            err.printf("[trace] T56 cmd.execute(args)     : %7.3f ms%n", (t6 - t5) / 1e6);
            err.printf("[trace] TOTAL (main entry -> done): %7.3f ms%n", (t6 - t0) / 1e6);
        }
        System.exit(exitCode);
    }

    /**
     * Look at argv to find the requested subcommand name and add ONLY that
     * one to the CommandLine. Falls back to registering all subcommands for
     * --help, unknown commands, and bash-completion paths so user-facing
     * behavior is unchanged.
     */
    private static void registerSubcommand(CommandLine cmd, String[] args) {
        String subName = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("-")) {
                // Skip flags and their values for the known --root, --debug,
                // --log, --log-format, --systemd-cgroup, --rootless, --criu options
                if ("--root".equals(a) || "--log".equals(a) || "--log-format".equals(a)
                        || "--rootless".equals(a) || "--criu".equals(a)) {
                    i++;
                }
                continue;
            }
            subName = a;
            break;
        }

        if (subName == null) {
            registerAllSubcommands(cmd);
            return;
        }

        switch (subName) {
            case "create" -> cmd.addSubcommand(new com.ternbusty.takoyaki.command.CreateCommand());
            case "start"  -> cmd.addSubcommand(new com.ternbusty.takoyaki.command.StartCommand());
            case "state"  -> cmd.addSubcommand(new com.ternbusty.takoyaki.command.StateCommand());
            case "kill"   -> cmd.addSubcommand(new com.ternbusty.takoyaki.command.KillCommand());
            case "delete" -> cmd.addSubcommand(new com.ternbusty.takoyaki.command.DeleteCommand());
            case "list", "ls" -> cmd.addSubcommand(new com.ternbusty.takoyaki.command.ListCommand());
            case "ps"     -> cmd.addSubcommand(new com.ternbusty.takoyaki.command.PsCommand());
            case "pause"  -> cmd.addSubcommand(new com.ternbusty.takoyaki.command.PauseCommand());
            case "resume" -> cmd.addSubcommand(new com.ternbusty.takoyaki.command.ResumeCommand());
            case "update" -> cmd.addSubcommand(new com.ternbusty.takoyaki.command.UpdateCommand());
            case "events" -> cmd.addSubcommand(new com.ternbusty.takoyaki.command.EventsCommand());
            case "exec"   -> cmd.addSubcommand(new com.ternbusty.takoyaki.command.ExecCommand());
            default -> registerAllSubcommands(cmd);
        }
    }

    private static void registerAllSubcommands(CommandLine cmd) {
        cmd.addSubcommand(new com.ternbusty.takoyaki.command.CreateCommand());
        cmd.addSubcommand(new com.ternbusty.takoyaki.command.StartCommand());
        cmd.addSubcommand(new com.ternbusty.takoyaki.command.StateCommand());
        cmd.addSubcommand(new com.ternbusty.takoyaki.command.KillCommand());
        cmd.addSubcommand(new com.ternbusty.takoyaki.command.DeleteCommand());
        cmd.addSubcommand(new com.ternbusty.takoyaki.command.ListCommand());
        cmd.addSubcommand(new com.ternbusty.takoyaki.command.PsCommand());
        cmd.addSubcommand(new com.ternbusty.takoyaki.command.PauseCommand());
        cmd.addSubcommand(new com.ternbusty.takoyaki.command.ResumeCommand());
        cmd.addSubcommand(new com.ternbusty.takoyaki.command.UpdateCommand());
        cmd.addSubcommand(new com.ternbusty.takoyaki.command.EventsCommand());
        cmd.addSubcommand(new com.ternbusty.takoyaki.command.ExecCommand());
    }
}
