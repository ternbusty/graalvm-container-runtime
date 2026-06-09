package com.ternbusty.gcr.syscall;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

public final class Bootstrap {
    private Bootstrap() {}

    private static final MethodHandle IS_INIT;

    static {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.loaderLookup().or(linker.defaultLookup());
        IS_INIT = lookup.find("gcr_is_init_process")
                .map(addr -> linker.downcallHandle(addr, FunctionDescriptor.of(ValueLayout.JAVA_INT)))
                .orElse(null);
    }

    public static boolean isInitProcess() {
        if (IS_INIT == null) return false;
        try {
            return ((int) IS_INIT.invoke()) != 0;
        } catch (Throwable t) {
            return false;
        }
    }
}
