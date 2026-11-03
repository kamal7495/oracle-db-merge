package com.dbmerge.data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Result types for the Phase 2 data comparison. Row values are normalised strings (null = SQL NULL). */
public final class DataModel {
    private DataModel() { }

    /** A row that exists in exactly one database (for the business key used). */
    public record OnlyRow(String database, String key, Map<String, String> row) { }

    /** A business key present in several databases with identical data on the compared columns. */
    public record IdenticalRow(String key, List<String> databases) { }

    /** Same business key in several databases but different data. */
    public record Conflict(String key, List<String> databases,
                           Map<String, Map<String, String>> rowsByDb, List<String> differingColumns) { }

    /** Same primary key value in several databases, but different business keys: different real-world records. */
    public record PkCollision(String pk, Map<String, String> businessKeyByDb,
                              Map<String, Map<String, String>> rowsByDb) { }

    /** Same business key but different primary key values: same record, ids must be remapped on merge. */
    public record PkDivergence(String key, Map<String, String> pkByDb) { }

    public enum Status { COMPARED, NO_KEY, SINGLE_DATABASE, TOO_LARGE, ERROR }

    public record TableDataResult(
            String table,
            Status status,
            String message,
            List<String> keyColumns,
            boolean keyIsPrimaryKey,
            List<String> pkColumns,
            Map<String, Long> rowCounts,                   // database -> rows (-1 where the table does not exist)
            List<String> comparedColumns,
            Map<String, List<String>> uncomparedColumns,   // database -> columns not present in every database
            Map<String, Long> onlyInCounts,                // database -> rows only there
            long identicalCount,
            long conflictCount,
            long pkCollisionCount,
            long pkDivergenceCount,
            List<OnlyRow> onlyRows,                        // detail lists are capped (see sampleLimit); counts are exact
            List<IdenticalRow> identicalRows,
            List<Conflict> conflicts,
            List<PkCollision> pkCollisions,
            List<PkDivergence> pkDivergences,
            List<String> warnings) {

        public long onlyInTotal() {
            return onlyInCounts.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    public record DataReport(
            String id,
            Instant generatedAt,
            String ddlComparisonId,
            List<String> databases,
            int sampleLimit,
            List<TableDataResult> tables) { }
}
