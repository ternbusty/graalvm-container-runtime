package com.ternbusty.takoyaki.contest.state_after_kill;

import com.ternbusty.takoyaki.contest.Contest;
import com.ternbusty.takoyaki.contest.Contest.CmdResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * After kill SIGKILL, the state JSON must reflect "stopped". Orchestrators
 * use this transition to know it's safe to call delete.
 */
@Contest.RequiresTakoyaki
class StateAfterKillTest {

    @Test
    void killSigkillTransitionsToStopped(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");
        Path rootfs = bundle.resolve("rootfs");
        java.nio.file.Files.createDirectories(rootfs);
        assumeTrue(Contest.stageBusyboxRootfs(rootfs) != null,
                "busybox not available; skipping");

        Contest.writeBundle(bundle, Map.of(
                "ociVersion", "1.0.0",
                "process", Map.of(
                        "terminal", false,
                        "args", List.of("/bin/sleep", "60"),
                        "cwd", "/",
                        "user", Map.of("uid", 0, "gid", 0)
                ),
                "root", Map.of("path", "rootfs"),
                "linux", Map.of(
                        "namespaces", List.of(
                                Map.of("type", "pid"),
                                Map.of("type", "mount"),
                                Map.of("type", "ipc"),
                                Map.of("type", "uts"),
                                Map.of("type", "cgroup")
                        )
                )
        ));

        String id = Contest.newContainerId();
        try {
            assertEquals(0, Contest.run(rootDir,
                    "create", "--bundle", bundle.toString(), id).rc());
            assertEquals(0, Contest.run(rootDir, "start", id).rc());
            assertTrue(Contest.waitForStatus(rootDir, id, "running", 2000),
                    "container never reached running");

            CmdResult kill = Contest.run(rootDir, "kill", id, "KILL");
            assertEquals(0, kill.rc(), () -> "kill failed: " + kill.stderr());

            // SIGKILL is immediate, but state refresh involves a kill(pid, 0)
            // probe — give it 2 seconds to settle.
            boolean stopped = Contest.waitForStatus(rootDir, id, "stopped", 2000);
            assertTrue(stopped,
                    () -> "state never reached 'stopped' after SIGKILL. Last state: "
                            + tryReadState(rootDir, id));
        } finally {
            Contest.forceCleanup(rootDir, id);
        }
    }

    private static String tryReadState(Path rootDir, String id) {
        try {
            return Contest.run(rootDir, "state", id).stdout();
        } catch (Exception e) {
            return "(state failed: " + e.getMessage() + ")";
        }
    }
}
