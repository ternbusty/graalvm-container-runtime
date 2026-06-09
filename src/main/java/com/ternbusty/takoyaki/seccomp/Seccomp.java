package com.ternbusty.takoyaki.seccomp;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.spec.Spec;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.List;

public final class Seccomp {
    private Seccomp() {}

    private static final Linker LINKER = Linker.nativeLinker();
    private static volatile SymbolLookup libseccomp;
    private static volatile MethodHandle SECCOMP_INIT;
    private static volatile MethodHandle SECCOMP_RELEASE;
    private static volatile MethodHandle SECCOMP_RULE_ADD;
    private static volatile MethodHandle SECCOMP_LOAD;
    private static volatile MethodHandle SECCOMP_SYSCALL_RESOLVE_NAME;
    private static volatile MethodHandle SECCOMP_ARCH_ADD;
    private static volatile MethodHandle SECCOMP_ARCH_REMOVE;
    private static volatile MethodHandle SECCOMP_ARCH_RESOLVE_NAME;
    private static volatile MethodHandle SECCOMP_ATTR_SET;

    public static boolean preload() {
        return ensureLoaded();
    }

    private static synchronized boolean ensureLoaded() {
        if (libseccomp != null) return true;
        try {
            SymbolLookup s = libraryLookupWithFallback();
            libseccomp = s;
            SECCOMP_INIT = LINKER.downcallHandle(s.find("seccomp_init").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            SECCOMP_RELEASE = LINKER.downcallHandle(s.find("seccomp_release").orElseThrow(),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            SECCOMP_RULE_ADD = LINKER.downcallHandle(s.find("seccomp_rule_add").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT));
            SECCOMP_LOAD = LINKER.downcallHandle(s.find("seccomp_load").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            SECCOMP_SYSCALL_RESOLVE_NAME = LINKER.downcallHandle(s.find("seccomp_syscall_resolve_name").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            SECCOMP_ARCH_ADD = LINKER.downcallHandle(s.find("seccomp_arch_add").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            SECCOMP_ARCH_REMOVE = LINKER.downcallHandle(s.find("seccomp_arch_remove").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            SECCOMP_ARCH_RESOLVE_NAME = LINKER.downcallHandle(s.find("seccomp_arch_resolve_name").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            SECCOMP_ATTR_SET = LINKER.downcallHandle(s.find("seccomp_attr_set").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            return true;
        } catch (Throwable t) {
            Logger.warn("libseccomp not loadable: " + t.getMessage());
            return false;
        }
    }

    public static void apply(Spec.LinuxSeccomp sec) {
        if (sec == null) return;
        if (!ensureLoaded()) {
            Logger.error("libseccomp.so.2 not found, cannot apply seccomp");
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            int defaultAction = actionToken(sec.defaultAction, sec.defaultErrnoRet);
            MemorySegment ctx = (MemorySegment) SECCOMP_INIT.invoke(defaultAction);
            if (ctx == null || ctx.address() == 0) {
                Logger.error("seccomp_init returned NULL");
                return;
            }
            try {
                // architectures - libseccomp wants lowercase, no SCMP_ARCH_ prefix
                if (sec.architectures != null) {
                    for (String archName : sec.architectures) {
                        String n = archName;
                        if (n.startsWith("SCMP_ARCH_")) n = n.substring("SCMP_ARCH_".length());
                        n = n.toLowerCase();
                        MemorySegment nameSeg = arena.allocateFrom(n);
                        int token = (int) SECCOMP_ARCH_RESOLVE_NAME.invoke(nameSeg);
                        if (token == 0) {
                            Logger.warn("unknown seccomp arch: " + archName);
                            continue;
                        }
                        SECCOMP_ARCH_ADD.invoke(ctx, token);
                    }
                }

                if (sec.syscalls != null) {
                    for (Spec.LinuxSyscall sc : sec.syscalls) {
                        int action = actionToken(sc.action, sc.errnoRet);
                        if (sc.names == null) continue;
                        for (String name : sc.names) {
                            MemorySegment nameSeg = arena.allocateFrom(name);
                            int nr = (int) SECCOMP_SYSCALL_RESOLVE_NAME.invoke(nameSeg);
                            if (nr < 0) {
                                Logger.debug("syscall " + name + " not supported, skipping");
                                continue;
                            }
                            int rc = (int) SECCOMP_RULE_ADD.invoke(ctx, action, nr, 0);
                            if (rc != 0) {
                                Logger.debug("rule_add " + name + " failed: " + rc);
                            }
                        }
                    }
                }

                int loadRc = (int) SECCOMP_LOAD.invoke(ctx);
                if (loadRc != 0) {
                    Logger.error("seccomp_load failed: " + loadRc);
                } else {
                    Logger.info("seccomp filter loaded");
                }
            } finally {
                SECCOMP_RELEASE.invoke(ctx);
            }
        } catch (Throwable t) {
            Logger.error("seccomp apply error: " + t.getMessage());
        }
    }

    private static int actionToken(String action, Long errnoRet) {
        int errno = errnoRet == null ? 0 : errnoRet.intValue();
        // libseccomp action codes from seccomp.h
        return switch (action == null ? "SCMP_ACT_ALLOW" : action) {
            case "SCMP_ACT_KILL", "SCMP_ACT_KILL_THREAD" -> 0x00000000;
            case "SCMP_ACT_KILL_PROCESS" -> 0x80000000;
            case "SCMP_ACT_TRAP" -> 0x00030000;
            case "SCMP_ACT_ERRNO" -> 0x00050000 | (errno & 0xffff);
            case "SCMP_ACT_TRACE" -> 0x7ff00000 | (errno & 0xffff);
            case "SCMP_ACT_LOG" -> 0x7ffc0000;
            case "SCMP_ACT_ALLOW" -> 0x7fff0000;
            case "SCMP_ACT_NOTIFY" -> 0x7fc00000;
            default -> 0x7fff0000;
        };
    }

    public static boolean available() {
        return Path.of("/usr/lib/x86_64-linux-gnu/libseccomp.so.2").toFile().exists()
                || Path.of("/usr/lib/aarch64-linux-gnu/libseccomp.so.2").toFile().exists()
                || Path.of("/lib/x86_64-linux-gnu/libseccomp.so.2").toFile().exists()
                || Path.of("/lib/aarch64-linux-gnu/libseccomp.so.2").toFile().exists();
    }

    private static SymbolLookup libraryLookupWithFallback() {
        String[] candidates = {
                "libseccomp.so.2",
                "/usr/lib/aarch64-linux-gnu/libseccomp.so.2",
                "/usr/lib/x86_64-linux-gnu/libseccomp.so.2",
                "/lib/aarch64-linux-gnu/libseccomp.so.2",
                "/lib/x86_64-linux-gnu/libseccomp.so.2",
        };
        Throwable last = null;
        for (String c : candidates) {
            try {
                return SymbolLookup.libraryLookup(c, Arena.global());
            } catch (Throwable t) { last = t; }
        }
        throw new RuntimeException("libseccomp.so.2 not loadable", last);
    }

    @SuppressWarnings("unused")
    private static void touch(List<String> ignore) {}
}
