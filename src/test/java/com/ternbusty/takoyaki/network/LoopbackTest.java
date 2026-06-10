package com.ternbusty.takoyaki.network;

import com.ternbusty.takoyaki.syscall.RecordingSyscalls;
import com.ternbusty.takoyaki.syscall.SyscallHost;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Loopback.up() is one line of semantic intent now ("bring lo up"). The
 * ioctl dance moved into LinuxSyscalls.ifUp(). So all the tests boil down
 * to: did the caller ask for "lo", and is failure swallowed.
 *
 * The "what if SIOCGIFFLAGS fails / what if socket fails" cases are at the
 * LinuxSyscalls layer and validated by the integration suite — they're not
 * unit-testable here without re-mocking the whole ioctl ABI.
 */
class LoopbackTest {

    @Test
    void upRequestsIfUpForLo() {
        // The CORE contract: Loopback exists to bring "lo" up specifically,
        // not "eth0" or "any". A typo here breaks every container's localhost.
        RecordingSyscalls rec = new RecordingSyscalls().stubIfUpReturn(0);
        try (var s = SyscallHost.install(rec)) {
            Loopback.up();
        }

        assertEquals(List.of(new RecordingSyscalls.IfUpCall("lo")),
                rec.ifUpCalls());
    }

    @Test
    void ifUpFailureIsLoggedNotThrown() {
        // Failure (e.g. CAP_NET_ADMIN missing in rootless) must NOT bubble
        // up. The container should still come up, just without working lo.
        RecordingSyscalls rec = new RecordingSyscalls()
                .stubIfUpReturn(-1)
                .stubErrno(1 /*EPERM*/);
        try (var s = SyscallHost.install(rec)) {
            assertDoesNotThrow(Loopback::up);
        }
    }
}
