package com.dbmerge.ddl.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything extracted from one database. {@code ddl} holds DBMS_METADATA text keyed by
 * "TABLE:NAME", "INDEX:NAME", "SEQUENCE:NAME" or "TRIGGER:NAME"; it is informational and is not used for
 * the structural comparison (which works on the parsed model above).
 */
public record SchemaModel(
        String nickname,
        String owner,
        Map<String, TableDef> tables,
        Map<String, SequenceDef> sequences,
        Map<String, String> ddl,
        List<String> warnings,
        Instant extractedAt) {

    public int columnCount() {
        return tables.values().stream().mapToInt(t -> t.columns().size()).sum();
    }
}
