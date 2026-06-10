package com.ternbusty.takoyaki.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContainerStatusTest {

    @Test
    void fromStringMatchesOciSpec() {
        assertEquals(ContainerStatus.CREATING, ContainerStatus.fromString("creating"));
        assertEquals(ContainerStatus.CREATED,  ContainerStatus.fromString("created"));
        assertEquals(ContainerStatus.RUNNING,  ContainerStatus.fromString("running"));
        assertEquals(ContainerStatus.STOPPED,  ContainerStatus.fromString("stopped"));
    }

    @Test
    void fromStringUnknownThrows() {
        // Unknown / null inputs are programmer errors at the call site (state
        // file corruption or a typo), so fail fast rather than silently
        // bucketing them into one of the four real statuses.
        assertThrows(IllegalArgumentException.class,
                () -> ContainerStatus.fromString("garbage"));
        assertThrows(IllegalArgumentException.class,
                () -> ContainerStatus.fromString(null));
    }

    @Test
    void canStartOnlyFromCreated() {
        // OCI runtime spec: `start` is only valid on a `created` container.
        assertTrue (ContainerStatus.CREATED.canStart());
        assertFalse(ContainerStatus.CREATING.canStart());
        assertFalse(ContainerStatus.RUNNING.canStart());
        assertFalse(ContainerStatus.STOPPED.canStart());
    }

    @Test
    void canKillRequiresCreatedOrRunning() {
        // OCI: "if the container is neither created nor running, kill MUST error".
        assertTrue (ContainerStatus.CREATED.canKill());
        assertTrue (ContainerStatus.RUNNING.canKill());
        assertFalse(ContainerStatus.CREATING.canKill());
        assertFalse(ContainerStatus.STOPPED.canKill());
    }

    @Test
    void canDeleteOnlyFromStopped() {
        // Without --force, delete is rejected unless the container has stopped.
        assertTrue (ContainerStatus.STOPPED.canDelete());
        assertFalse(ContainerStatus.CREATING.canDelete());
        assertFalse(ContainerStatus.CREATED.canDelete());
        assertFalse(ContainerStatus.RUNNING.canDelete());
    }

    @Test
    void valueIsTheOciSpecString() {
        // The state JSON we hand to runtime-tools / hooks must use the spec's
        // exact lowercase status strings.
        assertEquals("creating", ContainerStatus.CREATING.value);
        assertEquals("created",  ContainerStatus.CREATED.value);
        assertEquals("running",  ContainerStatus.RUNNING.value);
        assertEquals("stopped",  ContainerStatus.STOPPED.value);
    }
}
