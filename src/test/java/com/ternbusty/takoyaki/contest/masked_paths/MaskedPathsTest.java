package com.ternbusty.takoyaki.contest.masked_paths;

import com.ternbusty.takoyaki.contest.Contest;
import com.ternbusty.takoyaki.contest.Contest.CmdResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * linux.maskedPaths makes the runtime bind-mount /dev/null over each listed
 * file (or a tmpfs over each listed directory) so the container can't see
 * sensitive host state. This contest test only verifies that a spec with
 * maskedPaths is accepted end-to-end — actually validating that /proc/kcore
 * is empty inside the container requires a runtimetest binary baked into
 * the bundle's rootfs, which lives in the runtime-tools validation suite.
 *
 * youki's equivalent: tests/contest/contest/src/tests/linux_masked_paths/.
 */
@Contest.RequiresTakoyaki
class MaskedPathsTest {

    @Test
    void specWithMaskedPathsIsAccepted(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");

        // Real-world masked paths from the runc default. Listing files
        // (/proc/kcore, /proc/keys) and directories (/proc/scsi) exercises
        // both code paths in Rootfs.maskPaths — file gets bound to /dev/null,
        // directory falls back to tmpfs.
        Contest.writeBundle(bundle, Map.of(
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
                        ),
                        "maskedPaths", List.of(
                                "/proc/kcore",
                                "/proc/keys",
                                "/proc/scsi",
                                "/proc/sysrq-trigger"
                        )
                )
        ));

        String id = Contest.newContainerId();
        CmdResult create = Contest.run(rootDir,
                "create", "--bundle", bundle.toString(), id);
        assertEquals(0, create.rc(),
                () -> "create with maskedPaths failed: rc=" + create.rc()
                        + " stdout=<" + create.stdout() + "> stderr=<" + create.stderr() + ">");

        Contest.run(rootDir, "delete", "--force", id);
    }

    @Test
    void specWithReadonlyPathsIsAccepted(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");

        // readonlyPaths is the sibling of maskedPaths: each path gets self-bound
        // and then MS_REMOUNTed read-only. Listed as a separate test because
        // Rootfs.readonlyRemount is a different code path from maskPaths.
        Contest.writeBundle(bundle, Map.of(
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
                        ),
                        "readonlyPaths", List.of(
                                "/proc/asound",
                                "/proc/bus",
                                "/proc/fs",
                                "/proc/irq",
                                "/proc/sys"
                        )
                )
        ));

        String id = Contest.newContainerId();
        CmdResult create = Contest.run(rootDir,
                "create", "--bundle", bundle.toString(), id);
        assertEquals(0, create.rc(),
                () -> "create with readonlyPaths failed: rc=" + create.rc()
                        + " stderr=<" + create.stderr() + ">");

        Contest.run(rootDir, "delete", "--force", id);
    }
}
