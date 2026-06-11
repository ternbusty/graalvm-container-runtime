package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.state.State;
import com.ternbusty.takoyaki.util.Json;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ListCommand {
    private ListCommand() {}

    public static int run(String rootPath, String format, boolean quiet) {
        Path rootDir = Path.of(rootPath);
        if (!Files.isDirectory(rootDir)) {
            if ("json".equals(format)) System.out.println("[]");
            return 0;
        }
        List<State> states = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(rootDir)) {
            for (Path child : ds) {
                if (!Files.isDirectory(child)) continue;
                try {
                    State s = State.load(rootPath, child.getFileName().toString())
                            .refreshStatus();
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
            System.out.println(Json.encode(states));
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
