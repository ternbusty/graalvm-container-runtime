package com.ternbusty.takoyaki.spec;

import com.ternbusty.takoyaki.util.Json;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SpecTest {

    @Test
    void decodeMinimalSpec() {
        // Smallest config.json we accept: ociVersion + root + process.
        String json = """
                {
                  "ociVersion": "1.0.0",
                  "root": { "path": "rootfs" },
                  "process": {
                    "args": ["sh", "-c", "echo hi"]
                  }
                }
                """;
        Spec spec = Json.decode(json, Spec::fromJson);
        assertEquals("1.0.0", spec.ociVersion);
        assertEquals("rootfs", spec.root.path);
        assertEquals(3, spec.process.args.size());
        assertEquals("sh", spec.process.args.get(0));
    }

    @Test
    void unknownFieldsAreIgnored() {
        // The OCI spec mandates that runtimes MUST NOT fail on unknown
        // properties — we rely on Jackson's FAIL_ON_UNKNOWN_PROPERTIES=false.
        String json = """
                {
                  "ociVersion": "1.2.0",
                  "root": { "path": "rootfs" },
                  "process": { "args": ["true"] },
                  "thisFieldDoesNotExist": { "nested": 42 },
                  "unknown": "garbage"
                }
                """;
        Spec spec = Json.decode(json, Spec::fromJson);
        assertEquals("1.2.0", spec.ociVersion);
    }

    @Test
    void hasNamespaceReturnsTrueForListedTypes() {
        Spec spec = new Spec();
        spec.linux = new Spec.Linux();
        Spec.Namespace mnt = new Spec.Namespace();
        mnt.type = "mount";
        Spec.Namespace pid = new Spec.Namespace();
        pid.type = "pid";
        spec.linux.namespaces = java.util.List.of(mnt, pid);

        assertTrue(spec.hasNamespace("mount"));
        assertTrue(spec.hasNamespace("pid"));
        assertFalse(spec.hasNamespace("user"),
                "user is not in the spec so hasNamespace must return false");
        assertFalse(spec.hasNamespace("network"));
    }

    @Test
    void hasNamespaceWithNullLinuxIsSafe() {
        Spec spec = new Spec();
        spec.linux = null;
        assertFalse(spec.hasNamespace("mount"));
    }

    @Test
    void capabilitiesFieldRoundTrips() {
        String json = """
                {
                  "ociVersion": "1.0.0",
                  "root": { "path": "rootfs" },
                  "process": {
                    "args": ["true"],
                    "capabilities": {
                      "bounding": ["CAP_DAC_OVERRIDE", "CAP_KILL"],
                      "effective": ["CAP_DAC_OVERRIDE"]
                    }
                  }
                }
                """;
        Spec spec = Json.decode(json, Spec::fromJson);
        assertNotNull(spec.process.capabilities);
        assertEquals(2, spec.process.capabilities.bounding.size());
        assertTrue(spec.process.capabilities.bounding.contains("CAP_KILL"));
        assertEquals(1, spec.process.capabilities.effective.size());
    }

    @Test
    void mountUidMappingsParseForIdmapMounts() {
        String json = """
                {
                  "ociVersion": "1.0.0",
                  "root": { "path": "rootfs" },
                  "process": { "args": ["true"] },
                  "mounts": [{
                    "destination": "/idmap",
                    "source": "/tmp",
                    "type": "none",
                    "options": ["bind"],
                    "uidMappings": [{ "containerID": 0, "hostID": 100000, "size": 1 }],
                    "gidMappings": [{ "containerID": 0, "hostID": 100000, "size": 1 }]
                  }]
                }
                """;
        Spec spec = Json.decode(json, Spec::fromJson);
        assertEquals(1, spec.mounts.size());
        Spec.Mount m = spec.mounts.get(0);
        assertEquals("/idmap", m.destination);
        assertEquals(1, m.uidMappings.size());
        assertEquals(100000, m.uidMappings.get(0).hostID);
        assertEquals(0, m.uidMappings.get(0).containerID);
        assertEquals(1, m.uidMappings.get(0).size);
    }
}
