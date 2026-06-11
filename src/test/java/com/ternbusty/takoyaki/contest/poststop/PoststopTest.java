package com.ternbusty.takoyaki.contest.poststop;

import com.ternbusty.takoyaki.contest.Contest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * poststop hooks fire on the host AFTER the container is fully torn down
 * (delete). They're typically used to clean up state on the host that the
 * container left behind. Failure is best-effort; the delete must still
 * succeed.
 */
@Contest.RequiresTakoyaki
class PoststopTest {

    @Test
    void poststopHookFiresAfterDelete(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");
        Path rootfs = bundle.resolve("rootfs");
        Path marker = tmp.resolve("poststop-marker");
        Files.createDirectories(rootfs);
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
                "hooks", Map.of(
                        "poststop", List.of(Map.of(
                                "path", "/bin/sh",
                                "args", List.of(
                                        "sh", "-c",
                                        "touch " + marker.toAbsolutePath())
                        ))
                ),
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

            // Marker must NOT exist before delete.
            assertFalse(Files.exists(marker));

            // Delete the (still-created, not-started) container with --force.
            // Even without a started workload, delete still triggers poststop
            // per OCI semantics.
            Contest.run(rootDir, "delete", "--force", id);

            long deadline = System.nanoTime() + 2_000_000_000L;
            while (System.nanoTime() < deadline && !Files.exists(marker)) {
                Thread.sleep(50);
            }
            assertTrue(Files.exists(marker),
                    () -> "poststop hook never fired (marker " + marker + " absent)");
        } finally {
            // Best-effort cleanup for the failure paths (e.g. delete itself errored).
            Contest.forceCleanup(rootDir, id);
        }
    }
}
