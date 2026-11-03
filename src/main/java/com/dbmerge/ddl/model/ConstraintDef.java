package com.dbmerge.ddl.model;

import java.util.List;
import java.util.Locale;

/**
 * PK (P), unique (U), foreign key (R) or check (C) constraint. Constraints are matched across databases by
 * their structure (signature), not by name, because system-generated names (SYS_C00123) differ everywhere.
 */
public record ConstraintDef(
        String name,
        String type,               // P, U, R, C
        List<String> columns,
        String condition,          // check constraints only
        String refTable,           // foreign keys only (OWNER.TABLE when cross-schema)
        List<String> refColumns,
        String deleteRule,         // NO ACTION / CASCADE / SET NULL
        String status,             // ENABLED / DISABLED
        boolean generatedName) {

    public String signature() {
        return switch (type) {
            case "P" -> "PK(" + String.join(",", columns) + ")";
            case "U" -> "UNIQUE(" + String.join(",", columns) + ")";
            case "R" -> "FK(" + String.join(",", columns) + ") -> " + refTable
                    + "(" + String.join(",", refColumns) + ") ON DELETE " + deleteRule;
            default -> "CHECK(" + normalizeCondition(condition) + ")";
        };
    }

    public String label() {
        return switch (type) {
            case "P" -> "Primary key";
            case "U" -> "Unique";
            case "R" -> "Foreign key";
            default -> "Check";
        };
    }

    public static String normalizeCondition(String c) {
        return c == null ? "" : c.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }
}
