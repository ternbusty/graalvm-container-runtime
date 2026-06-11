package com.ternbusty.takoyaki.rootfs;

import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.syscall.Fs;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class UserDbTest {

    private static Spec.User user(int uid, int gid) {
        Spec.User u = new Spec.User();
        u.uid = uid;
        u.gid = gid;
        return u;
    }

    @Test
    void nullUserIsNoOp() {
        // Spec without process.user defaults to nothing. We must not touch the
        // image rootfs in that case.
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            UserDb.ensure(null, null);
            fm.verifyNoInteractions();
        }
    }

    @Test
    void missingEtcSkipsSilently() {
        // Scratch images have no /etc at all. We must not blow up.
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            fm.when(() -> Fs.exists(anyString())).thenReturn(false);

            UserDb.ensure(user(1000, 1000), null);

            // Read/write must NEVER happen when the files don't exist.
            fm.verify(() -> Fs.readString(anyString()), never());
            fm.verify(() -> Fs.writeString(anyString(), anyString()), never());
        }
    }

    @Test
    void existingUidEntryIsSkippedNotDuplicated() {
        // If /etc/passwd already lists the uid (e.g. busybox's "root:x:0:0"),
        // we MUST NOT append a second line. Idempotency matters because hooks
        // can re-trigger ensure().
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            fm.when(() -> Fs.exists(anyString())).thenReturn(true);
            fm.when(() -> Fs.readString(eq("/etc/passwd")))
                    .thenReturn("root:x:0:0:root:/root:/bin/sh\n");
            fm.when(() -> Fs.readString(eq("/etc/group")))
                    .thenReturn("root:x:0:\n");

            UserDb.ensure(user(0, 0), null);

            fm.verify(() -> Fs.writeString(anyString(), anyString()), never());
        }
    }

    @Test
    void missingUidEntryIsAppendedToPasswd() {
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            fm.when(() -> Fs.exists(anyString())).thenReturn(true);
            fm.when(() -> Fs.readString(eq("/etc/passwd")))
                    .thenReturn("root:x:0:0:root:/root:/bin/sh\n");
            fm.when(() -> Fs.readString(eq("/etc/group")))
                    .thenReturn("root:x:0:\n");

            UserDb.ensure(user(1000, 1000), null);

            // The entry shape is hard-coded to "container:x:<uid>:<gid>:container user:/:/sbin/nologin\n".
            // The new write is the WHOLE file (existing + new entry) because Fs has no append primitive.
            fm.verify(() -> Fs.writeString(
                    eq("/etc/passwd"),
                    eq("root:x:0:0:root:/root:/bin/sh\n"
                            + "container:x:1000:1000:container user:/:/sbin/nologin\n")));
        }
    }

    @Test
    void missingGidEntryIsAppendedToGroup() {
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            fm.when(() -> Fs.exists(anyString())).thenReturn(true);
            // /etc/passwd already has the uid -> no passwd write
            fm.when(() -> Fs.readString(eq("/etc/passwd")))
                    .thenReturn("container:x:1000:1000:c:/:/sbin/nologin\n");
            // /etc/group lacks gid=1000 -> append
            fm.when(() -> Fs.readString(eq("/etc/group")))
                    .thenReturn("root:x:0:\n");

            UserDb.ensure(user(1000, 1000), null);

            fm.verify(() -> Fs.writeString(
                    eq("/etc/group"),
                    eq("root:x:0:\nuser:x:1000:\n")));
            fm.verify(() -> Fs.writeString(eq("/etc/passwd"), anyString()), never());
        }
    }

    @Test
    void additionalGidsAreAppendedExceptDuplicateOfPrimaryGid() {
        // additionalGids is supplementary. If one of them is == primary gid,
        // we must NOT emit "extra<gid>" for it (else two lines for the same gid).
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            fm.when(() -> Fs.exists(anyString())).thenReturn(true);
            fm.when(() -> Fs.readString(eq("/etc/passwd")))
                    .thenReturn("container:x:1000:1000:c:/:/sbin/nologin\n");
            // Always return empty for /etc/group so every addGroup proceeds.
            fm.when(() -> Fs.readString(eq("/etc/group")))
                    .thenReturn("");

            UserDb.ensure(user(1000, 1000), List.of(1000, 100, 200));

            // primary gid=1000 appears once via the "user" entry.
            fm.verify(() -> Fs.writeString(
                    eq("/etc/group"),
                    eq("user:x:1000:\n")));
            // 1000 must NOT come back as "extra1000".
            fm.verify(() -> Fs.writeString(
                    eq("/etc/group"),
                    eq("extra1000:x:1000:\n")), never());
            // The non-duplicate supplementary gids do get appended.
            fm.verify(() -> Fs.writeString(
                    eq("/etc/group"),
                    eq("extra100:x:100:\n")));
            fm.verify(() -> Fs.writeString(
                    eq("/etc/group"),
                    eq("extra200:x:200:\n")));
        }
    }

    @Test
    void readFailureIsLoggedNotPropagated() {
        // If /etc/passwd is symlinked weirdly or sealed, Fs.readString throws.
        // The runtime must NOT die. UserDb is a best-effort convenience.
        try (MockedStatic<Fs> fm = mockStatic(Fs.class)) {
            fm.when(() -> Fs.exists(anyString())).thenReturn(true);
            fm.when(() -> Fs.readString(anyString()))
                    .thenThrow(new IOException("EACCES"));

            assertDoesNotThrow(() -> UserDb.ensure(user(1000, 1000), null));
        }
    }
}
