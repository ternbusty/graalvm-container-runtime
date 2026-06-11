package com.ternbusty.takoyaki.contest.invalid_spec;

import com.ternbusty.takoyaki.contest.Contest;
import com.ternbusty.takoyaki.contest.Contest.CmdResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Malformed config.json must produce a clean non-zero exit, never a hang
 * and never a stack trace dump that the operator has to read 30 lines of.
 *
 * youki's analogue lives at tests/contest/contest/src/tests/misc_props/.
 */
@Contest.RequiresTakoyaki
class InvalidSpecTest {

    @Test
    void completelyMalformedJsonFailsCleanly(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");
        Files.createDirectories(bundle);
        Files.createDirectories(bundle.resolve("rootfs"));
        Files.writeString(bundle.resolve("config.json"), "{this is not valid json");

        CmdResult r = Contest.run(rootDir,
                "create", "--bundle", bundle.toString(),
                Contest.newContainerId());

        assertNotEquals(0, r.rc(),
                () -> "malformed config.json must NOT silently succeed. "
                        + "stdout=<" + r.stdout() + "> stderr=<" + r.stderr() + ">");
    }

    @Test
    void missingConfigJsonFailsCleanly(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");
        Files.createDirectories(bundle);
        Files.createDirectories(bundle.resolve("rootfs"));
        // no config.json

        CmdResult r = Contest.run(rootDir,
                "create", "--bundle", bundle.toString(),
                Contest.newContainerId());

        assertNotEquals(0, r.rc(),
                () -> "missing config.json must error. stderr=<" + r.stderr() + ">");
    }

    @Test
    void emptyArgsListFailsAtStart(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");
        // process.args MUST have at least one element per OCI spec, but
        // runtime-tools validation/start (test 7) requires the rejection
        // happen at start time — create with no exec-able process still
        // succeeds so the lifecycle can park in 'created'. takoyaki used to
        // reject at create and broke that test.
        Contest.writeBundle(bundle, java.util.Map.of(
                "ociVersion", "1.0.0",
                "process", java.util.Map.of(
                        "terminal", false,
                        "args", java.util.List.of(),
                        "cwd", "/",
                        "user", java.util.Map.of("uid", 0, "gid", 0)
                ),
                "root", java.util.Map.of("path", "rootfs"),
                "linux", java.util.Map.of(
                        "namespaces", java.util.List.of(
                                java.util.Map.of("type", "pid"),
                                java.util.Map.of("type", "mount"),
                                java.util.Map.of("type", "ipc"),
                                java.util.Map.of("type", "uts"),
                                java.util.Map.of("type", "cgroup")
                        )
                )
        ));

        String id = Contest.newContainerId();
        try {
            // create must SUCCEED — the container parks in 'created' waiting
            // for start. This matches what runc and youki do.
            CmdResult create = Contest.run(rootDir,
                    "create", "--bundle", bundle.toString(), id);
            assertEquals(0, create.rc(),
                    () -> "create with empty args must succeed (parks in 'created'). "
                            + "stderr=<" + create.stderr() + ">");

            // start must FAIL — there's nothing to exec.
            CmdResult start = Contest.run(rootDir, "start", id);
            assertNotEquals(0, start.rc(),
                    () -> "start with empty process.args must error. "
                            + "stderr=<" + start.stderr() + ">");
        } finally {
            Contest.run(rootDir, "delete", "--force", id);
        }
    }
}
