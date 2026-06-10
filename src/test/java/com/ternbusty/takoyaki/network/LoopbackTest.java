package com.ternbusty.takoyaki.network;

import com.ternbusty.takoyaki.syscall.Constants;
import com.ternbusty.takoyaki.syscall.Libc;
import com.ternbusty.takoyaki.syscall.PosixIO;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LoopbackTest {

    @Test
    void socketFailureIsLoggedNotPropagated() {
        // If socket(2) fails (e.g. we're in a userns with no AF_INET allowed)
        // Loopback.up() must NOT throw. Network is best-effort.
        try (MockedStatic<PosixIO> pm = mockStatic(PosixIO.class);
             MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            pm.when(() -> PosixIO.socket(anyInt(), anyInt(), anyInt())).thenReturn(-1);
            lm.when(Libc::errno).thenReturn(1);
            lm.when(() -> Libc.strerror(anyInt())).thenReturn("Operation not permitted");

            assertDoesNotThrow(Loopback::up);

            // ioctl must NEVER be called if we failed to even open a socket.
            lm.verify(() -> Libc.ioctl(anyInt(), anyLong(), any(MemorySegment.class)), never());
        }
    }

    @Test
    void successfullySetsIffUpViaSiocsifflags() {
        // Happy path: socket opens, SIOCGIFFLAGS returns 0 (so the
        // buffer's flags field is 0), then SIOCSIFFLAGS is invoked with the
        // updated buffer. We verify the second ioctl was issued — which
        // implies the first succeeded, since the order is GET then OR-IFF_UP
        // then SET.
        try (MockedStatic<PosixIO> pm = mockStatic(PosixIO.class);
             MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            pm.when(() -> PosixIO.socket(eq(Constants.AF_INET),
                    eq(Constants.SOCK_DGRAM), eq(0))).thenReturn(3);
            lm.when(() -> Libc.ioctl(eq(3), anyLong(), any(MemorySegment.class)))
                    .thenReturn(0);

            Loopback.up();

            lm.verify(() -> Libc.ioctl(eq(3), eq(Constants.SIOCGIFFLAGS),
                    any(MemorySegment.class)));
            lm.verify(() -> Libc.ioctl(eq(3), eq(Constants.SIOCSIFFLAGS),
                    any(MemorySegment.class)));
            // And the fd must be closed regardless.
            pm.verify(() -> PosixIO.close(eq(3)));
        }
    }

    @Test
    void getFlagsFailureSkipsSetCallAndStillClosesFd() {
        // SIOCGIFFLAGS = -1 means we never knew the current flags. We must
        // NOT then call SIOCSIFFLAGS (which would clobber with whatever junk
        // is in the buffer) and we MUST still close the socket fd.
        try (MockedStatic<PosixIO> pm = mockStatic(PosixIO.class);
             MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            pm.when(() -> PosixIO.socket(anyInt(), anyInt(), anyInt())).thenReturn(7);
            lm.when(() -> Libc.ioctl(eq(7), eq(Constants.SIOCGIFFLAGS),
                    any(MemorySegment.class))).thenReturn(-1);
            lm.when(Libc::errno).thenReturn(1);
            lm.when(() -> Libc.strerror(anyInt())).thenReturn("EPERM");

            assertDoesNotThrow(Loopback::up);

            lm.verify(() -> Libc.ioctl(eq(7), eq(Constants.SIOCSIFFLAGS),
                    any(MemorySegment.class)), never());
            pm.verify(() -> PosixIO.close(eq(7)));
        }
    }
}
