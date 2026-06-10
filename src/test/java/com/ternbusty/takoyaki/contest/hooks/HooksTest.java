package com.ternbusty.takoyaki.contest.hooks;

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
 * Prestart hooks fire on the HOST (runtime namespace), after the container
 * is fully configured but before the user process is exec'd. They receive
 * the container state on stdin as JSON.
 *
 * youki's equivalent lives under tests/contest/contest/src/tests/prestart/.
 */
@Contest.RequiresTakoyaki
class HooksTest {

    @Test
    void prestartHookRunsDuringCreate(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");
        Path marker = tmp.resolve("prestart-marker");

        // Lay down a prestart hook that touches a file on the host. We
        // never need to look inside the container — the hook ran on
        // the runtime side, so the marker shows up in the test tmpdir
        // regardless of whether the container itself has any rootfs.
        Contest.writeBundle(bundle, Map.of(
                "ociVersion", "1.0.0",
                "process", Map.of(
                        "terminal", false,
                        "args", List.of("/bin/true"),
                        "cwd", "/",
                        "user", Map.of("uid", 0, "gid", 0)
                ),
                "root", Map.of("path", "rootfs"),
                "hooks", Map.of(
                        "prestart", List.of(Map.of(
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
        CmdResult create = Contest.run(rootDir,
                "create", "--bundle", bundle.toString(), id);
        assertEquals(0, create.rc(),
                () -> "create failed: " + create.stderr());

        // Hook must have fired by the time `create` returns.
        assertTrue(Files.exists(marker),
                () -> "prestart hook did not run — marker file " + marker
                        + " was not created. create stderr: " + create.stderr());

        Contest.run(rootDir, "delete", "--force", id);
    }

    @Test
    void prestartHookFailureFailsCreate(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");

        // A prestart hook returning non-zero MUST fail the create. Per OCI
        // spec, prestart hook failure aborts the lifecycle. Without that
        // gate, a container with a broken environment-prep hook would still
        // try to run the user process against an inconsistent host state.
        Contest.writeBundle(bundle, Map.of(
                "ociVersion", "1.0.0",
                "process", Map.of(
                        "terminal", false,
                        "args", List.of("/bin/true"),
                        "cwd", "/",
                        "user", Map.of("uid", 0, "gid", 0)
                ),
                "root", Map.of("path", "rootfs"),
                "hooks", Map.of(
                        "prestart", List.of(Map.of(
                                "path", "/bin/sh",
                                "args", List.of("sh", "-c", "exit 17")
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
        CmdResult create = Contest.run(rootDir,
                "create", "--bundle", bundle.toString(), id);

        assertNotEquals(0, create.rc(),
                "create must NOT return 0 when prestart hook exits non-zero. "
                        + "stdout=" + create.stdout() + " stderr=" + create.stderr());

        // Best-effort cleanup. delete may also fail (state file may be partial).
        Contest.run(rootDir, "delete", "--force", id);
    }
}
