package com.dbmerge.merge;

import com.dbmerge.ddl.model.ConstraintDef;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.TableDef;
import com.dbmerge.merge.MergeModel.Issue;
import com.dbmerge.merge.MergeModel.Level;
import com.dbmerge.merge.MergeModel.PlannedRow;
import com.dbmerge.merge.MergeModel.RowAction;
import com.dbmerge.merge.MergeModel.TableStep;
import com.dbmerge.refs.RefModel.Candidate;
import com.dbmerge.refs.RefModel.Kind;
import com.dbmerge.refs.ReferenceDetector;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refuses a merge that would silently break an undeclared reference.
 *
 * <p>The planner rewrites a child's key only through declared foreign keys. For every column the
 * {@link ReferenceDetector} considers a likely reference, this looks at the finished plan: if a row being copied
 * from a source holds a value equal to the <em>source</em> key of a parent row whose key changes in the master
 * (re-keyed, or matched to a master row with a different key), the copied value would point at the wrong row or at
 * nothing. That is a BLOCKER, listing the column and examples. The check is on actual planned values, so a schema full
 * of undeclared references does not block a merge in which no referenced key changes.
 *
 * <p>Ways forward, named in the message: declare the foreign key on the master (the planner then rewrites the column),
 * leave the child table out of the merge, or acknowledge "TABLE.COLUMN" as not a reference, which turns the blocker into
 * a warning. Type + ID pairs can never be checked or rewritten; they produce a warning when keys change.
 */
final class UndeclaredReferenceGuard {

    static final int EXAMPLES = 3;

    private UndeclaredReferenceGuard() { }

    static void check(Map<String, TableDef> effective, List<TableStep> steps, Set<String> acknowledged, List<Issue> issues) {
        Map<String, TableStep> byTable = new HashMap<>();
        for (TableStep s : steps) byTable.put(s.table(), s);

        // source db -> table -> source keys whose master key differs (single-column primary keys only)
        Map<String, Map<String, Set<String>>> changed = new HashMap<>();
        Map<String, Integer> changedPerTable = new LinkedHashMap<>();
        for (TableStep s : steps) {
            if (s.pkColumns().size() != 1) continue;
            for (PlannedRow r : s.rows()) {
                if (!keyChanges(r)) continue;
                changed.computeIfAbsent(r.sourceDb(), k -> new HashMap<>()).computeIfAbsent(s.table(), k -> new HashSet<>()).add(r.oldKey());
                changedPerTable.merge(s.table(), 1, Integer::sum);
            }
        }
        if (changed.isEmpty()) return;

        SchemaModel model = new SchemaModel("master", "", effective, Map.of(), Map.of(), List.of(), Instant.now());
        List<Candidate> candidates = new ReferenceDetector().detect(model);
        Set<String> declared = declaredColumns(effective);

        for (Candidate c : candidates) {
            if (declared.contains(c.qualifiedColumn())) continue;
            TableStep child = byTable.get(c.table());
            if (child == null) continue;
            if (c.kind() == Kind.POLYMORPHIC) {
                if (copiesColumn(child, c.column())) {
                    issues.add(new Issue(Level.WARNING, c.table(), c.qualifiedColumn() + " is a type + ID reference (" + c.rule()
                            + "). The merge cannot check or rewrite it, and rows in " + String.join(", ", changedPerTable.keySet())
                            + " get new keys in this run. Verify these rows after the merge."));
                }
                continue;
            }
            if (c.kind() != Kind.LIKELY_FK && c.kind() != Kind.SHARED_PRIMARY_KEY || c.parentColumn() == null) continue;

            boolean childPk = child.pkColumns().equals(List.of(c.column()));
            Set<PlannedRow> affected = new LinkedHashSet<>();
            Map<String, Integer> perParent = new LinkedHashMap<>();
            List<String> examples = new ArrayList<>();
            for (String parent : c.parentTables()) {
                TableStep ps = byTable.get(parent);
                if (ps == null || !ps.pkColumns().equals(List.of(c.parentColumn()))) continue;
                for (PlannedRow r : child.rows()) {
                    if (!writes(r)) continue;
                    Set<String> keys = changed.getOrDefault(r.sourceDb(), Map.of()).get(parent);
                    if (keys == null) continue;
                    String v = value(r, c.column(), childPk);
                    if (v == null || !keys.contains(v)) continue;
                    perParent.merge(parent, 1, Integer::sum);
                    if (affected.add(r) && examples.size() < EXAMPLES) {
                        examples.add(r.sourceDb() + " " + r.businessKey() + " (" + c.column() + "=" + v + ")");
                    }
                }
            }
            if (affected.isEmpty()) continue;

            String what = affected.size() + " row(s) copied into " + c.table() + " have a " + c.column() + " value that is the old key of a "
                    + String.join(" / ", perParent.keySet()) + " row whose key changes in this merge (e.g. " + String.join("; ", examples) + "). "
                    + c.qualifiedColumn() + " is not a declared foreign key (" + c.rule() + "), so the old value would be copied and point at the wrong row or none.";
            if (acknowledged.contains(c.qualifiedColumn())) {
                issues.add(new Issue(Level.WARNING, c.table(), what + " Acknowledged as not a reference."));
            } else {
                issues.add(new Issue(Level.BLOCKER, c.table(), what + " To continue: declare the foreign key on the master so the merge rewrites it, "
                        + "leave " + c.table() + " out of this merge, or acknowledge " + c.qualifiedColumn() + " as not a reference."));
            }
        }
    }

    /** The row's master key differs from its source key, or is only assigned at run time (sequence). */
    static boolean keyChanges(PlannedRow r) {
        return switch (r.action()) {
            case INSERT_REMAPPED -> true;
            case SKIP_DUPLICATE, SKIP_KEEP_MASTER, UPDATE -> r.masterPk() == null || r.masterPk().isEmpty()
                    || !r.oldKey().equals(Canon.of(r.masterPk().get(0)));
            default -> false;
        };
    }

    private static boolean writes(PlannedRow r) {
        return r.action() == RowAction.INSERT || r.action() == RowAction.INSERT_REMAPPED || r.action() == RowAction.UPDATE;
    }

    private static boolean copiesColumn(TableStep child, String column) {
        return child.rows().stream().anyMatch(r -> writes(r) && r.values().get(column) != null);
    }

    /** The source value of the column. For a child whose own PK is the reference, the planned value may already be re-keyed. */
    private static String value(PlannedRow r, String column, boolean childPk) {
        if (childPk && r.action() != RowAction.UPDATE) return r.oldKey();
        Object v = r.values().get(column);
        return v instanceof MergeModel.FkRef ? null : Canon.of(v);
    }

    private static Set<String> declaredColumns(Map<String, TableDef> tables) {
        Set<String> out = new HashSet<>();
        for (TableDef t : tables.values()) {
            for (ConstraintDef c : t.constraints()) if (c.type().equals("R")) for (String col : c.columns()) out.add(t.name() + "." + col);
        }
        return out;
    }
}
