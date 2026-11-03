package com.dbmerge.ddl.model;

import java.util.List;

/** A non-constraint index (indexes that merely back a PK/unique constraint are dropped by the extractor). */
public record IndexDef(
        String name,
        boolean unique,
        String indexType,          // NORMAL, BITMAP, FUNCTION-BASED NORMAL ...
        List<String> columns,      // "COL" or "COL DESC"; function-based columns appear as <expression>
        boolean generatedName) {

    public String signature() {
        return (unique ? "UNIQUE " : "")
                + ("NORMAL".equals(indexType) ? "" : indexType + " ")
                + "INDEX(" + String.join(",", columns) + ")";
    }
}
