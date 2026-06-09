package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.syscall.Libc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/** Minimal waitpid wrapper for foreground subcommands like `exec`. */
final class Wait {
    private static final MethodHandle WAITPID;
    static {
        Linker linker = Linker.nativeLinker();
        WAITPID = linker.downcallHandle(linker.defaultLookup().find("waitpid").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    }

    private Wait() {}

    static int waitForChild(int pid) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment status = arena.allocate(ValueLayout.JAVA_INT);
            int rc = (int) WAITPID.invoke(pid, status, 0);
            if (rc < 0) return 1;
            int s = status.get(ValueLayout.JAVA_INT, 0);
            // WIFEXITED ? WEXITSTATUS(s) : 128 + WTERMSIG(s)
            if ((s & 0x7f) == 0) return (s >> 8) & 0xff;
            return 128 + (s & 0x7f);
        } catch (Throwable t) {
            return 1;
        }
    }
}
