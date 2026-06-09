package com.ternbusty.takoyaki;

import com.ternbusty.takoyaki.command.TakoyakiRoot;
import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.process.InitProcess;
import com.ternbusty.takoyaki.syscall.Bootstrap;
import picocli.CommandLine;

public final class Main {
    public static void main(String[] args) {
        boolean stage2 = Bootstrap.isInitProcess()
                || (args.length == 1 && "__init__".equals(args[0]));
        if (stage2) {
            if ("1".equals(System.getenv("_TAKOYAKI_BOOTSTRAP_DEBUG"))) {
                Logger.setLevel(Logger.Level.DEBUG);
            }
            InitProcess.run();
            return;
        }

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

        TakoyakiRoot rootCmd = new TakoyakiRoot();
        CommandLine cmd = new CommandLine(rootCmd);
        cmd.setUnmatchedArgumentsAllowed(false);
        int exitCode = cmd.execute(args);
        System.exit(exitCode);
    }
}
