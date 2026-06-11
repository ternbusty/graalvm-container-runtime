package com.ternbusty.takoyaki.syscall;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Filesystem helpers built on direct POSIX syscalls via FFM, sidestepping
 * {@code java.nio.file.Files} and the {@code FileSystemProvider}
 * service-loader chain that it transitively pulls into the image.
 *
 * <p>String paths only. {@link java.nio.file.Path} construction triggers
 * the same {@code FileSystems.getDefault()} lookup we're trying to avoid,
 * so the API takes raw paths throughout.
 *
 * <p>Phase 3 of the cold-start work — see {@code docs/perf-history.md}.
 */
public final class Fs {
    private Fs() {}

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC = LINKER.defaultLookup();

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        return LIBC.find(name)
                .map(addr -> LINKER.downcallHandle(addr, desc))
                .orElseThrow(() -> new UnsatisfiedLinkError("libc symbol not found: " + name));
    }

    private static final MethodHandle CHMOD = downcall("chmod",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle SYMLINK = downcall("symlink",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle RMDIR = downcall("rmdir",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle OPENDIR = downcall("opendir",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle READDIR = downcall("readdir",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle CLOSEDIR = downcall("closedir",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle GETCWD = downcall("getcwd",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));

    // ---- read ---------------------------------------------------------

    public static byte[] readAllBytes(String path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            int fd = PosixIO.open(arena, path, Constants.O_RDONLY, 0);
            if (fd < 0) throw ioe("open " + path);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                while (true) {
                    long n = PosixIO.read(arena, fd, buf);
                    if (n < 0) throw ioe("read " + path);
                    if (n == 0) break;
                    out.write(buf, 0, (int) n);
                }
                return out.toByteArray();
            } finally {
                PosixIO.close(fd);
            }
        }
    }

    public static String readString(String path) throws IOException {
        return new String(readAllBytes(path), StandardCharsets.UTF_8);
    }

    public static List<String> readAllLines(String path) throws IOException {
        String s = readString(path);
        // Files.readAllLines drops the trailing empty line from "a\n",
        // returning [a] not [a, ""]. Mirror that.
        if (s.isEmpty()) return List.of();
        String[] parts = s.split("\\n", -1);
        int n = parts.length;
        if (parts[n - 1].isEmpty()) n--;
        return new ArrayList<>(Arrays.asList(parts).subList(0, n));
    }

    // ---- write --------------------------------------------------------

    public static void writeBytes(String path, byte[] data) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            int fd = PosixIO.open(arena, path,
                    Constants.O_WRONLY | Constants.O_CREAT | Constants.O_TRUNC,
                    0644);
            if (fd < 0) throw ioe("open " + path);
            try {
                writeAll(arena, fd, data, path);
            } finally {
                PosixIO.close(fd);
            }
        }
    }

    public static void writeString(String path, String content) throws IOException {
        writeBytes(path, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Append-only writer used by Logger / hooks. Opens for O_WRONLY|O_APPEND
     * and returns the raw fd; caller is responsible for closing.
     *
     * <p>Note we don't expose a Java {@code OutputStream} wrapper because
     * the only thing it'd buy us is the Files.newOutputStream signature,
     * and that pulls java.nio.channels in.
     */
    public static int openForAppend(String path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            // O_APPEND = 02000 (octal)
            int fd = PosixIO.open(arena, path,
                    Constants.O_WRONLY | Constants.O_CREAT | 02000, 0644);
            if (fd < 0) throw ioe("open " + path);
            return fd;
        }
    }

    public static void appendBytes(int fd, byte[] data) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            writeAll(arena, fd, data, "fd=" + fd);
        }
    }

    private static void writeAll(Arena arena, int fd, byte[] data, String label) throws IOException {
        int off = 0;
        while (off < data.length) {
            int chunkSize = Math.min(8192, data.length - off);
            byte[] chunk = off == 0 && chunkSize == data.length
                    ? data
                    : Arrays.copyOfRange(data, off, off + chunkSize);
            long n = PosixIO.write(arena, fd, chunk);
            if (n < 0) throw ioe("write " + label);
            if (n == 0) break;
            off += (int) n;
        }
    }

    // ---- predicates ---------------------------------------------------

    public static boolean exists(String path) {
        try (Arena arena = Arena.ofConfined()) {
            return PosixIO.access(arena, path, Constants.F_OK) == 0;
        }
    }

    public static boolean isDirectory(String path) {
        try (Arena arena = Arena.ofConfined()) {
            int fd = PosixIO.open(arena, path,
                    Constants.O_RDONLY | Constants.O_DIRECTORY, 0);
            if (fd < 0) return false;
            PosixIO.close(fd);
            return true;
        }
    }

    // ---- mkdir --------------------------------------------------------

    public static int mkdir(String path, int mode) {
        try (Arena arena = Arena.ofConfined()) {
            return PosixIO.mkdir(arena, path, mode);
        }
    }

    /** Like {@code Files.createDirectories}. mode 0755. No-op if already a dir. */
    public static void createDirectories(String path) throws IOException {
        if (path.isEmpty() || isDirectory(path)) return;
        int slash = path.lastIndexOf('/');
        if (slash > 0) {
            createDirectories(path.substring(0, slash));
        }
        try (Arena arena = Arena.ofConfined()) {
            int rc = PosixIO.mkdir(arena, path, 0755);
            if (rc != 0) {
                // EEXIST is OK if the existing entry is a directory; another
                // process may have raced us. errno code 17 on linux.
                if (Libc.errno() != 17 || !isDirectory(path)) {
                    throw ioe("mkdir " + path);
                }
            }
        }
    }

    // ---- delete -------------------------------------------------------

    public static boolean deleteIfExists(String path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            // Try unlink first; if it fails with EISDIR (21), try rmdir.
            int rc = PosixIO.unlink(arena, path);
            if (rc == 0) return true;
            int err = Libc.errno();
            if (err == 2 /* ENOENT */) return false;
            if (err == 21 /* EISDIR */) {
                rc = (int) RMDIR.invoke(arena.allocateFrom(path));
                if (rc == 0) return true;
                err = Libc.errno();
                if (err == 2) return false;
                throw ioe("rmdir " + path);
            }
            throw ioe("unlink " + path);
        } catch (Throwable t) {
            if (t instanceof IOException ie) throw ie;
            throw new IOException("delete " + path, t);
        }
    }

    public static void delete(String path) throws IOException {
        if (!deleteIfExists(path)) {
            throw new IOException("no such file: " + path);
        }
    }

    // ---- symlinks -----------------------------------------------------

    public static String readSymbolicLink(String path) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            String s = PosixIO.readlink(arena, path);
            if (s == null) throw ioe("readlink " + path);
            return s;
        }
    }

    public static int createSymbolicLink(String linkPath, String target) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) SYMLINK.invoke(arena.allocateFrom(target), arena.allocateFrom(linkPath));
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    // ---- permissions --------------------------------------------------

    public static int chmod(String path, int mode) {
        try (Arena arena = Arena.ofConfined()) {
            return (int) CHMOD.invoke(arena.allocateFrom(path), mode);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    // ---- directory listing --------------------------------------------

    /** Returns the entries of {@code dirPath}, excluding "." and "..". */
    public static List<String> list(String dirPath) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment dir = (MemorySegment) OPENDIR.invoke(arena.allocateFrom(dirPath));
            if (dir.address() == 0) throw ioe("opendir " + dirPath);
            try {
                List<String> out = new ArrayList<>();
                while (true) {
                    MemorySegment ent = (MemorySegment) READDIR.invoke(dir);
                    if (ent.address() == 0) break;
                    // dirent layout (Linux/glibc): d_ino(8) + d_off(8) +
                    // d_reclen(2) + d_type(1) + d_name (up to 256). d_name
                    // starts at offset 19. NAME_MAX is 255 plus NUL.
                    MemorySegment named = ent.reinterpret(19 + 256).asSlice(19, 256);
                    String name = named.getString(0);
                    if (name.equals(".") || name.equals("..")) continue;
                    out.add(name);
                }
                return out;
            } finally {
                CLOSEDIR.invoke(dir);
            }
        } catch (Throwable t) {
            if (t instanceof IOException ie) throw ie;
            throw new IOException("list " + dirPath, t);
        }
    }

    /** Recursively delete {@code path}. Like {@code rm -rf}. */
    public static void deleteRecursively(String path) throws IOException {
        if (isDirectory(path)) {
            for (String entry : list(path)) {
                deleteRecursively(path + "/" + entry);
            }
        }
        deleteIfExists(path);
    }

    // ---- path manipulation --------------------------------------------

    /** Returns the current working directory. */
    public static String getcwd() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(4096);
            MemorySegment ret = (MemorySegment) GETCWD.invoke(buf, 4096L);
            if (ret.address() == 0) {
                throw new RuntimeException("getcwd: " + Libc.strerror(Libc.errno()));
            }
            return buf.getString(0);
        } catch (Throwable t) {
            throw sneaky(t);
        }
    }

    /**
     * Like {@code Path.of(p).toAbsolutePath().normalize().toString()}.
     * Prepends {@code getcwd()} if {@code p} is relative, then collapses
     * {@code //}, {@code /./}, and {@code /../} segments.
     */
    public static String absoluteNormalized(String p) {
        if (!p.startsWith("/")) {
            p = getcwd() + "/" + p;
        }
        return normalize(p);
    }

    /** Collapses {@code //}, {@code /./}, and {@code /../} in an absolute path. */
    public static String normalize(String p) {
        List<String> parts = new ArrayList<>();
        for (String seg : p.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(seg);
        }
        return "/" + String.join("/", parts);
    }

    /** {@code dirname} — returns the parent path without the trailing slash. */
    public static String parent(String p) {
        int slash = p.lastIndexOf('/');
        if (slash <= 0) return "/";
        return p.substring(0, slash);
    }

    /** {@code basename} — returns the last segment. */
    public static String name(String p) {
        int slash = p.lastIndexOf('/');
        return slash < 0 ? p : p.substring(slash + 1);
    }

    // ---- internals ----------------------------------------------------

    private static IOException ioe(String op) {
        return new IOException(op + ": " + Libc.strerror(Libc.errno()));
    }

    private static RuntimeException sneaky(Throwable t) {
        if (t instanceof RuntimeException re) return re;
        if (t instanceof Error e) throw e;
        return new RuntimeException(t);
    }
}
