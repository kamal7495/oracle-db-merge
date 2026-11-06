package com.dbmerge.conn;

import java.sql.SQLException;

/**
 * Retries a read that failed with ORA-01466 ("unable to read data - table definition has changed").
 *
 * <p>Every read in this tool runs in a {@code SET TRANSACTION READ ONLY} transaction. Oracle refuses such a
 * transaction access to a table whose DDL is newer than the transaction's snapshot, and because the
 * DDL-time-to-SCN mapping is coarse it also refuses tables created or altered <em>a few seconds before</em> the
 * transaction started. That happens in normal use: an approved schema change creates a table on the master
 * (DDL commits at once, even if the data step later rolls back) and the user plans again straight away.
 *
 * <p>Each attempt must start a <b>new</b> read-only transaction (a new connection, or a reopened one), otherwise
 * the retry sees the same stale snapshot. Any other error is rethrown immediately.
 */
public final class ReadOnlyRetry {

    /** Oracle error code for ORA-01466. */
    public static final int ORA_TABLE_DEFINITION_CHANGED = 1466;

    /** Waits between attempts; about 10 s in total, well past the few seconds Oracle needs. */
    static final long[] DEFAULT_DELAYS_MS = {1_000, 2_000, 3_000, 4_000};

    @FunctionalInterface
    public interface SqlCall<T> {
        T call() throws SQLException;
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private ReadOnlyRetry() {
    }

    public static <T> T call(SqlCall<T> attempt) throws SQLException {
        return call(attempt, DEFAULT_DELAYS_MS, Thread::sleep);
    }

    static <T> T call(SqlCall<T> attempt, long[] delaysMs, Sleeper sleeper) throws SQLException {
        for (int i = 0; ; i++) {
            try {
                return attempt.call();
            } catch (SQLException e) {
                if (!isTableDefinitionChanged(e) || i >= delaysMs.length) throw e;
                try {
                    sleeper.sleep(delaysMs[i]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** True if ORA-01466 is anywhere in the exception's cause chain or SQLException chain. */
    public static boolean isTableDefinitionChanged(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException s) {
                for (SQLException n = s; n != null; n = n.getNextException()) {
                    if (n.getErrorCode() == ORA_TABLE_DEFINITION_CHANGED) return true;
                }
            }
            if (c.getCause() == c) break;
        }
        return false;
    }
}
