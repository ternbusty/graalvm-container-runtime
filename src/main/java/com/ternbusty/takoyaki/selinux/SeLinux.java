package com.ternbusty.takoyaki.selinux;

import com.ternbusty.takoyaki.logger.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Apply a SELinux exec context to the current thread.
 *
 *   echo "container_t:s0:c1,c2" > /proc/self/attr/exec
 *
 * The next exec(2) loads the process with that label. Like AppArmor, the kernel
 * rejects further label changes after exec when PR_SET_NO_NEW_PRIVS is set, so we
 * must do this before seccomp + execvp.
 */
public final class SeLinux {
    private SeLinux() {}

    public static void apply(String label) {
        if (label == null || label.isEmpty()) return;
        if (!Files.exists(Path.of("/sys/fs/selinux")) && !Files.exists(Path.of("/sys/fs/selinuxfs"))) {
            Logger.debug("selinux not enabled, skipping label=" + label);
            return;
        }
        try {
            Files.writeString(Path.of("/proc/self/attr/exec"), label);
            Logger.debug("selinux exec label staged: " + label);
        } catch (IOException e) {
            Logger.warn("selinux exec label write failed (label=" + label + "): " + e.getMessage());
        }
    }

    /** Optional: set the mount/keycreate label too (used by some workloads). */
    public static void applyKeyCreate(String label) {
        if (label == null || label.isEmpty()) return;
        try {
            Files.writeString(Path.of("/proc/self/attr/keycreate"), label);
        } catch (IOException ignored) {}
    }
}
