package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.config.KontainerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FreezeTest {

    @Test
    void returns1WhenNoConfigOnDisk(@TempDir Path tmp) {
        // pause/resume against a container we never saw must return non-zero
        // (so the user knows it failed) but NOT throw.
        assertEquals(1, Freeze.write(tmp.toString(), "never-existed", "1", "pause"));
    }

    @Test
    void returns1WhenConfigHasNoCgroupPath(@TempDir Path tmp) throws IOException {
        // Container created without resources.cgroupsPath has cgroupPath=null.
        // Freezing is meaningless without a cgroup, so we surface a clear error
        // rather than silently no-op.
        new KontainerConfig(null).save(tmp.toString(), "no-cgroup");
        assertEquals(1, Freeze.write(tmp.toString(), "no-cgroup", "1", "pause"));
    }

    @Test
    void returns1WhenCgroupFreezeFileDoesNotExist(@TempDir Path tmp) throws IOException {
        // The cgroupPath is recorded but the cgroup itself was already removed
        // (container died after we read config). Must return 1 with no crash.
        new KontainerConfig("/this/cgroup/will/never/exist/anywhere")
                .save(tmp.toString(), "stale");
        assertEquals(1, Freeze.write(tmp.toString(), "stale", "1", "pause"));
    }
}
