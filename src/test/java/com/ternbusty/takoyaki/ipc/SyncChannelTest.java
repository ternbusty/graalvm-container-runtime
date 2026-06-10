package com.ternbusty.takoyaki.ipc;

import com.ternbusty.takoyaki.syscall.PosixIO;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.foreign.Arena;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SyncChannel is the framing layer between Stage-1 and Stage-2 of the runtime.
 * The wire format is a little-endian 4-byte integer per message. If endianness
 * or framing slips, every Create-time handshake (USERMAP_PLS/ACK, INIT_READY)
 * goes wrong and the container hangs forever.
 */
class SyncChannelTest {

    @Test
    void messageSentinelsHaveStableValues() {
        // Pinning these values because they're a wire contract — Stage-1 and
        // Stage-2 are different processes, so the numbers MUST match across
        // runs and across builds.
        assertEquals(0x50, SyncChannel.MSG_INIT_READY);
        assertEquals(0x40, SyncChannel.MSG_USERMAP_PLS);
        assertEquals(0x41, SyncChannel.MSG_USERMAP_ACK);
    }

    @Test
    void writeInt32EmitsLittleEndianBytes() {
        // 0x12345678 -> bytes 78 56 34 12 (LE), matching how InitProcess.c
        // reads them on the other end.
        try (MockedStatic<PosixIO> pm = mockStatic(PosixIO.class)) {
            pm.when(() -> PosixIO.write(any(Arena.class), anyInt(), any(byte[].class)))
                    .thenReturn(4L);

            SyncChannel.writeInt32(7, 0x12345678);

            // Match a byte[] whose contents are exactly 78 56 34 12.
            pm.verify(() -> PosixIO.write(any(Arena.class), eq(7),
                    argThat((byte[] b) -> b != null && b.length == 4
                            && (b[0] & 0xff) == 0x78
                            && (b[1] & 0xff) == 0x56
                            && (b[2] & 0xff) == 0x34
                            && (b[3] & 0xff) == 0x12)));
        }
    }

    @Test
    void writeInt32ForKnownSentinelsEmitsExpectedBytes() {
        // MSG_INIT_READY = 0x50 -> bytes 50 00 00 00 (LE).
        try (MockedStatic<PosixIO> pm = mockStatic(PosixIO.class)) {
            pm.when(() -> PosixIO.write(any(Arena.class), anyInt(), any(byte[].class)))
                    .thenReturn(4L);

            SyncChannel.writeInt32(3, SyncChannel.MSG_INIT_READY);

            pm.verify(() -> PosixIO.write(any(Arena.class), eq(3),
                    argThat((byte[] b) -> b != null && b.length == 4
                            && (b[0] & 0xff) == 0x50
                            && b[1] == 0 && b[2] == 0 && b[3] == 0)));
        }
    }

    @Test
    void writeInt32ShortWriteThrows() {
        // A short write (n != 4) MUST throw — Stage-2 would otherwise block
        // reading 4 bytes that will never arrive, deadlocking init.
        try (MockedStatic<PosixIO> pm = mockStatic(PosixIO.class)) {
            pm.when(() -> PosixIO.write(any(Arena.class), anyInt(), any(byte[].class)))
                    .thenReturn(2L);

            assertThrows(RuntimeException.class,
                    () -> SyncChannel.writeInt32(3, 0));
        }
    }

    @Test
    void readInt32DecodesLittleEndianBytes() {
        // Inverse of writeInt32: bytes 78 56 34 12 on the wire -> 0x12345678.
        try (MockedStatic<PosixIO> pm = mockStatic(PosixIO.class)) {
            pm.when(() -> PosixIO.read(any(Arena.class), anyInt(), any(byte[].class)))
                    .thenAnswer(inv -> {
                        byte[] buf = inv.getArgument(2);
                        buf[0] = 0x78;
                        buf[1] = 0x56;
                        buf[2] = 0x34;
                        buf[3] = 0x12;
                        return 4L;
                    });

            assertEquals(0x12345678, SyncChannel.readInt32(5));
        }
    }

    @Test
    void readInt32HandlesAllZeroes() {
        // The wire-zero case must round-trip. (Used when sender wants to signal
        // a benign sentinel like "nothing".)
        try (MockedStatic<PosixIO> pm = mockStatic(PosixIO.class)) {
            pm.when(() -> PosixIO.read(any(Arena.class), anyInt(), any(byte[].class)))
                    .thenReturn(4L);
            assertEquals(0, SyncChannel.readInt32(5));
        }
    }

    @Test
    void readInt32HandlesAllOnesAsExpectedSignedValue() {
        // 0xFF 0xFF 0xFF 0xFF must decode to -1 (the high bit propagates).
        // This is what kernel error returns look like when surfaced via the
        // channel.
        try (MockedStatic<PosixIO> pm = mockStatic(PosixIO.class)) {
            pm.when(() -> PosixIO.read(any(Arena.class), anyInt(), any(byte[].class)))
                    .thenAnswer(inv -> {
                        byte[] buf = inv.getArgument(2);
                        buf[0] = (byte) 0xff;
                        buf[1] = (byte) 0xff;
                        buf[2] = (byte) 0xff;
                        buf[3] = (byte) 0xff;
                        return 4L;
                    });
            assertEquals(-1, SyncChannel.readInt32(5));
        }
    }

    @Test
    void readInt32ShortReadThrows() {
        // A short read (peer closed the fd mid-frame) MUST surface as an
        // exception so the caller doesn't proceed with a half-read sentinel.
        try (MockedStatic<PosixIO> pm = mockStatic(PosixIO.class)) {
            pm.when(() -> PosixIO.read(any(Arena.class), anyInt(), any(byte[].class)))
                    .thenReturn(2L);

            assertThrows(RuntimeException.class,
                    () -> SyncChannel.readInt32(5));
        }
    }

    @Test
    void writeThenReadRoundTrips() {
        // End-to-end: capture what writeInt32 hands to PosixIO, replay it
        // through readInt32. This is the "Stage-1 sends, Stage-2 receives"
        // contract in miniature.
        byte[][] wireBuf = new byte[1][];

        try (MockedStatic<PosixIO> pm = mockStatic(PosixIO.class)) {
            pm.when(() -> PosixIO.write(any(Arena.class), anyInt(), any(byte[].class)))
                    .thenAnswer(inv -> {
                        byte[] src = inv.getArgument(2);
                        wireBuf[0] = src.clone();
                        return (long) src.length;
                    });
            pm.when(() -> PosixIO.read(any(Arena.class), anyInt(), any(byte[].class)))
                    .thenAnswer(inv -> {
                        byte[] dst = inv.getArgument(2);
                        System.arraycopy(wireBuf[0], 0, dst, 0, dst.length);
                        return (long) dst.length;
                    });

            int[] samples = {0, 1, 0x40, 0x41, 0x50, 0x12345678, -1, Integer.MAX_VALUE};
            for (int v : samples) {
                SyncChannel.writeInt32(3, v);
                assertEquals(v, SyncChannel.readInt32(3),
                        () -> "round-trip failed for value 0x" + Integer.toHexString(v));
            }
        }
    }
}
