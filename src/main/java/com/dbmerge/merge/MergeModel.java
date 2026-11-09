package com.dbmerge.merge;

import com.dbmerge.ddl.model.TableDef;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Plan and result types for Phase 3. A plan is built read-only (dry run) and is exactly what the executor runs. */
public final class MergeModel {
    private MergeModel() { }

    public enum RowAction {
        INSERT,              // new row, keeps its source primary key
        INSERT_REMAPPED,     // new row whose primary key collides: gets a new key (sequence or max+1), mapping recorded
        UPDATE,              // matched master row is changed (conflict resolved to the source / newest / manual)
        SKIP_DUPLICATE,      // identical row already in master (or loaded earlier in this run)
        SKIP_KEEP_MASTER,    // conflicting row; master value kept
        SKIP_ORPHAN,         // child row whose parent does not exist (only when orphan policy is SKIP)
        UNRESOLVED_CONFLICT  // needs a decision; blocks execution
    }

    public enum Level { BLOCKER, WARNING, INFO }

    public enum PkMode { NONE, KEEP_ONLY, SEQUENCE, MAX_PLUS_ONE }

    public enum OrphanPolicy { FAIL, SKIP }

    public enum ConflictAction { KEEP_MASTER, TAKE_SOURCE, NEWEST, MANUAL }

    public record Issue(Level level, String table, String message) { }

    /**
     * Placeholder inside a row's values: "the value of parentColumn in the master row that source parent (oldKey)
     * ended up as". Resolved by the executor from the key mapping built while loading parents first.
     */
    public record FkRef(String parentTable, String sourceDb, String oldKey, String parentColumn) { }

    public record PlannedRow(
            String sourceDb,
            RowAction action,
            String oldKey,                       // canonical source primary key (or business key when the table has no PK)
            String businessKey,                  // human readable
            Map<String, Object> values,          // master column -> typed value or FkRef
            List<Object> masterPk,               // INSERT/KEEP: the key; MATCHED: key of the master row; null => allocate at run time
            List<String> changedColumns,         // UPDATE only
            String note) { }

    public record TableStep(
            String table,
            List<String> keyColumns,
            List<String> pkColumns,
            List<String> columns,
            PkMode pkMode,
            String sequence,
            long masterRowsAtPlan,
            List<PlannedRow> rows) {

        public long count(RowAction a) {
            return rows.stream().filter(r -> r.action() == a).count();
        }
    }

    public enum AlterKind { CREATE_AUDIT_TABLE, CREATE_TABLE, ADD_COLUMN, WIDEN_COLUMN, CREATE_SEQUENCE, ADD_FOREIGN_KEY, CREATE_INDEX }

    /** Schema change proposed for the master. DDL is auto-committed by Oracle, so each needs explicit approval. */
    public record AlterStatement(String id, AlterKind kind, String table, String sql, String reason, boolean requiredByPlan) { }

    /**
     * @param acknowledgedReferences "TABLE.COLUMN" entries the user confirmed are NOT references, so an undeclared-reference
     *                               blocker on them becomes a warning (see {@link UndeclaredReferenceGuard})
     */
    public record Options(boolean disableTriggers, OrphanPolicy orphanPolicy, Map<String, String> pkSequences,
                          java.util.Set<String> acknowledgedReferences) {

        public Options {
            pkSequences = pkSequences == null ? Map.of() : Map.copyOf(pkSequences);
            acknowledgedReferences = acknowledgedReferences == null ? java.util.Set.of()
                    : java.util.Set.copyOf(acknowledgedReferences.stream().map(s -> s.trim().toUpperCase(java.util.Locale.ROOT))
                            .filter(s -> !s.isEmpty()).toList());
        }

        public Options(boolean disableTriggers, OrphanPolicy orphanPolicy, Map<String, String> pkSequences) {
            this(disableTriggers, orphanPolicy, pkSequences, java.util.Set.of());
        }

        public static Options defaults() {
            return new Options(false, OrphanPolicy.FAIL, Map.of());
        }

        @Override
        public String toString() {   // part of the plan fingerprint: keep it deterministic
            return "Options[disableTriggers=" + disableTriggers + ", orphanPolicy=" + orphanPolicy + ", pkSequences="
                    + new java.util.TreeMap<>(pkSequences) + ", acknowledgedReferences=" + new java.util.TreeSet<>(acknowledgedReferences) + "]";
        }
    }

    public record MergePlan(
            String id,
            Instant createdAt,
            String master,
            List<String> sources,
            Options options,
            List<TableStep> steps,
            List<AlterStatement> alters,
            Map<String, TableDef> effectiveTables,       // master tables as they will look once the proposed alters are applied
            Map<String, List<String>> triggersToDisable, // table -> enabled trigger names (only when options.disableTriggers)
            List<Issue> issues,
            String fingerprint) {

        public boolean hasBlockers() {
            return issues.stream().anyMatch(i -> i.level() == Level.BLOCKER)
                    || steps.stream().anyMatch(s -> s.count(RowAction.UNRESOLVED_CONFLICT) > 0);
        }
    }

    public enum RunStatus { DRY_RUN, SUCCEEDED, FAILED, REFUSED }

    public record ValidationResult(String check, boolean passed, String detail) { }

    public record MergeResult(
            String runId,
            RunStatus status,
            String message,
            Map<String, Map<String, Long>> countsByTable,   // table -> {inserted, remapped, updated, skipped...}
            List<ValidationResult> validations,
            List<String> log) { }
}
