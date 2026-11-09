package com.dbmerge.ddl.compare;

public enum DiffKind {
    MISSING_TABLE("TABLE", "Missing table"),
    MISSING_COLUMN("COLUMN", "Missing column"),
    TYPE_MISMATCH("COLUMN", "Type mismatch"),
    LENGTH_MISMATCH("COLUMN", "Length / precision mismatch"),
    NULLABLE_MISMATCH("COLUMN", "Nullability mismatch"),
    DEFAULT_MISMATCH("COLUMN", "Default mismatch"),
    MISSING_CONSTRAINT("CONSTRAINT", "Missing constraint"),
    CONSTRAINT_DEFINITION_MISMATCH("CONSTRAINT", "Constraint definition differs"),
    CONSTRAINT_NAME_MISMATCH("CONSTRAINT", "Constraint name differs"),
    CONSTRAINT_STATUS_MISMATCH("CONSTRAINT", "Constraint enabled/disabled differs"),
    MISSING_INDEX("INDEX", "Missing index"),
    INDEX_NAME_MISMATCH("INDEX", "Index name differs"),
    MISSING_SEQUENCE("SEQUENCE", "Missing sequence"),
    SEQUENCE_PARAM_MISMATCH("SEQUENCE", "Sequence parameters differ"),
    MISSING_TRIGGER("TRIGGER", "Missing trigger"),
    TRIGGER_DEFINITION_MISMATCH("TRIGGER", "Trigger definition differs"),
    TRIGGER_STATUS_MISMATCH("TRIGGER", "Trigger enabled/disabled differs"),
    MISSING_VIEW("VIEW", "Missing view"),
    VIEW_DEFINITION_MISMATCH("VIEW", "View definition differs"),
    MISSING_MVIEW("MVIEW", "Missing materialized view"),
    MVIEW_DEFINITION_MISMATCH("MVIEW", "Materialized view definition differs"),
    MISSING_PLSQL("PLSQL", "Missing PL/SQL object"),
    PLSQL_SOURCE_MISMATCH("PLSQL", "PL/SQL source differs"),
    OBJECT_STATUS_MISMATCH("CODE", "Valid / invalid differs"),
    MISSING_SYNONYM("SYNONYM", "Missing synonym"),
    SYNONYM_TARGET_MISMATCH("SYNONYM", "Synonym target differs"),
    MISSING_GRANT("GRANT", "Missing grant"),
    GRANT_OPTION_MISMATCH("GRANT", "Grant option differs");

    private final String category;
    private final String label;

    DiffKind(String category, String label) {
        this.category = category;
        this.label = label;
    }

    public String category() { return category; }
    public String label() { return label; }
}
