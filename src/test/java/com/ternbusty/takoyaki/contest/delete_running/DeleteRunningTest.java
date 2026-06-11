package com.ternbusty.takoyaki.contest.delete_running;

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
 * delete on a still-running container MUST fail unless --force is passed.
 * Otherwise an operator's stray `takoyaki delete id` would kill production
 * containers.
 *
 * --force MUST succeed in the same situation (the orchestrator's escape
 * hatch when it knows it wants the container gone).
 */
@Contest.RequiresTakoyaki
class DeleteRunningTest {

    @Test
    void deleteWithoutForceOnRunningContainerErrors(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");
        Path rootfs = bundle.resolve("rootfs");
        java.nio.file.Files.createDirectories(rootfs);
        assumeTrue(Contest.stageBusyboxRootfs(rootfs) != null,
                "busybox not available; skipping");

        Contest.writeBundle(bundle, longRunningSpec());

        String id = Contest.newContainerId();
        try {
            assertEquals(0, Contest.run(rootDir,
                    "create", "--bundle", bundle.toString(), id).rc());
            assertEquals(0, Contest.run(rootDir, "start", id).rc());
            assertTrue(Contest.waitForStatus(rootDir, id, "running", 2000));

            CmdResult delete = Contest.run(rootDir, "delete", id);
            assertNotEquals(0, delete.rc(),
                    () -> "delete WITHOUT --force on a running container must fail. "
                            + "stderr=<" + delete.stderr() + ">");

            // Within-test cleanup. We still wrap with forceCleanup in finally
            // for the paths where this delete --force itself fails.
            CmdResult forced = Contest.run(rootDir, "delete", "--force", id);
            assertEquals(0, forced.rc(),
                    () -> "delete --force must succeed on running container. "
                            + "stderr=<" + forced.stderr() + ">");
        } finally {
            Contest.forceCleanup(rootDir, id);
        }
    }

    private static Map<String, Object> longRunningSpec() {
        return Map.of(
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
        );
    }
}
