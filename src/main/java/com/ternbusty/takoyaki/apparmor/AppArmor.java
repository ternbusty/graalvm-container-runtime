package com.ternbusty.takoyaki.apparmor;

import com.ternbusty.takoyaki.logger.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Apply an AppArmor profile to the calling thread by writing to
 * /proc/self/attr/exec. The profile takes effect on the next exec(2).
 *
 * Must be done before execve() but after PR_SET_NO_NEW_PRIVS (if used) and
 * after dropping privileges; the kernel rejects writes if no_new_privs is set
 * AFTER apparmor.
 */
public final class AppArmor {
    private AppArmor() {}

    public static void apply(String profile) {
        if (profile == null || profile.isEmpty() || "unconfined".equals(profile)) return;
        if (!Files.exists(Path.of("/sys/kernel/security/apparmor"))) {
            Logger.debug("apparmor not enabled on kernel, skipping profile=" + profile);
            return;
        }
        String command = "exec " + profile;
        try {
            Files.writeString(Path.of("/proc/self/attr/exec"), command);
            Logger.debug("apparmor profile staged: " + profile);
        } catch (IOException e) {
            Logger.warn("apparmor write failed (profile=" + profile + "): " + e.getMessage());
        }
    }
}
