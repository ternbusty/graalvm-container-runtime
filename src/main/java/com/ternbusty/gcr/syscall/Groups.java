package com.ternbusty.gcr.syscall;

import com.ternbusty.gcr.logger.Logger;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class Groups {
    private Groups() {}

    public static void setAdditional(List<Integer> gids) {
        if (gids == null || gids.isEmpty()) return;
        try {
            String s = Files.readString(Path.of("/proc/self/setgroups")).trim();
            if ("deny".equals(s)) {
                Logger.warn("setgroups denied in this user namespace, skipping");
                return;
            }
        } catch (IOException ignored) {}

        try (Arena arena = Arena.ofConfined()) {
            int[] arr = new int[gids.size()];
            for (int i = 0; i < gids.size(); i++) arr[i] = gids.get(i);
            int rc = Libc.setgroups(arena, arr);
            if (rc != 0) {
                Logger.warn("setgroups failed: " + Libc.strerror(Libc.errno()));
            } else {
                Logger.debug("set " + gids.size() + " additional groups");
            }
        }
    }
}
