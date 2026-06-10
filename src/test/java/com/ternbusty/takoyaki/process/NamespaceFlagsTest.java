package com.ternbusty.takoyaki.process;

import com.ternbusty.takoyaki.spec.Spec;
import com.ternbusty.takoyaki.syscall.Constants;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NamespaceFlagsTest {

    private Spec.Namespace ns(String type, String path) {
        Spec.Namespace n = new Spec.Namespace();
        n.type = type;
        n.path = path;
        return n;
    }

    @Test
    void nullListReturnsZero() {
        assertEquals(0, NamespaceFlags.fromSpec(null));
    }

    @Test
    void emptyListReturnsZero() {
        assertEquals(0, NamespaceFlags.fromSpec(List.of()));
    }

    @Test
    void singleMountNamespace() {
        int flags = NamespaceFlags.fromSpec(List.of(ns("mount", null)));
        assertEquals(Constants.CLONE_NEWNS, flags);
    }

    @Test
    void allCommonNamespaces() {
        int flags = NamespaceFlags.fromSpec(List.of(
                ns("mount", null),
                ns("pid", null),
                ns("uts", null),
                ns("ipc", null),
                ns("network", null)));
        int expected = Constants.CLONE_NEWNS | Constants.CLONE_NEWPID
                | Constants.CLONE_NEWUTS | Constants.CLONE_NEWIPC
                | Constants.CLONE_NEWNET;
        assertEquals(expected, flags);
    }

    @Test
    void namespaceWithPathIsExcludedFromCloneFlags() {
        // Namespaces with a `path` are joined via setns() in bootstrap.c, NOT
        // created via unshare. They must not appear in the clone flags or we'd
        // try to both join AND create the same namespace.
        int flags = NamespaceFlags.fromSpec(List.of(
                ns("mount", null),
                ns("ipc", "/proc/self/ns/ipc"),
                ns("pid", null)));
        int expected = Constants.CLONE_NEWNS | Constants.CLONE_NEWPID;
        assertEquals(expected, flags, "ipc with path should be omitted");
        assertEquals(0, flags & Constants.CLONE_NEWIPC,
                "CLONE_NEWIPC must NOT be set when ipc namespace has a path");
    }

    @Test
    void emptyPathStringIsTreatedAsNoPath() {
        int flags = NamespaceFlags.fromSpec(List.of(ns("uts", "")));
        assertEquals(Constants.CLONE_NEWUTS, flags,
                "empty-string path is the JSON-default for missing field, treat as no path");
    }

    @Test
    void unknownNamespaceTypeContributesNothing() {
        int flags = NamespaceFlags.fromSpec(List.of(
                ns("mount", null),
                ns("bogus", null)));
        assertEquals(Constants.CLONE_NEWNS, flags);
    }

    @Test
    void toFlagCoversEveryDocumentedType() {
        assertEquals(Constants.CLONE_NEWNS,     NamespaceFlags.toFlag("mount"));
        assertEquals(Constants.CLONE_NEWNET,    NamespaceFlags.toFlag("network"));
        assertEquals(Constants.CLONE_NEWUTS,    NamespaceFlags.toFlag("uts"));
        assertEquals(Constants.CLONE_NEWIPC,    NamespaceFlags.toFlag("ipc"));
        assertEquals(Constants.CLONE_NEWPID,    NamespaceFlags.toFlag("pid"));
        assertEquals(Constants.CLONE_NEWUSER,   NamespaceFlags.toFlag("user"));
        assertEquals(Constants.CLONE_NEWCGROUP, NamespaceFlags.toFlag("cgroup"));
        assertEquals(Constants.CLONE_NEWTIME,   NamespaceFlags.toFlag("time"));
        assertEquals(0, NamespaceFlags.toFlag("bogus"));
        assertEquals(0, NamespaceFlags.toFlag(null));
    }
}
