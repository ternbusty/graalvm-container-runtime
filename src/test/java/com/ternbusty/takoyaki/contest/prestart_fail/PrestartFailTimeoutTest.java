package com.ternbusty.takoyaki.contest.prestart_fail;

import com.ternbusty.takoyaki.contest.Contest;
import com.ternbusty.takoyaki.contest.Contest.CmdResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prestart hook with a tight timeout. The hook sleeps longer than its
 * declared timeout — the runtime MUST kill the hook, treat it as failed,
 * and abort create. A bug where timeout is ignored would let the create
 * proceed silently after killing the hook, which is non-conformant.
 */
@Contest.RequiresTakoyaki
class PrestartFailTimeoutTest {

    @Test
    void prestartHookTimeoutAbortsCreate(@TempDir Path tmp) throws Exception {
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
                "hooks", Map.of(
                        "prestart", List.of(Map.of(
                                "path", "/bin/sh",
                                "args", List.of("sh", "-c", "sleep 10"),
                                // Timeout of 1 second; the hook sleeps 10. Kernel SIGKILL
                                // arrives at the 1-second mark and the hook becomes "failed".
                                "timeout", 1
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
        long start = System.nanoTime();
        CmdResult create = Contest.run(rootDir,
                "create", "--bundle", bundle.toString(), id);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertNotEquals(0, create.rc(),
                () -> "create with prestart timeout MUST fail. "
                        + "stderr=<" + create.stderr() + ">");

        // Sanity: the whole create finished well before the hook's 10-second
        // sleep would have naturally ended. Confirms the timeout kicked in
        // rather than the hook running to completion.
        assertTrue(elapsedMs < 8000,
                () -> "create took " + elapsedMs + " ms — timeout doesn't seem"
                        + " to have killed the hook early");

        Contest.run(rootDir, "delete", "--force", id);
    }
}
