package com.ternbusty.takoyaki.time;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.syscall.Fs;

import java.io.IOException;
import java.util.Map;

/**
 * Apply OCI {@code linux.timeOffsets} to the current time namespace by writing the
 * clock offsets into {@code /proc/self/timens_offsets}.
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
            Fs.writeString("/proc/self/timens_offsets", content.toString());
            Logger.debug("timens_offsets applied: " + content.toString().trim().replace('\n', '|'));
        } catch (IOException e) {
            Logger.warn("timens_offsets write failed: " + e.getMessage());
        }
    }
}
