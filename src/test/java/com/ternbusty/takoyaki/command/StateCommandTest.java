package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.state.ContainerStatus;
import com.ternbusty.takoyaki.state.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StateCommandTest {

    private final PrintStream realStdout = System.out;
    private ByteArrayOutputStream capturedStdout;

    @BeforeEach
    void captureStdout() {
        capturedStdout = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capturedStdout));
    }

    @AfterEach
    void restoreStdout() {
        System.setOut(realStdout);
    }

    private StateCommand newCmd(String id) {
        StateCommand c = new StateCommand();
        c.root = new TakoyakiRoot();
        c.root.rootPath = "/run/takoyaki";
        c.containerId = id;
        return c;
    }

    private State sampleState() {
        State s = new State();
        s.ociVersion = "1.0.0";
        s.id = "ctr-a";
        s.status = ContainerStatus.RUNNING.value;
        s.pid = 4242;
        s.bundle = "/tmp/bundle";
        return s;
    }

    @Test
    void printsStateJsonOnSuccess() {
        State st = spy(sampleState());
        doReturn(st).when(st).refreshStatus();

        try (MockedStatic<State> sm = mockStatic(State.class)) {
            sm.when(() -> State.load("/run/takoyaki", "ctr-a")).thenReturn(st);

            int rc = newCmd("ctr-a").call();

            assertEquals(0, rc);
            String out = capturedStdout.toString();
            // The JSON must use the lower-case spec status string and the
            // fields runtime-tools' Go state parser expects (ociVersion, id,
            // pid, status, bundle).
            assertTrue(out.contains("\"id\""),         "output missing id field: " + out);
            assertTrue(out.contains("\"ctr-a\""),      "output missing id value: " + out);
            assertTrue(out.contains("\"running\""),    "status must be lowercase 'running'");
            assertTrue(out.contains("\"pid\""),        "output missing pid field");
            assertTrue(out.contains("4242"),           "output missing pid value");
        }
    }

    @Test
    void returnsNonzeroOnStateLoadFailure() {
        try (MockedStatic<State> sm = mockStatic(State.class)) {
            sm.when(() -> State.load(anyString(), anyString()))
                    .thenThrow(new java.io.IOException("no such container"));

            int rc = newCmd("missing").call();

            assertEquals(1, rc);
            assertEquals("", capturedStdout.toString(),
                    "no JSON should be printed on failure (stderr-only error log)");
        }
    }

    @Test
    void refreshStatusIsCalled() {
        // Critical for runtime-tools: state must reflect *current* process
        // liveness, not the stale value in state.json. If we forget to
        // refreshStatus, a stopped container keeps showing "running" and
        // the WaitingForStatus polls in pidfile/killsig/delete tests break.
        State st = spy(sampleState());
        doReturn(st).when(st).refreshStatus();

        try (MockedStatic<State> sm = mockStatic(State.class)) {
            sm.when(() -> State.load(anyString(), anyString())).thenReturn(st);
            newCmd("ctr-a").call();
            verify(st, times(1)).refreshStatus();
        }
    }
}
