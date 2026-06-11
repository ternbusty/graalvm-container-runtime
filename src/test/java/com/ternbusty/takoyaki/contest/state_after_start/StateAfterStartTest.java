package com.ternbusty.takoyaki.contest.state_after_start;

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
 * After start, the state JSON must reflect "running". The OCI lifecycle
 * is created -> running -> stopped, and orchestrators poll state to know
 * when start has actually taken effect.
 *
 * Needs a real user process inside the container, so this test stages
 * busybox in the rootfs and runs {@code sleep 60} as the workload.
 */
@Contest.RequiresTakoyaki
class StateAfterStartTest {

    @Test
    void stateReportsRunningAfterStart(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");
        Path rootfs = bundle.resolve("rootfs");
        java.nio.file.Files.createDirectories(rootfs);
        assumeTrue(Contest.stageBusyboxRootfs(rootfs) != null,
                "busybox not available on host; skipping start-based contest");

        // sleep 60 keeps the container alive long enough for state polling.
        // We delete-force at the end so we never wait for the actual sleep.
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
            CmdResult create = Contest.run(rootDir,
                    "create", "--bundle", bundle.toString(), id);
            assertEquals(0, create.rc(), () -> "create failed: " + create.stderr());

            CmdResult start = Contest.run(rootDir, "start", id);
            assertEquals(0, start.rc(), () -> "start failed: " + start.stderr());

            // State takes a tick to flip from created -> running. 2-second budget
            // is generous (sleep is well-warm by 50 ms in practice).
            boolean reachedRunning = Contest.waitForStatus(rootDir, id, "running", 2000);
            assertTrue(reachedRunning,
                    () -> "state never reflected 'running'. Last state: "
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
