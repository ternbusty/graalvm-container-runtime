package com.ternbusty.takoyaki.sysctl;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.syscall.Fs;

import java.io.IOException;
import java.util.Map;

/**
 * Apply spec.linux.sysctl entries to /proc/sys/*.
 *
 * Only kernel parameters that are namespaced (UTS, IPC, Network) can safely be set
 * inside a container without affecting the host, but we apply whatever the spec
 * requests and let the kernel reject violations.
 */
public final class Sysctl {
    private Sysctl() {}

    public static void apply(Map<String, String> sysctls) {
        if (sysctls == null || sysctls.isEmpty()) return;
        for (Map.Entry<String, String> e : sysctls.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            String path = "/proc/sys/" + key.replace('.', '/');
            try {
                Fs.writeString(path, value);
                Logger.debug("sysctl " + key + "=" + value);
            } catch (IOException ex) {
                Logger.warn("sysctl " + key + "=" + value + " failed: " + ex.getMessage());
            }
        }
    }
}
