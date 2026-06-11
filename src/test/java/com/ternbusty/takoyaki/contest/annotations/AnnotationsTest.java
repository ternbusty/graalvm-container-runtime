package com.ternbusty.takoyaki.contest.annotations;

import com.ternbusty.takoyaki.contest.Contest;
import com.ternbusty.takoyaki.contest.Contest.CmdResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * spec.annotations is a free-form string -> string map containerd uses to
 * stash sandbox metadata (sandbox-id, image-name, k8s pod uid, ...). The OCI
 * spec REQUIRES the runtime to round-trip these into the state JSON so
 * the orchestrator can read them back.
 */
@Contest.RequiresTakoyaki
class AnnotationsTest {

    @Test
    void annotationsRoundTripIntoStateOutput(@TempDir Path tmp) throws Exception {
        Path rootDir = tmp.resolve("run");
        Path bundle = tmp.resolve("bundle");

        // Three realistic keys: a containerd-style sandbox id, a dotted
        // domain-prefixed key (the OCI convention), and a value with a
        // colon and slash to make sure we're not breaking the JSON escape.
        Contest.writeBundle(bundle, Map.of(
                "ociVersion", "1.0.0",
                "process", Map.of(
                        "terminal", false,
                        "args", List.of("/bin/true"),
                        "cwd", "/",
                        "user", Map.of("uid", 0, "gid", 0)
                ),
                "root", Map.of("path", "rootfs"),
                "annotations", Map.of(
                        "io.containerd.sandbox.id", "sandbox-abc123",
                        "org.opencontainers.image.ref.name", "docker.io/library/alpine:3.18",
                        "io.kubernetes.pod.uid", "pod-uid-99"
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
        CmdResult create = Contest.run(rootDir,
                "create", "--bundle", bundle.toString(), id);
        assertEquals(0, create.rc(), () -> "create failed: " + create.stderr());

        CmdResult state = Contest.run(rootDir, "state", id);
        assertEquals(0, state.rc());
        String out = state.stdout();

        // Each annotation must appear in the state output. We accept either
        // the canonical "annotations":{...} block or each key:value pair as
        // separate strings — the OCI spec doesn't pin the exact JSON shape
        // beyond "they round-trip".
        assertTrue(out.contains("sandbox-abc123"),
                () -> "annotation value 'sandbox-abc123' missing from state: " + out);
        assertTrue(out.contains("docker.io/library/alpine:3.18"),
                () -> "annotation with colon and slash missing: " + out);
        assertTrue(out.contains("pod-uid-99"),
                () -> "annotation value 'pod-uid-99' missing: " + out);

        Contest.run(rootDir, "delete", "--force", id);
    }
}
