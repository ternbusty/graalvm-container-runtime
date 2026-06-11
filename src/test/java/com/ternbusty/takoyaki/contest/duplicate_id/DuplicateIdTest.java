package com.ternbusty.takoyaki.contest.duplicate_id;

import com.ternbusty.takoyaki.contest.Contest;
import com.ternbusty.takoyaki.contest.Contest.CmdResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Container ids are unique per --root directory. Creating the same id twice
 * MUST fail; otherwise orchestrators that hold an id-keyed map would lose
 * track of the original container and orphan it.
 */
@Contest.RequiresTakoyaki
class DuplicateIdTest {

    @Test
    void secondCreateWithSameIdFails(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");

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
                        )
                )
        ));

        String id = Contest.newContainerId();

        CmdResult first = Contest.run(rootDir,
                "create", "--bundle", bundle.toString(), id);
        assertEquals(0, first.rc(), () -> "first create failed: " + first.stderr());

        // Second create with the same id MUST fail. Tolerating it would
        // overwrite the existing state.json and lose the pid.
        CmdResult second = Contest.run(rootDir,
                "create", "--bundle", bundle.toString(), id);
        assertNotEquals(0, second.rc(),
                () -> "second create with the same id MUST fail. "
                        + "stdout=<" + second.stdout() + "> stderr=<" + second.stderr() + ">");

        Contest.run(rootDir, "delete", "--force", id);
    }
}
