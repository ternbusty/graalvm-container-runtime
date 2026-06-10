package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.state.ContainerStatus;
import com.ternbusty.takoyaki.state.State;
import com.ternbusty.takoyaki.syscall.Constants;
import com.ternbusty.takoyaki.syscall.Libc;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Drives the full {@link KillCommand#call} method with {@code Libc} and
 * {@code State.load} stubbed out, so we exercise the actual control flow
 * (state load → status check → pid presence → signal parse → kill → ESRCH
 * tolerance) without touching the real OS.
 */
class KillCommandCallTest {

    private KillCommand newCmd(String id, String sig) {
        KillCommand c = new KillCommand();
        c.root = new TakoyakiRoot();
        c.root.rootPath = "/run/takoyaki";
        c.containerId = id;
        c.signal = sig;
        return c;
    }

    /** Build a State with the given status+pid without touching disk. */
    private State runningState(int pid) {
        State s = new State();
        s.id = "id";
        s.status = ContainerStatus.RUNNING.value;
        s.pid = pid;
        s.bundle = "/bundle";
        return s;
    }

    @Test
    void killRunningContainerCallsLibcKillWithRightSignal() {
        State st = spy(runningState(4242));
        // refreshStatus should leave it RUNNING — return self unchanged.
        doReturn(st).when(st).refreshStatus();

        try (MockedStatic<State> sm = mockStatic(State.class);
             MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            sm.when(() -> State.load("/run/takoyaki", "ctr-a")).thenReturn(st);
            lm.when(() -> Libc.kill(4242, Constants.SIGTERM)).thenReturn(0);

            int rc = newCmd("ctr-a", "SIGTERM").call();

            assertEquals(0, rc);
            lm.verify(() -> Libc.kill(4242, Constants.SIGTERM), times(1));
        }
    }

    @Test
    void killOnStoppedContainerReturnsErrorWithoutCallingLibc() {
        State st = spy(runningState(4242));
        st.status = ContainerStatus.STOPPED.value;
        doReturn(st).when(st).refreshStatus();

        try (MockedStatic<State> sm = mockStatic(State.class);
             MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            sm.when(() -> State.load(anyString(), anyString())).thenReturn(st);

            int rc = newCmd("ctr-a", "KILL").call();

            assertEquals(1, rc, "OCI spec: kill on stopped MUST error");
            lm.verifyNoInteractions();
        }
    }

    @Test
    void esrchFromLibcIsTolerated() {
        // If the process died between refreshStatus and kill(2), the kernel
        // returns ESRCH. Per runc semantics we treat that as success.
        State st = spy(runningState(4242));
        doReturn(st).when(st).refreshStatus();

        try (MockedStatic<State> sm = mockStatic(State.class);
             MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            sm.when(() -> State.load(anyString(), anyString())).thenReturn(st);
            lm.when(() -> Libc.kill(anyInt(), anyInt())).thenReturn(-1);
            lm.when(Libc::errno).thenReturn(Constants.ESRCH);

            int rc = newCmd("ctr-a", "KILL").call();

            assertEquals(0, rc, "ESRCH from kill(2) must NOT propagate as a runtime error");
        }
    }

    @Test
    void nonEsrchKillFailurePropagates() {
        State st = spy(runningState(4242));
        doReturn(st).when(st).refreshStatus();

        try (MockedStatic<State> sm = mockStatic(State.class);
             MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            sm.when(() -> State.load(anyString(), anyString())).thenReturn(st);
            lm.when(() -> Libc.kill(anyInt(), anyInt())).thenReturn(-1);
            lm.when(Libc::errno).thenReturn(1 /* EPERM */);
            lm.when(() -> Libc.strerror(anyInt())).thenReturn("Operation not permitted");

            int rc = newCmd("ctr-a", "KILL").call();

            assertEquals(1, rc, "non-ESRCH kill errors must surface as exit 1");
        }
    }

    @Test
    void invalidSignalNameReturnsErrorBeforeAnyKill() {
        State st = spy(runningState(4242));
        doReturn(st).when(st).refreshStatus();

        try (MockedStatic<State> sm = mockStatic(State.class);
             MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            sm.when(() -> State.load(anyString(), anyString())).thenReturn(st);

            int rc = newCmd("ctr-a", "TOTALLY_NOT_A_SIGNAL").call();

            assertEquals(1, rc);
            lm.verifyNoInteractions();
        }
    }

    @Test
    void missingPidIsAnError() {
        State st = spy(runningState(4242));
        st.pid = null;
        doReturn(st).when(st).refreshStatus();

        try (MockedStatic<State> sm = mockStatic(State.class);
             MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            sm.when(() -> State.load(anyString(), anyString())).thenReturn(st);

            int rc = newCmd("ctr-a", "KILL").call();

            assertEquals(1, rc);
            lm.verifyNoInteractions();
        }
    }

    @Test
    void stateLoadFailureReturnsErrorWithoutKill() {
        try (MockedStatic<State> sm = mockStatic(State.class);
             MockedStatic<Libc> lm = mockStatic(Libc.class)) {
            sm.when(() -> State.load(anyString(), anyString()))
                    .thenThrow(new java.io.IOException("no state.json"));

            int rc = newCmd("ctr-a", "KILL").call();

            assertEquals(1, rc);
            lm.verifyNoInteractions();
        }
    }
}
