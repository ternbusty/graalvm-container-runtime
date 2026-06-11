package com.ternbusty.takoyaki.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StateRoundTripTest {

    @Test
    void saveAndLoadPreservesAllFields(@TempDir Path tmp) throws IOException {
        // Saving then loading must round-trip every field that runtime-tools
        // and downstream hooks rely on. If a field is silently dropped at the
        // JSON layer, killsig/hooks_stdin/pidfile etc. all start misbehaving.
        State s = State.create("1.0.0", "ctr-42", ContainerStatus.CREATED,
                12345, "/tmp/bundle",
                Map.of("org.example/k", "v"));
        s.save(tmp.toString());

        State loaded = State.load(tmp.toString(), "ctr-42");

        assertEquals("1.0.0",         loaded.ociVersion);
        assertEquals("ctr-42",        loaded.id);
        assertEquals("created",       loaded.status);
        assertEquals(12345,           loaded.pid);
        assertEquals("/tmp/bundle",   loaded.bundle);
        assertEquals(Map.of("org.example/k", "v"), loaded.annotations);
        assertNotNull(loaded.created, "created timestamp must be preserved");
    }

    @Test
    void existsReportsContainerLifecycle(@TempDir Path tmp) throws IOException {
        // exists() is the cheap guard that prevents create from racing against
        // itself and delete from acting on a non-container.
        assertFalse(State.exists(tmp.toString(), "absent"),
                "exists() returns false before any save");
        State s = State.create("1.0.0", "abc", ContainerStatus.CREATED, 1, "/b", null);
        s.save(tmp.toString());
        assertTrue(State.exists(tmp.toString(), "abc"),
                "exists() returns true after save");
    }

    @Test
    void containerDirPathIsRootPathSlashId() {
        String p = State.containerDir("/run/takoyaki", "abc");
        assertEquals("/run/takoyaki/abc", p);
    }

    @Test
    void statePathIsContainerDirSlashStateJson() {
        String p = State.statePath("/run/takoyaki", "abc");
        assertEquals("/run/takoyaki/abc/state.json", p);
    }

    @Test
    void withStatusReturnsACopyWithJustTheStatusChanged() {
        State original = State.create("1.0.0", "x", ContainerStatus.CREATED,
                10, "/b", null);
        State running = original.withStatus(ContainerStatus.RUNNING);

        // Original is untouched.
        assertEquals("created", original.status);
        // Running has new status but every other field the same.
        assertEquals("running", running.status);
        assertEquals(original.id, running.id);
        assertEquals(original.pid, running.pid);
        assertEquals(original.bundle, running.bundle);
        assertEquals(original.ociVersion, running.ociVersion);
    }
}
