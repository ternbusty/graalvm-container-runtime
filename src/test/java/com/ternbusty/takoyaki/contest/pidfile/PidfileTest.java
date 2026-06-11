package com.ternbusty.takoyaki.contest.pidfile;

import com.ternbusty.takoyaki.contest.Contest;
import com.ternbusty.takoyaki.contest.Contest.CmdResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * --pid-file is how runc / containerd-shim hands the container init pid back
 * to the orchestrator. Without a correctly-written pidfile, ctr / kubelet
 * can't kill or attach to the container.
 */
@Contest.RequiresTakoyaki
class PidfileTest {

    @Test
    void pidFileIsCreatedWithPositiveInteger(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");
        Path pidFile = tmp.resolve("pid");

        Contest.writeBundle(bundle, baseSpec());

        String id = Contest.newContainerId();
        CmdResult create = Contest.run(rootDir,
                "create", "--bundle", bundle.toString(),
                "--pid-file", pidFile.toString(), id);
        assertEquals(0, create.rc(), () -> "create failed: " + create.stderr());

        assertTrue(Files.exists(pidFile),
                () -> "pid file " + pidFile + " was not written by create");

        String content = Files.readString(pidFile).trim();
        int pid = Integer.parseInt(content);
        assertTrue(pid > 0,
                () -> "pid file must contain a positive integer, got: <" + content + ">");

        Contest.run(rootDir, "delete", "--force", id);
    }

    @Test
    void absentPidFileFlagWritesNoFile(@TempDir Path tmp) throws Exception {
        // Without --pid-file, no file should be created at the default location.
        // The orchestrator opts in to pid-file plumbing per call.
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");

        Contest.writeBundle(bundle, baseSpec());

        String id = Contest.newContainerId();
        CmdResult create = Contest.run(rootDir,
                "create", "--bundle", bundle.toString(), id);
        assertEquals(0, create.rc());

        // Nothing in the parent tempdir should have showed up as a pid file.
        // We only check that no "pid" file appeared next to the bundle.
        assertFalse(Files.exists(bundle.getParent().resolve("pid")));

        Contest.run(rootDir, "delete", "--force", id);
    }

    private static Map<String, Object> baseSpec() {
        return Map.of(
                "ociVersion", "1.0.0",
                "process", Map.of(
                        "terminal", false,
                        "args", List.of("/bin/true"),
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
