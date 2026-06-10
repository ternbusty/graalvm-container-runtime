package com.ternbusty.takoyaki.rootfs;

import com.ternbusty.takoyaki.syscall.Constants;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MountOptionsTest {

    @Test
    void nullOptionsReturnZerosAndNullData() {
        // A mount entry without options must not blow up the parser. All buckets
        // come back zeroed.
        MountOptions.Parsed p = MountOptions.parse(null);
        assertEquals(0L, p.flags);
        assertEquals(0L, p.propagation);
        assertNull(p.data);
        assertFalse(p.isBind);
    }

    @Test
    void emptyOptionsReturnZerosAndNullData() {
        MountOptions.Parsed p = MountOptions.parse(List.of());
        assertEquals(0L, p.flags);
        assertEquals(0L, p.propagation);
        assertNull(p.data);
        assertFalse(p.isBind);
    }

    @Test
    void bindFlagsIsBindAndSetsMsBind() {
        MountOptions.Parsed p = MountOptions.parse(List.of("bind"));
        assertEquals(Constants.MS_BIND, p.flags);
        assertTrue(p.isBind, "isBind must be true so the caller knows to skip type");
    }

    @Test
    void rbindIsBindAndImpliesRec() {
        // rbind = recursive bind, both MS_BIND and MS_REC must be set.
        MountOptions.Parsed p = MountOptions.parse(List.of("rbind"));
        assertEquals(Constants.MS_BIND | Constants.MS_REC, p.flags);
        assertTrue(p.isBind);
    }

    @Test
    void accessFlagsAllCombineWithOr() {
        // ro+nosuid+nodev+noexec is the standard lock-down recipe for a bind.
        MountOptions.Parsed p = MountOptions.parse(
                List.of("ro", "nosuid", "nodev", "noexec"));
        long expected = Constants.MS_RDONLY | Constants.MS_NOSUID
                | Constants.MS_NODEV | Constants.MS_NOEXEC;
        assertEquals(expected, p.flags);
        assertEquals(0L, p.propagation);
        assertNull(p.data);
        assertFalse(p.isBind);
    }

    @Test
    void atimeVariantsMapToCorrectBits() {
        assertEquals(Constants.MS_NOATIME,
                MountOptions.parse(List.of("noatime")).flags);
        assertEquals(Constants.MS_RELATIME,
                MountOptions.parse(List.of("relatime")).flags);
        assertEquals(Constants.MS_STRICTATIME,
                MountOptions.parse(List.of("strictatime")).flags);
    }

    @Test
    void nosymfollowAndRecMapToTheirOwnBits() {
        assertEquals(Constants.MS_NOSYMFOLLOW,
                MountOptions.parse(List.of("nosymfollow")).flags);
        assertEquals(Constants.MS_REC,
                MountOptions.parse(List.of("rec")).flags);
    }

    @Test
    void propagationSharedSlavePrivateUnbindable() {
        // Propagation goes into its OWN bucket. Never mixed with flags. Because
        // the kernel rejects propagation combined with regular mount flags.
        assertEquals(Constants.MS_SHARED,
                MountOptions.parse(List.of("shared")).propagation);
        assertEquals(Constants.MS_SLAVE,
                MountOptions.parse(List.of("slave")).propagation);
        assertEquals(Constants.MS_PRIVATE,
                MountOptions.parse(List.of("private")).propagation);
        assertEquals(Constants.MS_UNBINDABLE,
                MountOptions.parse(List.of("unbindable")).propagation);
    }

    @Test
    void recursivePropagationAddsMsRec() {
        assertEquals(Constants.MS_SHARED | Constants.MS_REC,
                MountOptions.parse(List.of("rshared")).propagation);
        assertEquals(Constants.MS_SLAVE | Constants.MS_REC,
                MountOptions.parse(List.of("rslave")).propagation);
        assertEquals(Constants.MS_PRIVATE | Constants.MS_REC,
                MountOptions.parse(List.of("rprivate")).propagation);
        assertEquals(Constants.MS_UNBINDABLE | Constants.MS_REC,
                MountOptions.parse(List.of("runbindable")).propagation);
    }

    @Test
    void propagationFlagsDoNotLeakIntoFlags() {
        // Critical contract. The caller fires propagation via a SECOND mount(2)
        // call, so it must NOT appear in the flags bucket.
        MountOptions.Parsed p = MountOptions.parse(List.of("rshared"));
        assertEquals(0L, p.flags);
        assertEquals(Constants.MS_SHARED | Constants.MS_REC, p.propagation);
    }

    @Test
    void lastPropagationWins() {
        // Two propagation tokens in one option list is malformed but well-defined.
        // The parser keeps the latest, matching how Rootfs.applyOciMounts used to
        // behave with its inline switch.
        MountOptions.Parsed p = MountOptions.parse(List.of("shared", "private"));
        assertEquals(Constants.MS_PRIVATE, p.propagation);
    }

    @Test
    void unknownTokensFallThroughToDataString() {
        // Anything we don't recognize is fs-specific data ("mode=755", "size=64k").
        MountOptions.Parsed p = MountOptions.parse(
                List.of("mode=755", "size=64k", "uid=1000"));
        assertEquals(0L, p.flags);
        assertEquals(0L, p.propagation);
        assertEquals("mode=755,size=64k,uid=1000", p.data);
    }

    @Test
    void mixedKnownAndUnknownOnlyDataInDataString() {
        // Known tokens go to flags, unknown tokens get joined into data.
        MountOptions.Parsed p = MountOptions.parse(
                List.of("nosuid", "mode=755", "noexec", "size=64k"));
        assertEquals(Constants.MS_NOSUID | Constants.MS_NOEXEC, p.flags);
        assertEquals("mode=755,size=64k", p.data);
    }

    @Test
    void singleUnknownTokenHasNoCommaPrefix() {
        // Regression. An early bug joined with ",foo" when the list was empty.
        MountOptions.Parsed p = MountOptions.parse(List.of("mode=755"));
        assertEquals("mode=755", p.data);
    }

    @Test
    void typicalTmpfsRecipeProducesExpectedShape() {
        // Tmpfs mounts in real bundles look like ["nosuid","strictatime","mode=755","size=65536k"].
        MountOptions.Parsed p = MountOptions.parse(
                List.of("nosuid", "strictatime", "mode=755", "size=65536k"));
        assertEquals(Constants.MS_NOSUID | Constants.MS_STRICTATIME, p.flags);
        assertEquals(0L, p.propagation);
        assertEquals("mode=755,size=65536k", p.data);
        assertFalse(p.isBind);
    }

    @Test
    void typicalReadonlyRbindRecipe() {
        // Lockdown bind. rbind + ro + nosuid + nodev. isBind must be true.
        MountOptions.Parsed p = MountOptions.parse(
                List.of("rbind", "ro", "nosuid", "nodev"));
        assertEquals(Constants.MS_BIND | Constants.MS_REC
                | Constants.MS_RDONLY | Constants.MS_NOSUID | Constants.MS_NODEV,
                p.flags);
        assertTrue(p.isBind);
        assertNull(p.data);
    }
}
