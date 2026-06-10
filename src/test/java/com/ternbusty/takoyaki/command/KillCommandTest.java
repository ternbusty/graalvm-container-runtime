package com.ternbusty.takoyaki.command;

import com.ternbusty.takoyaki.syscall.Constants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KillCommandTest {

    @Test
    void numericSignalPassesThrough() {
        assertEquals(9, KillCommand.parseSignal("9"));
        assertEquals(15, KillCommand.parseSignal("15"));
    }

    @Test
    void shortNameIsAccepted() {
        // runc accepts both KILL and SIGKILL forms; we mirror that.
        assertEquals(Constants.SIGKILL, KillCommand.parseSignal("KILL"));
        assertEquals(Constants.SIGTERM, KillCommand.parseSignal("TERM"));
        assertEquals(Constants.SIGHUP,  KillCommand.parseSignal("HUP"));
    }

    @Test
    void longNameIsAccepted() {
        assertEquals(Constants.SIGKILL, KillCommand.parseSignal("SIGKILL"));
        assertEquals(Constants.SIGTERM, KillCommand.parseSignal("SIGTERM"));
    }

    @Test
    void lowerCaseIsAccepted() {
        // Loosely match runc's behaviour — accept any case.
        assertEquals(Constants.SIGKILL, KillCommand.parseSignal("kill"));
        assertEquals(Constants.SIGTERM, KillCommand.parseSignal("sigterm"));
    }

    @Test
    void unknownSignalThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> KillCommand.parseSignal("BOGUS"));
        assertThrows(IllegalArgumentException.class,
                () -> KillCommand.parseSignal("SIGBOGUS"));
    }
}
