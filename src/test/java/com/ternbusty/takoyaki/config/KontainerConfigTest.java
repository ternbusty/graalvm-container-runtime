package com.ternbusty.takoyaki.config;

import com.ternbusty.takoyaki.syscall.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class KontainerConfigTest {

    @Test
    void pathIsRootSlashIdSlashConfigJson() {
        // pause/resume/freeze all hang off this exact filename. If the convention
        // drifts, every subcommand stops finding the cgroup.
        String p = KontainerConfig.path("/run/takoyaki", "abc");
        assertEquals("/run/takoyaki/abc/config.json", p);
    }

    @Test
    void saveAndLoadRoundTripsCgroupPath(@TempDir Path tmp) throws IOException {
        KontainerConfig c = new KontainerConfig("/sys/fs/cgroup/user.slice/x");
        c.save(tmp.toString(), "ctr-1");

        KontainerConfig loaded = KontainerConfig.load(tmp.toString(), "ctr-1");
        assertEquals("/sys/fs/cgroup/user.slice/x", loaded.cgroupPath);
    }

    @Test
    void saveCreatesContainerDirectoryIfMissing(@TempDir Path tmp) throws IOException {
        KontainerConfig c = new KontainerConfig("/sys/fs/cgroup/x");
        c.save(tmp.toString(), "freshly-created");
        assertTrue(Fs.exists(tmp.resolve("freshly-created").resolve("config.json").toString()));
    }

    @Test
    void loadMissingFileThrowsIoException(@TempDir Path tmp) {
        assertThrows(IOException.class,
                () -> KontainerConfig.load(tmp.toString(), "never-existed"));
    }

    @Test
    void nullCgroupPathIsAllowedAndRoundTrips(@TempDir Path tmp) throws IOException {
        KontainerConfig c = new KontainerConfig(null);
        c.save(tmp.toString(), "no-cgroup");
        KontainerConfig loaded = KontainerConfig.load(tmp.toString(), "no-cgroup");
        assertNull(loaded.cgroupPath);
    }
}
