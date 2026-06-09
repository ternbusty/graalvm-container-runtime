package io.github.ternbusty.gcr.command;

import io.github.ternbusty.gcr.logger.Logger;
import io.github.ternbusty.gcr.state.State;
import io.github.ternbusty.gcr.syscall.Constants;
import io.github.ternbusty.gcr.syscall.Libc;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.util.concurrent.Callable;

@Command(name = "kill", description = "Send a signal to a container")
public final class KillCommand implements Callable<Integer> {
    @ParentCommand
    GcrRoot root;

    @Parameters(index = "0", description = "Container ID")
    String containerId;

    @Parameters(index = "1", arity = "0..1", description = "Signal name or number",
            defaultValue = "SIGTERM")
    String signal;

    @Override
    public Integer call() {
        State state;
        try {
            state = State.load(root.rootPath, containerId).refreshStatus();
        } catch (Exception e) {
            Logger.error("failed to load state: " + e.getMessage());
            return 1;
        }
        if (!state.statusEnum().canKill()) {
            Logger.error("cannot kill container in '" + state.status + "' state");
            return 1;
        }
        if (state.pid == null) {
            Logger.error("no pid in state");
            return 1;
        }
        int sig;
        try { sig = parseSignal(signal); }
        catch (IllegalArgumentException e) {
            Logger.error("invalid signal: " + signal);
            return 1;
        }
        int rc = Libc.kill(state.pid, sig);
        if (rc != 0 && Libc.errno() != Constants.ESRCH) {
            Logger.error("kill failed: " + Libc.strerror(Libc.errno()));
            return 1;
        }
        Logger.info("sent signal " + signal + " to pid " + state.pid);
        return 0;
    }

    public static int parseSignal(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        String n = s.startsWith("SIG") ? s : "SIG" + s;
        return switch (n.toUpperCase()) {
            case "SIGHUP" -> Constants.SIGHUP;
            case "SIGINT" -> Constants.SIGINT;
            case "SIGQUIT" -> Constants.SIGQUIT;
            case "SIGILL" -> Constants.SIGILL;
            case "SIGABRT" -> Constants.SIGABRT;
            case "SIGFPE" -> Constants.SIGFPE;
            case "SIGKILL" -> Constants.SIGKILL;
            case "SIGSEGV" -> Constants.SIGSEGV;
            case "SIGPIPE" -> Constants.SIGPIPE;
            case "SIGALRM" -> Constants.SIGALRM;
            case "SIGTERM" -> Constants.SIGTERM;
            case "SIGUSR1" -> Constants.SIGUSR1;
            case "SIGUSR2" -> Constants.SIGUSR2;
            case "SIGCHLD" -> Constants.SIGCHLD;
            case "SIGCONT" -> Constants.SIGCONT;
            case "SIGSTOP" -> Constants.SIGSTOP;
            case "SIGTSTP" -> Constants.SIGTSTP;
            case "SIGTTIN" -> Constants.SIGTTIN;
            case "SIGTTOU" -> Constants.SIGTTOU;
            default -> throw new IllegalArgumentException("unknown signal: " + s);
        };
    }
}
