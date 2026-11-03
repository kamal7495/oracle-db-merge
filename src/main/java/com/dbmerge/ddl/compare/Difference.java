package com.dbmerge.ddl.compare;

import java.util.Map;

/**
 * One finding. {@code values} maps every compared database (in order) to what it holds for this object,
 * which is exactly one row of the side-by-side matrix. A table-less object (sequence) has a null table.
 */
public record Difference(
        DiffKind kind,
        Severity severity,
        String table,
        String object,
        Map<String, String> values,
        String description) {

    public String category() { return kind.category(); }
}
