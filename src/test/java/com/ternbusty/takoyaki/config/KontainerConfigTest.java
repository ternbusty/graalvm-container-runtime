package com.ternbusty.takoyaki.config;

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
        Path p = KontainerConfig.path("/run/takoyaki", "abc");
        assertEquals(Path.of("/run/takoyaki/abc/config.json"), p);
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
        // Crucially this must NOT require the caller to mkdir first — Create
        // saves config.json as part of the same call sequence.
        KontainerConfig c = new KontainerConfig("/sys/fs/cgroup/x");
        c.save(tmp.toString(), "freshly-created");
        assertTrue(java.nio.file.Files.exists(
                tmp.resolve("freshly-created").resolve("config.json")));
    }

    @Test
    void loadMissingFileThrowsIoException(@TempDir Path tmp) {
        // Subcommands like pause distinguish "no config" from "wrong cgroup"
        // via the IOException. Don't let it be silently swallowed.
        assertThrows(IOException.class,
                () -> KontainerConfig.load(tmp.toString(), "never-existed"));
    }

    @Test
    void nullCgroupPathIsAllowedAndRoundTrips(@TempDir Path tmp) throws IOException {
        // Containers without resources.cgroupsPath leave this null. We must
        // serialize it as null rather than blowing up on Json.encode.
        KontainerConfig c = new KontainerConfig(null);
        c.save(tmp.toString(), "no-cgroup");
        KontainerConfig loaded = KontainerConfig.load(tmp.toString(), "no-cgroup");
        assertNull(loaded.cgroupPath);
    }
}
