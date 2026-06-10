package com.ternbusty.takoyaki.apparmor;

import com.ternbusty.takoyaki.logger.Logger;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
        if (!Files.exists(Path.of("/sys/kernel/security/apparmor"))) {
            Logger.debug("apparmor not enabled on kernel, skipping profile=" + profile);
            return;
        }
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

    /**
     * Write the command in one write(2) syscall via FileOutputStream so the kernel
     * sees the exact buffer (no trailing newline, no readback).
     */
    private static boolean writeAttr(String path, byte[] command) {
        if (!Files.exists(Path.of(path))) return false;
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write(command);
            return true;
        } catch (IOException e) {
            Logger.debug("apparmor write " + path + " failed: " + e.getMessage());
            return false;
        }
    }
}
