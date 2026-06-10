package com.ternbusty.takoyaki.seccomp;

import com.ternbusty.takoyaki.ipc.ScmRights;
import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.syscall.Constants;
import com.ternbusty.takoyaki.syscall.Libc;
import com.ternbusty.takoyaki.syscall.PosixIO;
import com.ternbusty.takoyaki.util.Json;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forward a seccomp notify fd to an external listener over a Unix socket.
 *
 * Protocol (matching runc / kontainer-runtime):
 *   1. AF_UNIX SOCK_STREAM connect() to {@code listenerPath}.
 *   2. send() container state JSON + "\n" so the listener can identify the
 *      container that this fd belongs to. Optionally followed by listenerMetadata.
 *   3. sendmsg() with SCM_RIGHTS carrying the notify fd (1 dummy byte iov).
 *   4. close().
 *
 * The Unix socket connect MUST happen on the host (in CreateCommand), because the
 * listener path is a host-side file and the container's mount namespace doesn't
 * have it after pivot_root. CreateCommand calls {@link #connectHostSide} before
 * forking the bootstrap, passes the fd via env, and the init reuses it.
 */
public final class SeccompListener {
    private SeccompListener() {}

    /** Open and connect a Unix socket to listenerPath on the host. Returns fd, or -1. */
    public static int connectHostSide(String listenerPath) {
        try (Arena arena = Arena.ofConfined()) {
            int sock = (int) Libc.syscall(NR_socket(),
                    Constants.AF_UNIX, Constants.SOCK_STREAM, 0, 0, 0);
            if (sock < 0) {
                Logger.warn("seccomp listener socket() failed: " + Libc.strerror(Libc.errno()));
                return -1;
            }
            if (!connectUnix(arena, sock, listenerPath)) {
                PosixIO.close(sock);
                return -1;
            }
            return sock;
        }
    }

    /**
     * Forward the notify fd over a pre-connected socket. If preConnectedFd is -1,
     * fall back to connecting from here (only works pre-pivot or when the listener
     * path is somehow reachable from inside the container, which it usually isn't).
     */
    public static void forward(String listenerPath,
                               String containerId,
                               String bundle,
                               int containerPid,
                               String listenerMetadata,
                               int notifyFd,
                               int preConnectedFd) {
        Logger.debug("forwarding seccomp notify fd=" + notifyFd
                + " to listener " + listenerPath
                + (preConnectedFd >= 0 ? " (via host-prepared fd " + preConnectedFd + ")" : ""));
        try (Arena arena = Arena.ofConfined()) {
            int sock;
            if (preConnectedFd >= 0) {
                sock = preConnectedFd;
            } else {
                sock = (int) Libc.syscall(NR_socket(),
                        Constants.AF_UNIX, Constants.SOCK_STREAM, 0, 0, 0);
                if (sock < 0) {
                    Logger.warn("seccomp listener socket() failed: " + Libc.strerror(Libc.errno()));
                    return;
                }
                if (!connectUnix(arena, sock, listenerPath)) {
                    PosixIO.close(sock);
                    return;
                }
            }
            try {
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("ociVersion", "1.0.0");
                state.put("id", containerId == null ? "" : containerId);
                state.put("status", "created");
                state.put("pid", containerPid);
                state.put("bundle", bundle == null ? "" : bundle);
                String json = Json.encode(state) + "\n";
                if (!writeAll(arena, sock, json.getBytes())) {
                    Logger.warn("failed to send state to seccomp listener");
                    return;
                }
                if (listenerMetadata != null && !listenerMetadata.isEmpty()) {
                    writeAll(arena, sock, listenerMetadata.getBytes());
                }
                if (!ScmRights.sendFd(sock, notifyFd, (byte) 0)) {
                    Logger.warn("failed to send seccomp notify fd via SCM_RIGHTS");
                    return;
                }
                Logger.info("seccomp notify fd forwarded to " + listenerPath);
            } finally {
                PosixIO.close(sock);
            }
        }
    }

    private static boolean connectUnix(Arena arena, int sock, String path) {
        byte[] bytes = path.getBytes();
        if (bytes.length >= 108) {
            Logger.warn("seccomp listener path too long: " + path);
            return false;
        }
        // struct sockaddr_un { sa_family_t sun_family; char sun_path[108]; }
        MemorySegment addr = arena.allocate(110);
        addr.set(ValueLayout.JAVA_SHORT, 0, (short) Constants.AF_UNIX);
        for (int i = 0; i < bytes.length; i++) {
            addr.set(ValueLayout.JAVA_BYTE, 2 + i, bytes[i]);
        }
        addr.set(ValueLayout.JAVA_BYTE, 2 + bytes.length, (byte) 0);
        int addrLen = 2 + bytes.length + 1;
        long rc = Libc.syscall(NR_connect(), sock, addr.address(), addrLen, 0, 0);
        if (rc != 0) {
            Logger.warn("seccomp listener connect(" + path + ") failed: "
                    + Libc.strerror(Libc.errno()));
            return false;
        }
        return true;
    }

    private static boolean writeAll(Arena arena, int sock, byte[] data) {
        int off = 0;
        while (off < data.length) {
            byte[] chunk;
            if (off == 0) {
                chunk = data;
            } else {
                chunk = new byte[data.length - off];
                System.arraycopy(data, off, chunk, 0, chunk.length);
            }
            long n = PosixIO.write(arena, sock, chunk);
            if (n < 0) {
                Logger.warn("seccomp listener write failed: " + Libc.strerror(Libc.errno()));
                return false;
            }
            if (n == 0) return false;
            off += (int) n;
        }
        return true;
    }

    private static long NR_socket() {
        String a = System.getProperty("os.arch", "").toLowerCase();
        return (a.contains("aarch64") || a.contains("arm64")) ? 198L : 41L;
    }

    private static long NR_connect() {
        String a = System.getProperty("os.arch", "").toLowerCase();
        return (a.contains("aarch64") || a.contains("arm64")) ? 203L : 42L;
    }
}
