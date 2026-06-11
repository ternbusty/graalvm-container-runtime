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
    void emptyArgsListFailsCreate(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");
        // process.args MUST have at least one element per OCI spec — there has
        // to be SOMETHING to exec. An empty array is a malformed spec.
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
        CmdResult r = Contest.run(rootDir,
                "create", "--bundle", bundle.toString(), id);

        assertNotEquals(0, r.rc(),
                () -> "empty process.args must error at create or start time. "
                        + "stderr=<" + r.stderr() + ">");

        Contest.run(rootDir, "delete", "--force", id);
    }
}
