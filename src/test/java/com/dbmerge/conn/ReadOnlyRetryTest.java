package com.dbmerge.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReadOnlyRetryTest {

    private static final long[] DELAYS = {10, 20, 30};

    private final List<Long> slept = new ArrayList<>();
    private final ReadOnlyRetry.Sleeper sleeper = slept::add;

    private static SQLException ora(int code) {
        return new SQLException("ORA-" + code, "72000", code);
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void returnsImmediatelyWhenTheFirstAttemptSucceeds() throws SQLException {
        assertEquals("ok", ReadOnlyRetry.call(() -> "ok", DELAYS, sleeper));
        assertTrue(slept.isEmpty());
    }

    @Test
    void retriesOra01466UntilTheReadSucceeds() throws SQLException {
        AtomicInteger attempts = new AtomicInteger();
        String result = ReadOnlyRetry.call(() -> {
            if (attempts.incrementAndGet() < 3) throw ora(1466);
            return "rows";
        }, DELAYS, sleeper);
        assertEquals("rows", result);
        assertEquals(3, attempts.get());
        assertEquals(List.of(10L, 20L), slept);
    }

    @Test
    void givesUpAfterTheLastDelayAndRethrowsTheOracleError() {
        AtomicInteger attempts = new AtomicInteger();
        SQLException last = ora(1466);
        SQLException thrown = assertThrows(SQLException.class, () -> ReadOnlyRetry.call(() -> {
            attempts.incrementAndGet();
            throw last;
        }, DELAYS, sleeper));
        assertSame(last, thrown);
        assertEquals(DELAYS.length + 1, attempts.get());
        assertEquals(List.of(10L, 20L, 30L), slept);
    }

    @Test
    void otherErrorsAreNotRetried() {
        AtomicInteger attempts = new AtomicInteger();
        SQLException thrown = assertThrows(SQLException.class, () -> ReadOnlyRetry.call(() -> {
            attempts.incrementAndGet();
            throw ora(942); // table or view does not exist
        }, DELAYS, sleeper));
        assertEquals(942, thrown.getErrorCode());
        assertEquals(1, attempts.get());
        assertTrue(slept.isEmpty());
    }

    @Test
    void interruptionStopsRetryingAndKeepsTheInterruptFlag() {
        ReadOnlyRetry.Sleeper interrupted = ms -> { throw new InterruptedException(); };
        AtomicInteger attempts = new AtomicInteger();
        assertThrows(SQLException.class, () -> ReadOnlyRetry.call(() -> {
            attempts.incrementAndGet();
            throw ora(1466);
        }, DELAYS, interrupted));
        assertEquals(1, attempts.get());
        assertTrue(Thread.currentThread().isInterrupted());
    }

    @Test
    void recognisesOra01466InTheCauseAndNextExceptionChains() {
        SQLException wrapped = new SQLException("outer", ora(1466));
        SQLException chained = new SQLException("first");
        chained.setNextException(ora(1466));
        assertTrue(ReadOnlyRetry.isTableDefinitionChanged(ora(1466)));
        assertTrue(ReadOnlyRetry.isTableDefinitionChanged(wrapped));
        assertTrue(ReadOnlyRetry.isTableDefinitionChanged(new RuntimeException(wrapped)));
        assertTrue(ReadOnlyRetry.isTableDefinitionChanged(chained));
        assertFalse(ReadOnlyRetry.isTableDefinitionChanged(ora(942)));
        assertFalse(ReadOnlyRetry.isTableDefinitionChanged(null));
    }
}
