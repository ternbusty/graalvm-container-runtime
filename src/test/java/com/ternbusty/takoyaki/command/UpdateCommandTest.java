package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.cgroup.Cgroup;
import com.ternbusty.takoyaki.config.KontainerConfig;
import com.ternbusty.takoyaki.spec.Spec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UpdateCommandTest {

    private static UpdateCommand newCmd(String rootPath, String id) {
        UpdateCommand c = new UpdateCommand();
        c.root = new TakoyakiRoot();
        c.root.rootPath = rootPath;
        c.containerId = id;
        return c;
    }

    @Test
    void returnsErrorWhenNoKontainerConfigExists(@TempDir Path tmp) {
        // update against an unknown id must NOT silently succeed — Cgroup
        // wouldn't know where to write. We surface non-zero.
        try (MockedStatic<Cgroup> cm = mockStatic(Cgroup.class)) {
            int rc = newCmd(tmp.toString(), "absent").call();
            assertEquals(1, rc);
            cm.verify(() -> Cgroup.applyLimitsOnly(anyString(),
                    any(Spec.LinuxResources.class)), never());
        }
    }

    @Test
    void returnsErrorWhenConfigHasNullCgroupPath(@TempDir Path tmp) throws IOException {
        new KontainerConfig(null).save(tmp.toString(), "no-cgroup");
        try (MockedStatic<Cgroup> cm = mockStatic(Cgroup.class)) {
            int rc = newCmd(tmp.toString(), "no-cgroup").call();
            assertEquals(1, rc);
            cm.verify(() -> Cgroup.applyLimitsOnly(anyString(),
                    any(Spec.LinuxResources.class)), never());
        }
    }

    @Test
    void memoryFlagOnlyPopulatesMemoryLimit(@TempDir Path tmp) throws IOException {
        new KontainerConfig("/sys/fs/cgroup/user.slice/x")
                .save(tmp.toString(), "ctr");

        UpdateCommand c = newCmd(tmp.toString(), "ctr");
        c.memory = 256L * 1024 * 1024;

        try (MockedStatic<Cgroup> cm = mockStatic(Cgroup.class)) {
            int rc = c.call();
            assertEquals(0, rc);
            ArgumentCaptor<Spec.LinuxResources> arg =
                    ArgumentCaptor.forClass(Spec.LinuxResources.class);
            cm.verify(() -> Cgroup.applyLimitsOnly(
                    eq("/sys/fs/cgroup/user.slice/x"), arg.capture()));
            Spec.LinuxResources r = arg.getValue();
            // Only memory.limit should be populated. cpu/pids must stay null
            // so applyLimitsOnly knows not to touch their controllers.
            assertNotNull(r.memory, "memory block must be created");
            assertEquals(256L * 1024 * 1024, r.memory.limit);
            assertNull(r.cpu);
            assertNull(r.pids);
        }
    }

    @Test
    void cpuFlagsAllPopulateCpuBlock(@TempDir Path tmp) throws IOException {
        new KontainerConfig("/sys/fs/cgroup/x").save(tmp.toString(), "ctr");

        UpdateCommand c = newCmd(tmp.toString(), "ctr");
        c.cpuQuota  = 50000L;
        c.cpuPeriod = 100000L;
        c.cpuShares = 1024L;

        try (MockedStatic<Cgroup> cm = mockStatic(Cgroup.class)) {
            assertEquals(0, c.call());
            ArgumentCaptor<Spec.LinuxResources> arg =
                    ArgumentCaptor.forClass(Spec.LinuxResources.class);
            cm.verify(() -> Cgroup.applyLimitsOnly(anyString(), arg.capture()));
            Spec.LinuxResources r = arg.getValue();
            assertNotNull(r.cpu);
            assertEquals(50000L,  r.cpu.quota);
            assertEquals(100000L, r.cpu.period);
            assertEquals(1024L,   r.cpu.shares);
            // memory/pids not touched.
            assertNull(r.memory);
            assertNull(r.pids);
        }
    }

    @Test
    void pidsLimitFlagPopulatesPidsBlock(@TempDir Path tmp) throws IOException {
        new KontainerConfig("/sys/fs/cgroup/x").save(tmp.toString(), "ctr");

        UpdateCommand c = newCmd(tmp.toString(), "ctr");
        c.pidsLimit = 512L;

        try (MockedStatic<Cgroup> cm = mockStatic(Cgroup.class)) {
            assertEquals(0, c.call());
            ArgumentCaptor<Spec.LinuxResources> arg =
                    ArgumentCaptor.forClass(Spec.LinuxResources.class);
            cm.verify(() -> Cgroup.applyLimitsOnly(anyString(), arg.capture()));
            assertNotNull(arg.getValue().pids);
            assertEquals(512L, arg.getValue().pids.limit);
        }
    }

    @Test
    void resourcesJsonFileIsLoadedAndOverriddenByFlags(@TempDir Path tmp) throws IOException {
        // --resources reads a full LinuxResources JSON. Flags then OVERRIDE
        // matching fields. Both runc and youki document this precedence.
        new KontainerConfig("/sys/fs/cgroup/x").save(tmp.toString(), "ctr");
        Path res = tmp.resolve("resources.json");
        Files.writeString(res, "{\"memory\":{\"limit\":100},\"pids\":{\"limit\":42}}");

        UpdateCommand c = newCmd(tmp.toString(), "ctr");
        c.resourcesPath = res.toString();
        c.memory = 999L; // override file's 100

        try (MockedStatic<Cgroup> cm = mockStatic(Cgroup.class)) {
            assertEquals(0, c.call());
            ArgumentCaptor<Spec.LinuxResources> arg =
                    ArgumentCaptor.forClass(Spec.LinuxResources.class);
            cm.verify(() -> Cgroup.applyLimitsOnly(anyString(), arg.capture()));
            Spec.LinuxResources r = arg.getValue();
            // flag wins
            assertEquals(999L, r.memory.limit);
            // file-only value kept
            assertEquals(42L,  r.pids.limit);
        }
    }

    @Test
    void invalidResourcesFileReturnsErrorAndDoesNotApply(@TempDir Path tmp) throws IOException {
        new KontainerConfig("/sys/fs/cgroup/x").save(tmp.toString(), "ctr");
        UpdateCommand c = newCmd(tmp.toString(), "ctr");
        c.resourcesPath = tmp.resolve("does-not-exist.json").toString();

        try (MockedStatic<Cgroup> cm = mockStatic(Cgroup.class)) {
            assertEquals(1, c.call());
            cm.verify(() -> Cgroup.applyLimitsOnly(anyString(),
                    any(Spec.LinuxResources.class)), never());
        }
    }
}
