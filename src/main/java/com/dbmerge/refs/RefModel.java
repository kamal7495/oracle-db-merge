package com.dbmerge.refs;

import java.time.Instant;
import java.util.List;

/** Undeclared-reference detection: columns that behave like foreign keys but have no FOREIGN KEY constraint. */
public final class RefModel {

    private RefModel() { }

    public enum Confidence { HIGH, MEDIUM, LOW }

    public enum Kind {
        /** Column whose name and type point at another table's primary key. */
        LIKELY_FK("Likely foreign key"),
        /** The table's own primary key is also another table's primary key (1:1 extension table). */
        SHARED_PRIMARY_KEY("Shared primary key"),
        /** ID column paired with a type/class column in the same table: points at different tables per row. */
        POLYMORPHIC("Type + ID pair"),
        /** Looks like an identifier, but no table in the schema matches it. */
        UNRESOLVED_ID("ID-like, no matching table");

        private final String label;

        Kind(String label) { this.label = label; }

        public String label() { return label; }
    }

    /** Result of checking a candidate against the data: how many child values exist in the parent table. */
    public record DataCheck(String parentTable, long nonNullValues, long unresolvedValues, List<String> unresolvedExamples, String error) {

        public boolean ran() {
            return error == null;
        }

        /** Share of non-null child values found in the parent, 0..1; null when nothing could be measured. */
        public Double resolvedShare() {
            if (!ran() || nonNullValues == 0) return null;
            return (nonNullValues - unresolvedValues) / (double) nonNullValues;
        }
    }

    public record Candidate(
            String table,
            String column,
            String columnType,
            Kind kind,
            Confidence confidence,
            List<String> parentTables,     // likely referenced tables; several when ambiguous; empty for POLYMORPHIC / UNRESOLVED_ID
            String parentColumn,           // referenced primary-key column when every parent shares one name, else null
            String rule,                   // which naming rule matched, in plain words
            List<String> notes,
            boolean indexed,               // column leads an index or key in its table
            List<DataCheck> dataChecks) {  // empty unless the data check ran

        public String qualifiedColumn() {
            return table + "." + column;
        }

        Candidate withDataChecks(List<DataCheck> checks) {
            return new Candidate(table, column, columnType, kind, confidence, parentTables, parentColumn, rule, notes, indexed, checks);
        }
    }

    public record ReferenceReport(
            String id,
            Instant generatedAt,
            String nickname,
            String owner,
            int tableCount,
            int declaredForeignKeys,
            int declaredForeignKeyColumns,
            boolean dataChecked,
            List<Candidate> candidates,
            List<String> warnings) {

        public long count(Kind kind) {
            return candidates.stream().filter(c -> c.kind() == kind).count();
        }

        public long count(Confidence confidence) {
            return candidates.stream().filter(c -> c.confidence() == confidence).count();
        }
    }
}
