package com.ternbusty.takoyaki.cgroup;

import com.ternbusty.takoyaki.spec.Spec;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CgroupTest {

    private static Spec.LinuxResources resources() {
        return new Spec.LinuxResources();
    }

    @Test
    void nullCgroupPathIsNoOp() {
        try (MockedStatic<Files> fm = mockStatic(Files.class)) {
            Cgroup.setup(123, null, null);
            fm.verifyNoInteractions();
        }
    }

    @Test
    void leadingSlashIsStrippedBeforeResolving() {
        // We accept both "/takoyaki-x" and "takoyaki-x" from the spec. The
        // resulting cgroup path must be /sys/fs/cgroup/takoyaki-x either way.
        try (MockedStatic<Files> fm = mockStatic(Files.class)) {
            fm.when(() -> Files.createDirectories(any())).thenReturn(null);
            fm.when(() -> Files.writeString(any(), anyString())).thenReturn(Path.of("/dev/null"));

            Cgroup.setup(123, "/takoyaki-x", null);
            fm.verify(() -> Files.createDirectories(
                    eq(Path.of("/sys/fs/cgroup/takoyaki-x"))));
            fm.verify(() -> Files.writeString(
                    eq(Path.of("/sys/fs/cgroup/takoyaki-x/cgroup.procs")),
                    eq("123")));
        }
    }

    @Test
    void memoryLimitWritesMemoryMax() {
        // Confirm spec.linux.resources.memory.limit lands at memory.max with
        // the exact value (or "max" sentinel for -1).
        Spec.LinuxResources r = resources();
        r.memory = new Spec.LinuxMemory();
        r.memory.limit = 67108864L; // 64 MiB

        try (MockedStatic<Files> fm = mockStatic(Files.class)) {
            fm.when(() -> Files.createDirectories(any())).thenReturn(null);
            fm.when(() -> Files.writeString(any(), anyString())).thenReturn(Path.of("/dev/null"));
            Spec.Linux linux = new Spec.Linux();
            linux.resources = r;
            Cgroup.setup(123, "/takoyaki-mem", linux);

            fm.verify(() -> Files.writeString(
                    eq(Path.of("/sys/fs/cgroup/takoyaki-mem/memory.max")),
                    eq("67108864")));
        }
    }

    @Test
    void memoryMinusOneLimitWritesMaxSentinel() {
        Spec.LinuxResources r = resources();
        r.memory = new Spec.LinuxMemory();
        r.memory.limit = -1L;

        try (MockedStatic<Files> fm = mockStatic(Files.class)) {
            fm.when(() -> Files.createDirectories(any())).thenReturn(null);
            fm.when(() -> Files.writeString(any(), anyString())).thenReturn(Path.of("/dev/null"));
            Spec.Linux linux = new Spec.Linux();
            linux.resources = r;
            Cgroup.setup(123, "/takoyaki-mem", linux);

            fm.verify(() -> Files.writeString(
                    eq(Path.of("/sys/fs/cgroup/takoyaki-mem/memory.max")),
                    eq("max")));
        }
    }

    @Test
    void cpuCpusetIsApplied() {
        Spec.LinuxResources r = resources();
        r.cpu = new Spec.LinuxCpu();
        r.cpu.cpus = "0-1";

        try (MockedStatic<Files> fm = mockStatic(Files.class)) {
            fm.when(() -> Files.createDirectories(any())).thenReturn(null);
            fm.when(() -> Files.writeString(any(), anyString())).thenReturn(Path.of("/dev/null"));
            Spec.Linux linux = new Spec.Linux();
            linux.resources = r;
            Cgroup.setup(123, "/takoyaki-cpu", linux);

            fm.verify(() -> Files.writeString(
                    eq(Path.of("/sys/fs/cgroup/takoyaki-cpu/cpuset.cpus")),
                    eq("0-1")));
        }
    }

    @Test
    void cpuQuotaAndPeriodAreCombinedIntoCpuMax() {
        // cgroup v2 writes both as one string "quota period".
        Spec.LinuxResources r = resources();
        r.cpu = new Spec.LinuxCpu();
        r.cpu.quota = 50000L;
        r.cpu.period = 100000L;

        try (MockedStatic<Files> fm = mockStatic(Files.class)) {
            fm.when(() -> Files.createDirectories(any())).thenReturn(null);
            fm.when(() -> Files.writeString(any(), anyString())).thenReturn(Path.of("/dev/null"));
            Spec.Linux linux = new Spec.Linux();
            linux.resources = r;
            Cgroup.setup(123, "/takoyaki-q", linux);

            fm.verify(() -> Files.writeString(
                    eq(Path.of("/sys/fs/cgroup/takoyaki-q/cpu.max")),
                    eq("50000 100000")));
        }
    }

    @Test
    void pidsLimitIsApplied() {
        Spec.LinuxResources r = resources();
        r.pids = new Spec.LinuxPids();
        r.pids.limit = 100;

        try (MockedStatic<Files> fm = mockStatic(Files.class)) {
            fm.when(() -> Files.createDirectories(any())).thenReturn(null);
            fm.when(() -> Files.writeString(any(), anyString())).thenReturn(Path.of("/dev/null"));
            Spec.Linux linux = new Spec.Linux();
            linux.resources = r;
            Cgroup.setup(123, "/takoyaki-p", linux);

            fm.verify(() -> Files.writeString(
                    eq(Path.of("/sys/fs/cgroup/takoyaki-p/pids.max")),
                    eq("100")));
        }
    }

    @Test
    void enableControllersWalksParentChainTowardsRoot() {
        // For a nested cgroup like /takoyaki/sub the runtime must "+memory"
        // (etc.) in EACH ancestor's cgroup.subtree_control. Verify that
        // subtree_control writes hit at least the root.
        Spec.LinuxResources r = resources();
        r.memory = new Spec.LinuxMemory();
        r.memory.limit = 4096L;

        try (MockedStatic<Files> fm = mockStatic(Files.class)) {
            fm.when(() -> Files.createDirectories(any())).thenReturn(null);
            fm.when(() -> Files.writeString(any(), anyString())).thenReturn(Path.of("/dev/null"));
            Spec.Linux linux = new Spec.Linux();
            linux.resources = r;
            Cgroup.setup(123, "/takoyaki/sub", linux);

            // The root cgroup must get a "+memory" write.
            fm.verify(() -> Files.writeString(
                    eq(Path.of("/sys/fs/cgroup/cgroup.subtree_control")),
                    eq("+memory")));
        }
    }

    @Test
    void cleanupRemovesDirectoryWhenItExists() {
        try (MockedStatic<Files> fm = mockStatic(Files.class)) {
            fm.when(() -> Files.exists(eq(Path.of("/sys/fs/cgroup/takoyaki-x")))).thenReturn(true);
            Cgroup.cleanup("/takoyaki-x");
            fm.verify(() -> Files.delete(eq(Path.of("/sys/fs/cgroup/takoyaki-x"))));
        }
    }

    @Test
    void cleanupSkipsWhenDirectoryDoesNotExist() {
        try (MockedStatic<Files> fm = mockStatic(Files.class)) {
            fm.when(() -> Files.exists(any())).thenReturn(false);
            Cgroup.cleanup("/takoyaki-x");
            fm.verify(() -> Files.delete(any()), never());
        }
    }

    @Test
    void cleanupNullIsNoOp() {
        try (MockedStatic<Files> fm = mockStatic(Files.class)) {
            Cgroup.cleanup(null);
            fm.verifyNoInteractions();
        }
    }
}
