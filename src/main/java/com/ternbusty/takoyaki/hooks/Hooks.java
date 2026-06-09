package com.ternbusty.takoyaki.hooks;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.state.State;
import com.ternbusty.takoyaki.util.Json;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Run OCI lifecycle hooks.
 *
 * Each hook is an external command. We pipe the container's current state JSON
 * to the hook's stdin, as required by the OCI runtime spec.
 */
public final class Hooks {
    private Hooks() {}

    public static void run(List<Spec.Hook> hooks, State state, String phase) {
        if (hooks == null || hooks.isEmpty()) return;
        String stateJson = Json.encode(state);
        for (Spec.Hook h : hooks) {
            if (h.path == null) continue;
            List<String> cmd = new ArrayList<>();
            cmd.add(h.path);
            if (h.args != null) cmd.addAll(h.args.subList(1, h.args.size()));
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            if (h.env != null) {
                pb.environment().clear();
                for (String e : h.env) {
                    int eq = e.indexOf('=');
                    if (eq > 0) pb.environment().put(e.substring(0, eq), e.substring(eq + 1));
                }
            }
            try {
                Process p = pb.start();
                try (OutputStream stdin = p.getOutputStream()) {
                    stdin.write(stateJson.getBytes());
                }
                long timeout = h.timeout == null ? 30 : h.timeout;
                boolean done = p.waitFor(timeout, TimeUnit.SECONDS);
                if (!done) {
                    p.destroyForcibly();
                    Logger.warn("hook " + phase + " " + h.path + " timeout after " + timeout + "s");
                    continue;
                }
                int rc = p.exitValue();
                if (rc != 0) {
                    Logger.warn("hook " + phase + " " + h.path + " exit=" + rc);
                } else {
                    Logger.debug("hook " + phase + " " + h.path + " ok");
                }
            } catch (IOException | InterruptedException e) {
                Logger.warn("hook " + phase + " " + h.path + " failed: " + e.getMessage());
            }
        }
    }
}
