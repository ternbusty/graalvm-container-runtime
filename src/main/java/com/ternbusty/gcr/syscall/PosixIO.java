package com.ternbusty.gcr.syscall;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

public final class PosixIO {
    private PosixIO() {}

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();

    private static MethodHandle h(String name, FunctionDescriptor desc) {
        return LIBC.find(name)
                .map(addr -> LINKER.downcallHandle(addr, desc))
                .orElseThrow(() -> new UnsatisfiedLinkError("libc: " + name));
    }

    private static final MethodHandle SOCKETPAIR = h("socketpair",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS));
    private static final MethodHandle SOCKET = h("socket",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle BIND = h("bind",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle LISTEN = h("listen",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle ACCEPT = h("accept",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle CONNECT = h("connect",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle READ = h("read",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final MethodHandle WRITE = h("write",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final MethodHandle SEND = h("send",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
    private static final MethodHandle RECV = h("recv",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
    private static final MethodHandle CLOSE = h("close",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle UNLINK = h("unlink",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle ACCESS = h("access",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle MKDIR = h("mkdir",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle OPEN = h("open",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle FCHDIR = h("fchdir",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle FORK = h("fork",
            FunctionDescriptor.of(ValueLayout.JAVA_INT));
    private static final MethodHandle EXIT_ = h("_exit",
            FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT));
    private static final MethodHandle EXECV = h("execv",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle EXECVE = h("execve",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle READLINK = h("readlink",
            FunctionDescriptor.of(ValueLayout.JAVA_LONG,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final MethodHandle FCNTL = h("fcntl",
            FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    public static int socketpair(Arena arena, int domain, int type, int protocol, int[] fds) {
        try {
            MemorySegment seg = arena.allocate(ValueLayout.JAVA_INT, 2);
            int rc = (int) SOCKETPAIR.invoke(domain, type, protocol, seg);
            if (rc == 0) {
                fds[0] = seg.getAtIndex(ValueLayout.JAVA_INT, 0);
                fds[1] = seg.getAtIndex(ValueLayout.JAVA_INT, 1);
            }
            return rc;
        } catch (Throwable t) { throw sneaky(t); }
    }

    public static int socket(int domain, int type, int protocol) {
        try { return (int) SOCKET.invoke(domain, type, protocol); }
        catch (Throwable t) { throw sneaky(t); }
    }

    public static int bindUnix(Arena arena, int fd, String path) {
        try {
            MemorySegment addr = arena.allocate(110 + 2);
            addr.set(ValueLayout.JAVA_SHORT, 0, (short) 1);
            byte[] pb = path.getBytes();
            if (pb.length >= 108) throw new IllegalArgumentException("socket path too long");
            MemorySegment sunPath = addr.asSlice(2);
            sunPath.asByteBuffer().put(pb).put((byte) 0);
            return (int) BIND.invoke(fd, addr, 2 + pb.length + 1);
        } catch (Throwable t) { throw sneaky(t); }
    }

    public static int connectUnix(Arena arena, int fd, String path) {
        try {
            MemorySegment addr = arena.allocate(110 + 2);
            addr.set(ValueLayout.JAVA_SHORT, 0, (short) 1);
            byte[] pb = path.getBytes();
            if (pb.length >= 108) throw new IllegalArgumentException("socket path too long");
            MemorySegment sunPath = addr.asSlice(2);
            sunPath.asByteBuffer().put(pb).put((byte) 0);
            return (int) CONNECT.invoke(fd, addr, 2 + pb.length + 1);
        } catch (Throwable t) { throw sneaky(t); }
    }

    public static int listen(int fd, int backlog) {
        try { return (int) LISTEN.invoke(fd, backlog); }
        catch (Throwable t) { throw sneaky(t); }
    }

    public static int accept(int fd) {
        try { return (int) ACCEPT.invoke(fd, MemorySegment.NULL, MemorySegment.NULL); }
        catch (Throwable t) { throw sneaky(t); }
    }

    public static long read(Arena arena, int fd, byte[] buf) {
        try {
            MemorySegment seg = arena.allocate(buf.length);
            long n = (long) READ.invoke(fd, seg, (long) buf.length);
            if (n > 0) MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0, buf, 0, (int) n);
            return n;
        } catch (Throwable t) { throw sneaky(t); }
    }

    public static long write(Arena arena, int fd, byte[] buf) {
        try {
            MemorySegment seg = arena.allocate(buf.length);
            MemorySegment.copy(buf, 0, seg, ValueLayout.JAVA_BYTE, 0, buf.length);
            return (long) WRITE.invoke(fd, seg, (long) buf.length);
        } catch (Throwable t) { throw sneaky(t); }
    }

    public static long send(Arena arena, int fd, byte[] buf, int flags) {
        try {
            MemorySegment seg = arena.allocate(buf.length);
            MemorySegment.copy(buf, 0, seg, ValueLayout.JAVA_BYTE, 0, buf.length);
            return (long) SEND.invoke(fd, seg, (long) buf.length, flags);
        } catch (Throwable t) { throw sneaky(t); }
    }

    public static long recv(Arena arena, int fd, byte[] buf, int flags) {
        try {
            MemorySegment seg = arena.allocate(buf.length);
            long n = (long) RECV.invoke(fd, seg, (long) buf.length, flags);
            if (n > 0) MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, 0, buf, 0, (int) n);
            return n;
        } catch (Throwable t) { throw sneaky(t); }
    }

    public static int close(int fd) {
        try { return (int) CLOSE.invoke(fd); }
        catch (Throwable t) { throw sneaky(t); }
    }

    public static int unlink(Arena arena, String path) {
        try { return (int) UNLINK.invoke(arena.allocateFrom(path)); }
        catch (Throwable t) { throw sneaky(t); }
    }

    public static int access(Arena arena, String path, int mode) {
        try { return (int) ACCESS.invoke(arena.allocateFrom(path), mode); }
        catch (Throwable t) { throw sneaky(t); }
    }

    public static int mkdir(Arena arena, String path, int mode) {
        try { return (int) MKDIR.invoke(arena.allocateFrom(path), mode); }
        catch (Throwable t) { throw sneaky(t); }
    }

    public static int open(Arena arena, String path, int flags, int mode) {
        try { return (int) OPEN.invoke(arena.allocateFrom(path), flags, mode); }
        catch (Throwable t) { throw sneaky(t); }
    }

    public static int fchdir(int fd) {
        try { return (int) FCHDIR.invoke(fd); }
        catch (Throwable t) { throw sneaky(t); }
    }

    public static int fork() {
        try { return (int) FORK.invoke(); }
        catch (Throwable t) { throw sneaky(t); }
    }

    public static void _exit(int status) {
        try { EXIT_.invoke(status); }
        catch (Throwable t) { throw sneaky(t); }
    }

    public static int execve(Arena arena, String path, String[] argv, String[] envp) {
        try {
            ExecvePayload p = ExecvePayload.build(arena, path, argv, envp);
            return invokeExecve(p);
        } catch (Throwable t) { throw sneaky(t); }
    }

    public static int invokeExecve(ExecvePayload p) {
        try {
            return (int) EXECVE.invoke(p.path, p.argv, p.envp);
        } catch (Throwable t) { throw sneaky(t); }
    }

    public static final class ExecvePayload {
        public final MemorySegment path;
        public final MemorySegment argv;
        public final MemorySegment envp;
        private ExecvePayload(MemorySegment path, MemorySegment argv, MemorySegment envp) {
            this.path = path; this.argv = argv; this.envp = envp;
        }
        public static ExecvePayload build(Arena arena, String path, String[] argv, String[] envp) {
            MemorySegment pathSeg = arena.allocateFrom(path);
            MemorySegment[] argSegs = new MemorySegment[argv.length];
            for (int i = 0; i < argv.length; i++) argSegs[i] = arena.allocateFrom(argv[i]);
            MemorySegment argvArr = arena.allocate(ValueLayout.ADDRESS, argv.length + 1L);
            for (int i = 0; i < argv.length; i++) argvArr.setAtIndex(ValueLayout.ADDRESS, i, argSegs[i]);
            argvArr.setAtIndex(ValueLayout.ADDRESS, argv.length, MemorySegment.NULL);

            MemorySegment[] envSegs = new MemorySegment[envp.length];
            for (int i = 0; i < envp.length; i++) envSegs[i] = arena.allocateFrom(envp[i]);
            MemorySegment envArr = arena.allocate(ValueLayout.ADDRESS, envp.length + 1L);
            for (int i = 0; i < envp.length; i++) envArr.setAtIndex(ValueLayout.ADDRESS, i, envSegs[i]);
            envArr.setAtIndex(ValueLayout.ADDRESS, envp.length, MemorySegment.NULL);

            return new ExecvePayload(pathSeg, argvArr, envArr);
        }
    }

    public static int execv(Arena arena, String path, String[] argv) {
        try {
            MemorySegment[] segs = new MemorySegment[argv.length];
            for (int i = 0; i < argv.length; i++) segs[i] = arena.allocateFrom(argv[i]);
            MemorySegment arr = arena.allocate(ValueLayout.ADDRESS, argv.length + 1L);
            for (int i = 0; i < argv.length; i++) arr.setAtIndex(ValueLayout.ADDRESS, i, segs[i]);
            arr.setAtIndex(ValueLayout.ADDRESS, argv.length, MemorySegment.NULL);
            return (int) EXECV.invoke(arena.allocateFrom(path), arr);
        } catch (Throwable t) { throw sneaky(t); }
    }

    public static int fcntl(int fd, int op, int arg) {
        try { return (int) FCNTL.invoke(fd, op, arg); }
        catch (Throwable t) { throw sneaky(t); }
    }

    public static String readlink(Arena arena, String path) {
        try {
            MemorySegment p = arena.allocateFrom(path);
            MemorySegment buf = arena.allocate(4096);
            long n = (long) READLINK.invoke(p, buf, 4095L);
            if (n < 0) return null;
            byte[] b = new byte[(int) n];
            MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0, b, 0, (int) n);
            return new String(b);
        } catch (Throwable t) { throw sneaky(t); }
    }

    private static RuntimeException sneaky(Throwable t) {
        if (t instanceof RuntimeException re) return re;
        if (t instanceof Error e) throw e;
        return new RuntimeException(t);
    }
}
