package com.dbmerge.merge;

import static com.dbmerge.util.Sql.columnList;
import static com.dbmerge.util.Sql.literal;
import static com.dbmerge.util.Sql.quote;

import com.dbmerge.merge.MergeModel.AlterStatement;
import com.dbmerge.merge.MergeModel.FkRef;
import com.dbmerge.merge.MergeModel.MergePlan;
import com.dbmerge.merge.MergeModel.PkMode;
import com.dbmerge.merge.MergeModel.PlannedRow;
import com.dbmerge.merge.MergeModel.RowAction;
import com.dbmerge.merge.MergeModel.TableStep;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders the review script for a plan. It is for reading and, if wanted, running by hand in SQL*Plus/SQLcl; the app's
 * own executor does not run this text but the same plan through JDBC. The script contains no passwords.
 */
public final class ScriptRenderer {

    public static final String BACKUP_REMINDER = "Before running this against the master, take a Data Pump backup, for example: "
            + "expdp <user>@<service> schemas=<MASTER_SCHEMA> directory=DATA_PUMP_DIR dumpfile=master_before_merge_%U.dmp logfile=master_before_merge.log";

    private ScriptRenderer() { }

    public static String render(MergePlan plan, Set<String> approvedAlterIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("-- ============================================================================\n");
        sb.append("-- Merge script (REVIEW COPY) run ").append(plan.id()).append(" - master ").append(plan.master())
                .append(", sources ").append(String.join(", ", plan.sources())).append('\n');
        sb.append("-- Generated ").append(plan.createdAt()).append(". Nothing has been changed by generating this script.\n");
        sb.append("-- ").append(BACKUP_REMINDER).append('\n');
        sb.append("-- Source databases are only ever read. Only the master is written.\n");
        sb.append("-- ============================================================================\n\n");
        sb.append("WHENEVER SQLERROR EXIT FAILURE ROLLBACK\nSET DEFINE OFF\n\n");

        if (!plan.alters().isEmpty()) {
            sb.append("-- ---- Schema changes (Oracle commits DDL immediately; each needs approval) ----\n");
            for (AlterStatement a : plan.alters()) {
                boolean approved = approvedAlterIds != null && approvedAlterIds.contains(a.id());
                sb.append("-- [").append(a.id()).append("] ").append(a.reason()).append(a.requiredByPlan() ? " (required)" : "")
                        .append(approved ? " [APPROVED]" : " [not approved]").append('\n');
                sb.append(a.sql()).append(";\n\n");
            }
        }
        if (!plan.triggersToDisable().isEmpty()) {
            sb.append("-- ---- Triggers disabled during the load and re-enabled afterwards ----\n");
            plan.triggersToDisable().forEach((t, names) -> names.forEach(n -> sb.append("ALTER TRIGGER ").append(quote(n)).append(" DISABLE;\n")));
            sb.append('\n');
        }

        sb.append("-- ---- Data (one transaction) ----\n");
        for (TableStep step : plan.steps()) {
            long work = step.rows().stream().filter(r -> r.action() == RowAction.INSERT || r.action() == RowAction.INSERT_REMAPPED
                    || r.action() == RowAction.UPDATE).count();
            sb.append("\n-- ").append(step.table()).append(": ")
                    .append(step.count(RowAction.INSERT)).append(" insert, ").append(step.count(RowAction.INSERT_REMAPPED)).append(" re-keyed, ")
                    .append(step.count(RowAction.UPDATE)).append(" update, ").append(step.count(RowAction.SKIP_DUPLICATE)).append(" duplicate, ")
                    .append(step.count(RowAction.SKIP_KEEP_MASTER)).append(" keep master, ").append(step.count(RowAction.SKIP_ORPHAN)).append(" orphan skipped, ")
                    .append(step.count(RowAction.UNRESOLVED_CONFLICT)).append(" unresolved\n");
            if (work == 0 && step.rows().isEmpty()) continue;
            for (PlannedRow r : step.rows()) renderRow(sb, plan, step, r);
        }
        sb.append("\n-- ---- Checks (run before COMMIT) ----\n");
        for (TableStep step : plan.steps()) {
            long inserts = step.count(RowAction.INSERT) + step.count(RowAction.INSERT_REMAPPED);
            sb.append("-- expect ").append(quote(step.table())).append(" to have ").append(step.masterRowsAtPlan() + inserts).append(" rows\n");
            sb.append("SELECT COUNT(*) AS ").append(quote(step.table() + "_ROWS")).append(" FROM ").append(quote(step.table())).append(";\n");
        }
        sb.append("\n-- Review the checks, then COMMIT or ROLLBACK.\n-- COMMIT;\nROLLBACK;\n");
        if (!plan.triggersToDisable().isEmpty()) {
            sb.append("\n-- ---- Re-enable triggers ----\n");
            plan.triggersToDisable().forEach((t, names) -> names.forEach(n -> sb.append("ALTER TRIGGER ").append(quote(n)).append(" ENABLE;\n")));
        }
        return sb.toString();
    }

    private static void renderRow(StringBuilder sb, MergePlan plan, TableStep step, PlannedRow r) {
        String t = quote(step.table());
        switch (r.action()) {
            case INSERT, INSERT_REMAPPED -> {
                List<String> cols = new ArrayList<>(r.values().keySet());
                List<String> vals = new ArrayList<>();
                boolean seq = r.action() == RowAction.INSERT_REMAPPED && step.pkMode() == PkMode.SEQUENCE;
                for (String c : cols) {
                    if (seq && c.equals(step.pkColumns().get(0))) vals.add(quote(step.sequence()) + ".NEXTVAL");
                    else vals.add(value(plan, step, c, r.values().get(c)));
                }
                sb.append("-- ").append(r.sourceDb()).append(' ').append(r.businessKey()).append(r.note() == null ? "" : " - " + r.note()).append('\n');
                sb.append("INSERT INTO ").append(t).append(" (").append(columnList(cols)).append(") VALUES (").append(String.join(", ", vals)).append(");\n");
                String newPk = r.action() == RowAction.INSERT_REMAPPED && seq ? quote(step.sequence()) + ".CURRVAL"
                        : r.masterPk() == null ? "NULL" : toVal(r.masterPk().get(0));
                sb.append(audit(plan, step, r, newPk));
            }
            case UPDATE -> {
                List<String> sets = new ArrayList<>();
                for (var e : r.values().entrySet()) sets.add(quote(e.getKey()) + " = " + value(plan, step, e.getKey(), e.getValue()));
                sb.append("-- ").append(r.sourceDb()).append(' ').append(r.businessKey()).append(" - ").append(r.note()).append('\n');
                sb.append("UPDATE ").append(t).append(" SET ").append(String.join(", ", sets)).append(" WHERE ").append(where(step, r)).append(";\n");
                sb.append(audit(plan, step, r, r.masterPk() == null ? "NULL" : toVal(r.masterPk().get(0))));
            }
            default -> sb.append("-- ").append(r.action()).append(": ").append(r.sourceDb()).append(' ').append(r.businessKey())
                    .append(r.note() == null ? "" : " - " + r.note()).append('\n');
        }
    }

    private static String where(TableStep step, PlannedRow r) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < step.pkColumns().size(); i++) parts.add(quote(step.pkColumns().get(i)) + " = " + toVal(r.masterPk().get(i)));
        return String.join(" AND ", parts);
    }

    private static String audit(MergePlan plan, TableStep step, PlannedRow r, String newPkExpr) {
        return "INSERT INTO " + quote(AlterScriptGenerator.AUDIT_TABLE)
                + " (RUN_ID, SOURCE_DB, TABLE_NAME, OLD_PK, NEW_PK, BUSINESS_KEY, ACTION) VALUES ("
                + literal(plan.id()) + ", " + literal(r.sourceDb()) + ", " + literal(step.table()) + ", " + literal(oldPk(r.oldKey()))
                + ", " + (newPkExpr.equals("NULL") ? "NULL" : "TO_CHAR(" + newPkExpr + ")") + ", " + literal(r.businessKey()) + ", " + literal(r.action().name()) + ");\n";
    }

    static String oldPk(String canonicalKey) {
        return canonicalKey.replace('\u0001', '|');
    }

    private static String value(MergePlan plan, TableStep step, String column, Object v) {
        String empty = EmptyLobs.expression(plan.effectiveTables().get(step.table()), column, v);
        if (empty != null) return empty;    // '' / HEXTORAW('') would be NULL in Oracle
        if (v instanceof FkRef f) {
            return "(SELECT TO_NUMBER(NEW_PK) FROM " + quote(AlterScriptGenerator.AUDIT_TABLE) + " WHERE RUN_ID = " + literal(plan.id())
                    + " AND SOURCE_DB = " + literal(f.sourceDb()) + " AND TABLE_NAME = " + literal(f.parentTable())
                    + " AND OLD_PK = " + literal(oldPk(f.oldKey())) + ")";
        }
        return toVal(v);
    }

    static String toVal(Object v) {
        if (v == null) return "NULL";
        if (v instanceof BigDecimal b) return b.toPlainString();
        if (v instanceof Number n) return n.toString();
        if (v instanceof Timestamp t) return "TIMESTAMP '" + t.toLocalDateTime().toString().replace('T', ' ') + "'";
        if (v instanceof OffsetDateTime o) return "TIMESTAMP '" + o.toLocalDateTime().toString().replace('T', ' ') + " " + o.getOffset().getId().replace("Z", "+00:00") + "'";
        if (v instanceof byte[] b) return "HEXTORAW('" + HexFormat.of().formatHex(b) + "')";
        return literal(v.toString());
    }

    /** Short plain-text summary used in logs and the text report. */
    public static Map<String, Map<String, Long>> counts(MergePlan plan) {
        Map<String, Map<String, Long>> out = new java.util.LinkedHashMap<>();
        for (TableStep s : plan.steps()) {
            Map<String, Long> m = new java.util.LinkedHashMap<>();
            for (RowAction a : RowAction.values()) {
                long c = s.count(a);
                if (c > 0) m.put(a.name(), c);
            }
            out.put(s.table(), m);
        }
        return out;
    }
}
