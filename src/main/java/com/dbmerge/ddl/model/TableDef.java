package com.dbmerge.ddl.model;

import java.util.List;
import java.util.Map;

public record TableDef(
        String name,
        Map<String, ColumnDef> columns,      // insertion order = COLUMN_ID order
        List<ConstraintDef> constraints,
        List<IndexDef> indexes,
        List<TriggerDef> triggers) {
}
