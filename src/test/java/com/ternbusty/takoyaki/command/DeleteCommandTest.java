package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.state.ContainerStatus;
import com.ternbusty.takoyaki.state.State;
import com.ternbusty.takoyaki.syscall.Constants;
import com.ternbusty.takoyaki.syscall.Libc;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeleteCommandTest {

    private DeleteCommand newCmd(String id, boolean force) {
        DeleteCommand c = new DeleteCommand();
        c.root = new TakoyakiRoot();
        c.root.rootPath = "/run/takoyaki";
        c.containerId = id;
        c.force = force;
        return c;
    }

    private State stoppedState() {
        State s = new State();
        s.id = "ctr-a";
        s.status = ContainerStatus.STOPPED.value;
        s.pid = 4242;
        s.bundle = "/tmp/bundle";
        return s;
    }

    private State runningState() {
        State s = new State();
        s.id = "ctr-a";
        s.status = ContainerStatus.RUNNING.value;
        s.pid = 4242;
        s.bundle = "/tmp/bundle";
        return s;
    }

    @Test
    void deletingMissingContainerWithoutForceErrors() {
        try (MockedStatic<State> sm = mockStatic(State.class)) {
            sm.when(() -> State.exists(anyString(), anyString())).thenReturn(false);
            int rc = newCmd("missing", false).call();
            assertEquals(1, rc);
        }
    }

    @Test
    void deletingMissingContainerWithForceSucceeds() {
        // --force on a non-existent container is a soft no-op so that cleanup
        // scripts that always run `delete --force` don't trip.
        try (MockedStatic<State> sm = mockStatic(State.class)) {
            sm.when(() -> State.exists(anyString(), anyString())).thenReturn(false);
            int rc = newCmd("missing", true).call();
            assertEquals(0, rc);
        }
    }

    @Test
    void deletingRunningContainerWithoutForceErrorsAndDoesNotKill() {
        State st = spy(runningState());
        doReturn(st).when(st).refreshStatus();

        try (MockedStatic<State> sm = mockStatic(State.class);
             MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            sm.when(() -> State.exists(anyString(), anyString())).thenReturn(true);
            sm.when(() -> State.load(anyString(), anyString())).thenReturn(st);

            int rc = newCmd("ctr-a", false).call();

            assertEquals(1, rc, "OCI: delete on non-stopped MUST error without --force");
            lm.verify(() -> Libc.kill(anyInt(), anyInt()), never());
        }
    }

    @Test
    void deletingRunningContainerWithForceSendsSigkill() {
        State st = spy(runningState());
        doReturn(st).when(st).refreshStatus();

        try (MockedStatic<State> sm = mockStatic(State.class);
             MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            sm.when(() -> State.exists(anyString(), anyString())).thenReturn(true);
            sm.when(() -> State.load(anyString(), anyString())).thenReturn(st);
            sm.when(() -> State.containerDir(anyString(), anyString()))
                    .thenReturn(java.nio.file.Path.of("/tmp/nonexistent-container-dir"));
            lm.when(() -> Libc.kill(anyInt(), anyInt())).thenReturn(0);

            int rc = newCmd("ctr-a", true).call();

            assertEquals(0, rc);
            lm.verify(() -> Libc.kill(eq(4242), eq(Constants.SIGKILL)));
        }
    }

    @Test
    void deletingStoppedContainerSkipsKill() {
        State st = spy(stoppedState());
        doReturn(st).when(st).refreshStatus();

        try (MockedStatic<State> sm = mockStatic(State.class);
             MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            sm.when(() -> State.exists(anyString(), anyString())).thenReturn(true);
            sm.when(() -> State.load(anyString(), anyString())).thenReturn(st);
            sm.when(() -> State.containerDir(anyString(), anyString()))
                    .thenReturn(java.nio.file.Path.of("/tmp/nonexistent-container-dir"));

            int rc = newCmd("ctr-a", false).call();

            assertEquals(0, rc);
            // No kill should be issued — container is already stopped.
            lm.verify(() -> Libc.kill(anyInt(), anyInt()), never());
        }
    }

    @Test
    void stateLoadFailureReturnsError() {
        try (MockedStatic<State> sm = mockStatic(State.class)) {
            sm.when(() -> State.exists(anyString(), anyString())).thenReturn(true);
            sm.when(() -> State.load(anyString(), anyString()))
                    .thenThrow(new IOException("corrupt"));

            int rc = newCmd("ctr-a", false).call();

            assertEquals(1, rc);
        }
    }
}
