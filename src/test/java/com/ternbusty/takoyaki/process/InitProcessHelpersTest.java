package com.ternbusty.takoyaki.process;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure helpers extracted from InitProcess.run.
 *
 * The {@code _TAKOYAKI_IDMAP_FDS} env value is consumed by Stage-2 (PID 1
 * inside the container's pid namespace) to look up host-prepared user-ns
 * fds for idmap-mount(). A bug here can silently fall back to in-init helper
 * forking, which deadlocks because /proc shows host pids inside the pid ns.
 */
class InitProcessHelpersTest {

    private static String enc(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes());
    }

    @Test
    void nullEnvProducesEmptyMap() {
        // Most containers have no idmap mounts; null env must be a clean no-op.
        assertTrue(InitProcess.parseIdmapFds(null).isEmpty());
    }

    @Test
    void emptyEnvProducesEmptyMap() {
        assertTrue(InitProcess.parseIdmapFds("").isEmpty());
    }

    @Test
    void singleEntryRoundTripsBase64AndFd() {
        String env = enc("/data") + ":7";
        Map<String, Integer> got = InitProcess.parseIdmapFds(env);
        assertEquals(1, got.size());
        assertEquals(7, got.get("/data"));
    }

    @Test
    void multipleEntriesAreCommaSeparated() {
        // Three entries, each base64(dest):fd, joined with comma.
        String env = enc("/a") + ":3," + enc("/b") + ":5," + enc("/c") + ":11";
        Map<String, Integer> got = InitProcess.parseIdmapFds(env);
        assertEquals(3, got.size());
        assertEquals(3,  got.get("/a"));
        assertEquals(5,  got.get("/b"));
        assertEquals(11, got.get("/c"));
    }

    @Test
    void destPathWithCommaIsHandledByBase64() {
        // The whole point of base64-encoding the destination is that paths
        // containing '=' or ',' don't break the outer split. Make sure that
        // actually works.
        String weirdPath = "/mnt/dir,with,commas";
        String env = enc(weirdPath) + ":99";
        Map<String, Integer> got = InitProcess.parseIdmapFds(env);
        assertEquals(99, got.get(weirdPath));
    }

    @Test
    void entryWithoutColonIsSkipped() {
        // A malformed entry must NOT abort the whole parse — we still want the
        // other entries through. Better to lose one idmap mount than fail init.
        String env = "this-has-no-colon," + enc("/good") + ":42";
        Map<String, Integer> got = InitProcess.parseIdmapFds(env);
        assertEquals(1, got.size());
        assertEquals(42, got.get("/good"));
    }

    @Test
    void entryWithBadBase64IsSkippedAndOthersStillParse() {
        // base64 decoder throws IllegalArgumentException on garbage; we swallow.
        String env = "!!!not-base64!!!:7," + enc("/good") + ":3";
        Map<String, Integer> got = InitProcess.parseIdmapFds(env);
        assertEquals(1, got.size());
        assertEquals(3, got.get("/good"));
    }

    @Test
    void entryWithNonNumericFdIsSkipped() {
        // fd must parse as int; otherwise we'd later pass garbage to a syscall.
        String env = enc("/good") + ":NaN," + enc("/keep") + ":4";
        Map<String, Integer> got = InitProcess.parseIdmapFds(env);
        assertEquals(1, got.size());
        assertEquals(4, got.get("/keep"));
    }

    @Test
    void duplicateDestinationLastEntryWins() {
        // Last-writer-wins is the simple, predictable rule. Documenting it.
        String env = enc("/same") + ":1," + enc("/same") + ":2";
        Map<String, Integer> got = InitProcess.parseIdmapFds(env);
        assertEquals(2, got.get("/same"));
    }

    @Test
    void insertionOrderPreserved() {
        // Stage-2 doesn't rely on order today, but a LinkedHashMap keeps the
        // door open for ordered application (e.g. parent-before-child mounts).
        String env = enc("/c") + ":1," + enc("/a") + ":2," + enc("/b") + ":3";
        Map<String, Integer> got = InitProcess.parseIdmapFds(env);
        assertEquals(java.util.List.of("/c", "/a", "/b"),
                java.util.List.copyOf(got.keySet()));
    }
}
