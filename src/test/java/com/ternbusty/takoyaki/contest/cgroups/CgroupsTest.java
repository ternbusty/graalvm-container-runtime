package com.ternbusty.takoyaki.contest.cgroups;

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
 * cgroup v2 family. Each test sets a specific resource limit in
 * spec.linux.resources, then verifies the kernel-facing file under
 * /sys/fs/cgroup/{cgroupsPath}/ ends up with the expected value.
 *
 * These are HOST-OBSERVABLE: cgroup files are visible from the runtime
 * namespace; we don't need to be inside the container to read them. The
 * file content IS the conformance point — the kernel will enforce whatever
 * is written there regardless of whether the container is started.
 */
@Contest.RequiresTakoyaki
class CgroupsTest {

    @Test
    void cgroupsPathDirectoryIsCreated(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");
        String cgPath = "/takoyaki-test-" + System.nanoTime();

        Contest.writeBundle(bundle, baseSpec(cgPath, Map.of()));

        String id = Contest.newContainerId();
        CmdResult create = Contest.run(rootDir,
                "create", "--bundle", bundle.toString(), id);
        assertEquals(0, create.rc(), () -> "create failed: " + create.stderr());

        // The leaf cgroup directory must exist after create.
        Path cgDir = Path.of("/sys/fs/cgroup" + cgPath);
        assertTrue(Files.isDirectory(cgDir),
                () -> "cgroup directory " + cgDir + " was not created. "
                        + "create stderr: " + create.stderr());

        Contest.run(rootDir, "delete", "--force", id);
    }

    @Test
    void memoryLimitIsWrittenToMemoryMax(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");
        String cgPath = "/takoyaki-test-" + System.nanoTime();
        long memLimit = 64L * 1024 * 1024; // 64 MB

        Contest.writeBundle(bundle, baseSpec(cgPath, Map.of(
                "memory", Map.of("limit", memLimit)
        )));

        String id = Contest.newContainerId();
        CmdResult create = Contest.run(rootDir,
                "create", "--bundle", bundle.toString(), id);
        assertEquals(0, create.rc(), () -> "create failed: " + create.stderr());

        Path memMax = Path.of("/sys/fs/cgroup" + cgPath + "/memory.max");
        assertTrue(Files.exists(memMax),
                () -> "memory.max not created at " + memMax);

        String content = Files.readString(memMax).trim();
        assertEquals(String.valueOf(memLimit), content,
                () -> "memory.max expected " + memLimit + " but was <" + content + ">");

        Contest.run(rootDir, "delete", "--force", id);
    }

    @Test
    void pidsLimitIsWrittenToPidsMax(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");
        String cgPath = "/takoyaki-test-" + System.nanoTime();

        Contest.writeBundle(bundle, baseSpec(cgPath, Map.of(
                "pids", Map.of("limit", 100)
        )));

        String id = Contest.newContainerId();
        CmdResult create = Contest.run(rootDir,
                "create", "--bundle", bundle.toString(), id);
        assertEquals(0, create.rc(), () -> "create failed: " + create.stderr());

        Path pidsMax = Path.of("/sys/fs/cgroup" + cgPath + "/pids.max");
        assertTrue(Files.exists(pidsMax),
                () -> "pids.max not created at " + pidsMax);

        String content = Files.readString(pidsMax).trim();
        assertEquals("100", content,
                () -> "pids.max expected 100 but was <" + content + ">");

        Contest.run(rootDir, "delete", "--force", id);
    }

    @Test
    void cpuPeriodAndQuotaAreWrittenToCpuMax(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");
        String cgPath = "/takoyaki-test-" + System.nanoTime();

        // cgroup v2 packs both quota and period into cpu.max as one line:
        // "<quota> <period>". Setting quota=50000 period=100000 means 0.5 CPU.
        Contest.writeBundle(bundle, baseSpec(cgPath, Map.of(
                "cpu", Map.of("period", 100000, "quota", 50000)
        )));

        String id = Contest.newContainerId();
        CmdResult create = Contest.run(rootDir,
                "create", "--bundle", bundle.toString(), id);
        assertEquals(0, create.rc(), () -> "create failed: " + create.stderr());

        Path cpuMax = Path.of("/sys/fs/cgroup" + cgPath + "/cpu.max");
        assertTrue(Files.exists(cpuMax));

        String content = Files.readString(cpuMax).trim();
        assertEquals("50000 100000", content,
                () -> "cpu.max expected '50000 100000' but was <" + content + ">");

        Contest.run(rootDir, "delete", "--force", id);
    }

    @Test
    void cgroupDirectoryIsRemovedAfterDelete(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");
        String cgPath = "/takoyaki-test-" + System.nanoTime();

        Contest.writeBundle(bundle, baseSpec(cgPath, Map.of()));

        String id = Contest.newContainerId();
        CmdResult create = Contest.run(rootDir,
                "create", "--bundle", bundle.toString(), id);
        assertEquals(0, create.rc());

        Path cgDir = Path.of("/sys/fs/cgroup" + cgPath);
        assertTrue(Files.isDirectory(cgDir));

        CmdResult delete = Contest.run(rootDir, "delete", "--force", id);
        assertEquals(0, delete.rc());

        // After delete, the cgroup MUST be reaped. Stale cgroups
        // are a long-running OOM-killer leak source for orchestrators
        // that don't reap them themselves.
        //
        // Tiny poll: cgroup tear-down has an unavoidable async tail in the
        // kernel (cgroup_destroy_locked schedules work). Even after takoyaki
        // returns successfully, the directory entry can survive ~10-50 ms on
        // fast hosts. Wait up to 2 seconds for it to disappear before
        // declaring a leak.
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline && Files.exists(cgDir)) {
            Thread.sleep(50);
        }
        assertFalse(Files.exists(cgDir),
                () -> "cgroup directory " + cgDir + " leaked after delete. "
                        + "delete stderr: " + delete.stderr());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> baseSpec(String cgroupsPath,
                                                Map<String, Object> resources) {
        Map<String, Object> linux = new java.util.LinkedHashMap<>();
        linux.put("cgroupsPath", cgroupsPath);
        linux.put("namespaces", List.of(
                Map.of("type", "pid"),
                Map.of("type", "mount"),
                Map.of("type", "ipc"),
                Map.of("type", "uts"),
                Map.of("type", "cgroup")
        ));
        if (!resources.isEmpty()) {
            linux.put("resources", resources);
        }
        return Map.of(
                "ociVersion", "1.0.0",
                "process", Map.of(
                        "terminal", false,
                        "args", List.of("/bin/true"),
                        "cwd", "/",
                        "user", Map.of("uid", 0, "gid", 0)
                ),
                "root", Map.of("path", "rootfs"),
                "linux", linux
        );
    }
}
