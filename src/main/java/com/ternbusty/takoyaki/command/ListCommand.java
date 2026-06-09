package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.logger.Logger;
import com.ternbusty.takoyaki.state.State;
import com.ternbusty.takoyaki.util.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "list", aliases = {"ls"}, description = "List containers")
public final class ListCommand implements Callable<Integer> {
    @ParentCommand
    TakoyakiRoot root;

    @Option(names = {"-f", "--format"}, defaultValue = "table",
            description = "Output format (table or json)")
    String format;

    @Option(names = {"-q", "--quiet"}, description = "Print only container IDs")
    boolean quiet;

    @Override
    public Integer call() {
        Path rootDir = Path.of(root.rootPath);
        if (!Files.isDirectory(rootDir)) {
            if ("json".equals(format)) System.out.println("[]");
            return 0;
        }
        List<State> states = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(rootDir)) {
            for (Path child : ds) {
                if (!Files.isDirectory(child)) continue;
                try {
                    State s = State.load(root.rootPath, child.getFileName().toString())
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
