package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.state.State;
import com.ternbusty.takoyaki.syscall.Fs;
import com.ternbusty.takoyaki.util.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class ListCommand {
    private ListCommand() {}

    public static int run(String rootPath, String format, boolean quiet) {
        if (!Fs.isDirectory(rootPath)) {
            if ("json".equals(format)) System.out.println("[]");
            return 0;
        }
        List<State> states = new ArrayList<>();
        try {
            for (String entry : Fs.list(rootPath)) {
                String child = rootPath + "/" + entry;
                if (!Fs.isDirectory(child)) continue;
                try {
                    State s = State.load(rootPath, entry).refreshStatus();
                    states.add(s);
                } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            Logger.error("list failed: " + e.getMessage());
            return 1;
        }

        if (quiet) {
            for (State s : states) System.out.println(s.id);
            return 0;
        }
        if ("json".equals(format)) {
            System.out.println(Json.encode(states.stream().map(State::toJson).toList()));
            return 0;
        }
        System.out.printf("%-30s %-8s %-10s %-20s %s%n", "ID", "PID", "STATUS", "CREATED", "BUNDLE");
        for (State s : states) {
            System.out.printf("%-30s %-8s %-10s %-20s %s%n",
                    s.id, s.pid == null ? "-" : s.pid,
                    s.status, s.created == null ? "-" : s.created, s.bundle);
        }
        return 0;
    }
}
