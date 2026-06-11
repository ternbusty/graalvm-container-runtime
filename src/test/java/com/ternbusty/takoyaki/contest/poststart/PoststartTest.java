package com.ternbusty.takoyaki.contest.poststart;

import com.ternbusty.takoyaki.contest.Contest;
import com.ternbusty.takoyaki.contest.Contest.CmdResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * poststart hooks fire on the host after the user process is exec'd. Unlike
 * prestart, a failure is logged but does NOT abort the lifecycle.
 */
@Contest.RequiresTakoyaki
class PoststartTest {

    @Test
    void poststartHookFiresAfterStart(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");
        Path rootfs = bundle.resolve("rootfs");
        Path marker = tmp.resolve("poststart-marker");
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
                        "poststart", List.of(Map.of(
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

            // Marker must NOT exist before start — poststart is start-time, not create-time.
            assertFalse(Files.exists(marker),
                    () -> "poststart fired during create — wrong phase. marker=" + marker);

            CmdResult start = Contest.run(rootDir, "start", id);
            assertEquals(0, start.rc(), () -> "start failed: " + start.stderr());

            // Give the hook a moment to run; we don't wait on start's process
            // synchronously for hooks.
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (System.nanoTime() < deadline && !Files.exists(marker)) {
                Thread.sleep(50);
            }
            assertTrue(Files.exists(marker),
                    () -> "poststart hook never fired (marker " + marker + " absent). "
                            + "start stderr: " + start.stderr());
        } finally {
            Contest.forceCleanup(rootDir, id);
        }
    }
}
