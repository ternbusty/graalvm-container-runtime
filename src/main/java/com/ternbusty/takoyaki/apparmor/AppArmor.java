package com.ternbusty.takoyaki.apparmor;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.syscall.Fs;

import java.io.IOException;

/**
 * Apply an AppArmor profile to the calling thread by writing to
 * {@code /proc/self/attr/apparmor/exec} (preferred, kernel >=5.8) or
 * {@code /proc/self/attr/exec} (legacy). The profile takes effect on the next
 * exec(2) on this thread.
 *
 * Must be done before execve() but after PR_SET_NO_NEW_PRIVS (if used) and after
 * dropping privileges; the kernel rejects writes if no_new_privs is set AFTER
 * apparmor.
 */
public final class AppArmor {
    private AppArmor() {}

    public static void apply(String profile) {
        if (profile == null || profile.isEmpty() || "unconfined".equals(profile)) return;
        byte[] command = ("exec " + profile).getBytes();

        // Prefer the newer per-LSM path; fall back to the legacy attr/exec.
        if (writeAttr("/proc/self/attr/apparmor/exec", command)) {
            Logger.debug("apparmor profile staged via attr/apparmor/exec: " + profile);
            return;
        }
        if (writeAttr("/proc/self/attr/exec", command)) {
            Logger.debug("apparmor profile staged via attr/exec: " + profile);
            return;
        }
        Logger.warn("apparmor profile write failed for both paths: " + profile);
    }

    private static boolean writeAttr(String path, byte[] command) {
        if (!Fs.exists(path)) return false;
        try {
            Fs.writeBytes(path, command);
            return true;
        } catch (IOException e) {
            Logger.debug("apparmor write " + path + " failed: " + e.getMessage());
            return false;
        }
    }
}
