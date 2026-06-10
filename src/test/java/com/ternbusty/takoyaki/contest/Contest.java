package com.ternbusty.takoyaki.contest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Test-side companion to the production runtime — youki calls this layer
 * {@code contest}. Each contest test drives the real {@code takoyaki} binary
 * end-to-end against a bundle laid down under a {@link org.junit.jupiter.api.io.TempDir}.
 *
 * Two prerequisites must hold for a contest test to run:
 *   1. {@code TAKOYAKI_BIN} environment variable points at the native binary.
 *   2. The host is Linux (clone3, unshare, cgroup v2).
 *
 * Annotate test classes with {@link RequiresTakoyaki @RequiresTakoyaki} to
 * have JUnit skip them otherwise (clean local-dev experience on macOS).
 */
public final class Contest {
    private Contest() {}

    /** Marker; combined with the {@link Condition} extension to gate execution. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @ExtendWith(Condition.class)
    public @interface RequiresTakoyaki {}

    /** Skip when prerequisites fail. */
    public static final class Condition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext ctx) {
            String bin = System.getenv("TAKOYAKI_BIN");
            if (bin == null || bin.isBlank()) {
                return ConditionEvaluationResult.disabled(
                        "TAKOYAKI_BIN not set — skipping contest test");
            }
            String os = System.getProperty("os.name", "").toLowerCase();
            if (!os.contains("linux")) {
                return ConditionEvaluationResult.disabled(
                        "contest tests require Linux, got: " + os);
            }
            if (!java.nio.file.Files.isExecutable(java.nio.file.Path.of(bin))) {
                return ConditionEvaluationResult.disabled(
                        "TAKOYAKI_BIN is set but not executable: " + bin);
            }
            // Most contest specs unshare PID/MNT/IPC/UTS/NET — kernel requires
            // CAP_SYS_ADMIN unless a user namespace with mappings is in the
            // spec. Simpler to require effective uid 0. CI sudoes around it.
            if (!isRoot()) {
                return ConditionEvaluationResult.disabled(
                        "contest tests require root (or sudo); skipping");
            }
            return ConditionEvaluationResult.enabled("takoyaki binary available, running as root");
        }

        private static boolean isRoot() {
            try {
                Process p = new ProcessBuilder("id", "-u")
                        .redirectErrorStream(true).start();
                p.waitFor();
                String out = new String(p.getInputStream().readAllBytes()).trim();
                return "0".equals(out);
            } catch (Exception e) {
                return false;
            }
        }
    }

    // ---- bundle layout ------------------------------------------------------

    /** Path to the takoyaki binary under test, from TAKOYAKI_BIN. */
    public static String bin() {
        return System.getenv("TAKOYAKI_BIN");
    }

    /** Generate a fresh container id for one test. */
    public static String newContainerId() {
        return "contest-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Lay down a minimal OCI bundle at {@code bundle/}: empty rootfs dir +
     * config.json built from the supplied builder. The caller usually decides
     * to run {@code /bin/true} or similar; this helper just handles the boring
     * directory layout and JSON serialization.
     */
    public static Path writeBundle(Path bundle, Map<String, Object> spec) throws IOException {
        Files.createDirectories(bundle);
        Files.createDirectories(bundle.resolve("rootfs"));
        Path config = bundle.resolve("config.json");
        Files.writeString(config, new ObjectMapper().writeValueAsString(spec));
        return bundle;
    }

    // ---- subprocess invocation ---------------------------------------------

    public record CmdResult(int rc, String stdout, String stderr) {
        public boolean ok() { return rc == 0; }
    }

    /** Run takoyaki with the given args + the standard --root path; returns rc, stdout, stderr. */
    public static CmdResult run(Path rootDir, String... args) throws IOException, InterruptedException {
        List<String> argv = new ArrayList<>();
        argv.add(bin());
        argv.add("--root");
        argv.add(rootDir.toString());
        for (String a : args) argv.add(a);

        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.redirectErrorStream(false);
        Process p = pb.start();
        // 30 s is generous — most contest steps finish in well under a second.
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new RuntimeException("takoyaki " + String.join(" ", args)
                    + " timed out after 30s");
        }
        String out = new String(p.getInputStream().readAllBytes());
        String err = new String(p.getErrorStream().readAllBytes());
        return new CmdResult(p.exitValue(), out, err);
    }
}
