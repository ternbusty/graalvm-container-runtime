package com.ternbusty.takoyaki.time;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.spec.Spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Apply OCI {@code linux.timeOffsets} to the current time namespace by writing the
 * clock offsets into {@code /proc/self/timens_offsets}.
 *
 * Allowed clocks: monotonic, boottime. Format per line:
 *   {@code <clock_id> <secs> <nanosecs>}
 *
 * The kernel only allows writes while the namespace has no children, so we do this
 * inside Stage-2 (PID 1 in the new time NS) before any further forks.
 */
public final class TimeNs {
    private static final int CLOCK_MONOTONIC = 1;
    private static final int CLOCK_BOOTTIME  = 7;

    private TimeNs() {}

    public static void applyOffsets(Map<String, Spec.TimeOffset> offsets) {
        if (offsets == null || offsets.isEmpty()) return;
        StringBuilder content = new StringBuilder();
        for (Map.Entry<String, Spec.TimeOffset> e : offsets.entrySet()) {
            int clockId = switch (e.getKey()) {
                case "monotonic" -> CLOCK_MONOTONIC;
                case "boottime"  -> CLOCK_BOOTTIME;
                default -> -1;
            };
            if (clockId < 0) {
                Logger.warn("unsupported timeOffset clock: " + e.getKey());
                continue;
            }
            Spec.TimeOffset off = e.getValue();
            content.append(clockId).append(' ')
                   .append(off.secs).append(' ')
                   .append(off.nanosecs).append('\n');
        }
        if (content.length() == 0) return;
        try {
            Files.writeString(Path.of("/proc/self/timens_offsets"), content.toString());
            Logger.debug("timens_offsets applied: " + content.toString().trim().replace('\n', '|'));
        } catch (IOException e) {
            Logger.warn("timens_offsets write failed: " + e.getMessage());
        }
    }
}
