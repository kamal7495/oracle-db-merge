package com.dbmerge.util;

import java.util.List;
import java.util.stream.Collectors;

/** Identifier quoting. All table/column names come from the data dictionary, never from free-form user text. */
public final class Sql {
    private Sql() { }

    public static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public static String qualified(String owner, String table) {
        return quote(owner) + "." + quote(table);
    }

    public static String columnList(List<String> columns) {
        return columns.stream().map(Sql::quote).collect(Collectors.joining(", "));
    }

    /** Escapes a value for use inside a single-quoted SQL literal (used only for generated review scripts). */
    public static String literal(String value) {
        return value == null ? "NULL" : "'" + value.replace("'", "''") + "'";
    }
}
