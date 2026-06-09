package com.ternbusty.takoyaki.ipc;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.syscall.Libc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Pass open file descriptors over a unix domain socket using SCM_RIGHTS.
 *
 * Used for two things in takoyaki:
 * 1. Console socket: ship a pty master fd back to whoever invoked the runtime.
 * 2. Seccomp notify: forward the notify fd to the listener path.
 *
 * Layout (glibc x86_64/aarch64) for the structures involved:
 *   struct msghdr     56 bytes (name=8 namelen=4 pad=4 iov=8 iovlen=8 control=8 controllen=8 flags=4 pad=4)
 *   struct iovec      16 bytes (base=8 len=8)
 *   cmsghdr header    16 bytes (len=8 level=4 type=4); the fd payload follows.
 */
public final class ScmRights {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final MethodHandle SENDMSG;
    private static final MethodHandle RECVMSG;
    static {
        SENDMSG = LINKER.downcallHandle(LINKER.defaultLookup().find("sendmsg").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        RECVMSG = LINKER.downcallHandle(LINKER.defaultLookup().find("recvmsg").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    }

    private static final int SOL_SOCKET = 1;
    private static final int SCM_RIGHTS = 1;

    private ScmRights() {}

    /** Send one byte (and {@code fd} via SCM_RIGHTS) to the connected socket. */
    public static boolean sendFd(int sockFd, int fd, byte tag) {
        try (Arena arena = Arena.ofConfined()) {
            // iovec with a single byte payload
            MemorySegment iovBuf = arena.allocate(1);
            iovBuf.set(ValueLayout.JAVA_BYTE, 0, tag);
            MemorySegment iov = arena.allocate(16);
            iov.set(ValueLayout.ADDRESS, 0, iovBuf);
            iov.set(ValueLayout.JAVA_LONG, 8, 1L);

            // cmsg buffer: 16 byte header + 4 byte fd (8 byte aligned by data section)
            int cmsgLen = 16 + 4;
            int cmsgSpace = 16 + 8;
            MemorySegment cmsg = arena.allocate(cmsgSpace);
            cmsg.set(ValueLayout.JAVA_LONG, 0, (long) cmsgLen); // cmsg_len
            cmsg.set(ValueLayout.JAVA_INT, 8, SOL_SOCKET); // cmsg_level
            cmsg.set(ValueLayout.JAVA_INT, 12, SCM_RIGHTS); // cmsg_type
            cmsg.set(ValueLayout.JAVA_INT, 16, fd); // payload

            MemorySegment msg = arena.allocate(56);
            // msg_name=NULL, msg_namelen=0
            msg.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
            msg.set(ValueLayout.JAVA_INT, 8, 0);
            // msg_iov, msg_iovlen
            msg.set(ValueLayout.ADDRESS, 16, iov);
            msg.set(ValueLayout.JAVA_LONG, 24, 1L);
            // msg_control, msg_controllen
            msg.set(ValueLayout.ADDRESS, 32, cmsg);
            msg.set(ValueLayout.JAVA_LONG, 40, (long) cmsgSpace);
            // msg_flags
            msg.set(ValueLayout.JAVA_INT, 48, 0);

            long rc = (long) SENDMSG.invoke(sockFd, msg, 0);
            if (rc < 0) {
                Logger.warn("sendmsg failed: " + Libc.strerror(Libc.errno()));
                return false;
            }
            return true;
        } catch (Throwable t) {
            Logger.warn("scmrights sendFd error: " + t.getMessage());
            return false;
        }
    }

    /** Receive a single fd via SCM_RIGHTS. Returns -1 on failure. */
    public static int recvFd(int sockFd) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment iovBuf = arena.allocate(1);
            MemorySegment iov = arena.allocate(16);
            iov.set(ValueLayout.ADDRESS, 0, iovBuf);
            iov.set(ValueLayout.JAVA_LONG, 8, 1L);

            int cmsgSpace = 16 + 8;
            MemorySegment cmsg = arena.allocate(cmsgSpace);

            MemorySegment msg = arena.allocate(56);
            msg.set(ValueLayout.ADDRESS, 0, MemorySegment.NULL);
            msg.set(ValueLayout.JAVA_INT, 8, 0);
            msg.set(ValueLayout.ADDRESS, 16, iov);
            msg.set(ValueLayout.JAVA_LONG, 24, 1L);
            msg.set(ValueLayout.ADDRESS, 32, cmsg);
            msg.set(ValueLayout.JAVA_LONG, 40, (long) cmsgSpace);
            msg.set(ValueLayout.JAVA_INT, 48, 0);

            long rc = (long) RECVMSG.invoke(sockFd, msg, 0);
            if (rc < 0) {
                Logger.warn("recvmsg failed: " + Libc.strerror(Libc.errno()));
                return -1;
            }
            int level = cmsg.get(ValueLayout.JAVA_INT, 8);
            int type = cmsg.get(ValueLayout.JAVA_INT, 12);
            if (level != SOL_SOCKET || type != SCM_RIGHTS) {
                Logger.warn("recvmsg returned unexpected cmsg level=" + level + " type=" + type);
                return -1;
            }
            return cmsg.get(ValueLayout.JAVA_INT, 16);
        } catch (Throwable t) {
            Logger.warn("scmrights recvFd error: " + t.getMessage());
            return -1;
        }
    }
}
