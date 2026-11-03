package com.dbmerge.conn;

public record TestResult(
        boolean ok,
        String message,
        String serverVersion,
        String currentSchema,
        long elapsedMillis) {
}
