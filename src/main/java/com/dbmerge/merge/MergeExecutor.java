package com.dbmerge.merge;

import static com.dbmerge.util.Sql.columnList;
import static com.dbmerge.util.Sql.qualified;
import static com.dbmerge.util.Sql.quote;

import com.dbmerge.conn.ConnectionSource;
import com.dbmerge.ddl.model.ConstraintDef;
import com.dbmerge.ddl.model.TableDef;
import com.dbmerge.merge.MergeModel.AlterStatement;
import com.dbmerge.merge.MergeModel.FkRef;
import com.dbmerge.merge.MergeModel.MergePlan;
import com.dbmerge.merge.MergeModel.MergeResult;
import com.dbmerge.merge.MergeModel.PkMode;
import com.dbmerge.merge.MergeModel.PlannedRow;
import com.dbmerge.merge.MergeModel.RowAction;
import com.dbmerge.merge.MergeModel.RunStatus;
import com.dbmerge.merge.MergeModel.TableStep;
import com.dbmerge.merge.MergeModel.ValidationResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies a plan to the master, and only the master. All row changes happen in one transaction that is validated before
 * it is committed and rolled back on any error or failed check. Oracle commits DDL immediately, so the approved schema
 * changes run first, each logged; triggers that were disabled are always re-enabled afterwards.
 */
public class MergeExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(MergeExecutor.class);
    private static final int SAMPLE_PER_TABLE = 20;

    private final ConnectionSource connections;

    public MergeExecutor(ConnectionSource connections) {
        this.connections = connections;
    }

    private record Inserted(TableStep step, PlannedRow row, Map<String, Object> pk) { }

    public MergeResult execute(MergePlan plan, Set<String> approvedAlterIds) {
        List<String> log = new ArrayList<>();
        List<ValidationResult> validations = new ArrayList<>();
        String run = plan.id();
        log(log, "Run " + run + " started. Master " + plan.master() + ", sources " + plan.sources() + " (sources are never opened for writing).");

        if (plan.hasBlockers()) return refused(run, "The plan still has blockers.", log);
        for (AlterStatement a : plan.alters()) {
            if (a.requiredByPlan() && !approvedAlterIds.contains(a.id())) {
                return refused(run, "Required schema change not approved: " + a.id(), log);
            }
        }
        for (String id : approvedAlterIds) {
            if (plan.alters().stream().noneMatch(a -> a.id().equals(id))) return refused(run, "Unknown schema change id: " + id, log);
        }

        String schema = connections.schemaOf(plan.master());
        List<String> disabled = new ArrayList<>();
        Connection c = null;
        boolean ddlRan = false;
        try {
            c = connections.openWritable(plan.master());
            // 1. master must still look like it did when the plan was made
            for (TableStep s : plan.steps()) {
                if (!tableExists(c, schema, s.table())) continue;
                long now = count(c, schema, s.table());
                if (now != s.masterRowsAtPlan()) {
                    c.rollback();
                    return refused(run, "Master table " + s.table() + " now has " + now + " rows but had " + s.masterRowsAtPlan()
                            + " when the plan was made. Plan again.", log);
                }
            }
            // 2. approved DDL (auto-committed by Oracle)
            for (AlterStatement a : plan.alters()) {
                if (!approvedAlterIds.contains(a.id())) { log(log, "Schema change not approved, skipped: " + a.id()); continue; }
                log(log, "DDL (commits immediately): " + a.sql().replace('\n', ' '));
                try (Statement st = c.createStatement()) { st.execute(a.sql()); }
                ddlRan = true;
            }
            // 3. triggers
            for (var e : plan.triggersToDisable().entrySet()) {
                for (String trg : e.getValue()) {
                    log(log, "Disabling trigger " + trg + " (will be re-enabled)");
                    try (Statement st = c.createStatement()) { st.execute("ALTER TRIGGER " + qualified(schema, trg) + " DISABLE"); }
                    disabled.add(trg);
                }
            }
            // 4. baseline orphan counts, then the data transaction
            Map<String, Long> baseline = orphanCounts(c, schema, plan);
            Map<String, Map<String, Object>> keyMap = new HashMap<>();
            Map<String, Map<String, Object>> byBusiness = new HashMap<>();
            List<Inserted> inserted = new ArrayList<>();
            Map<String, Map<String, Long>> counts = new LinkedHashMap<>();
            try (PreparedStatement audit = c.prepareStatement("INSERT INTO " + quote(AlterScriptGenerator.AUDIT_TABLE)
                    + " (RUN_ID, SOURCE_DB, TABLE_NAME, OLD_PK, NEW_PK, BUSINESS_KEY, ACTION) VALUES (?,?,?,?,?,?,?)")) {
                for (TableStep step : plan.steps()) {
                    Map<String, Long> tc = new LinkedHashMap<>();
                    counts.put(step.table(), tc);
                    for (PlannedRow r : step.rows()) {
                        Map<String, Object> pk = apply(c, schema, plan.effectiveTables().get(step.table()), step, r, keyMap, byBusiness, inserted);
                        if (pk != null) {
                            keyMap.put(r.sourceDb() + "|" + step.table() + "|" + r.oldKey(), pk);
                            byBusiness.putIfAbsent(step.table() + "|" + r.businessKey(), pk);
                        }
                        tc.merge(r.action().name(), 1L, Long::sum);
                        audit.setString(1, run);
                        audit.setString(2, r.sourceDb());
                        audit.setString(3, step.table());
                        audit.setString(4, ScriptRenderer.oldPk(r.oldKey()));
                        audit.setString(5, pk == null || pk.isEmpty() ? null : joinPk(pk));
                        audit.setString(6, trunc(r.businessKey(), 1000));
                        audit.setString(7, r.action().name());
                        audit.addBatch();
                    }
                    log(log, step.table() + ": " + tc);
                }
                audit.executeBatch();
            }
            // 5. validation inside the open transaction
            validations.addAll(validate(c, schema, plan, baseline, inserted));
            boolean ok = validations.stream().allMatch(ValidationResult::passed);
            if (!ok) {
                c.rollback();
                log(log, "Validation failed - ROLLED BACK. No row changes were kept." + (ddlRan ? " Approved schema changes were already committed by Oracle." : ""));
                return new MergeResult(run, RunStatus.FAILED, "Validation failed; data changes rolled back.", counts, validations, log);
            }
            c.commit();
            log(log, "COMMITTED.");
            return new MergeResult(run, RunStatus.SUCCEEDED, "Merge committed.", counts, validations, log);
        } catch (Exception e) {
            log(log, "ERROR: " + e.getMessage() + " - rolling back.");
            if (c != null) {
                try { c.rollback(); log(log, "ROLLED BACK. No row changes were kept." + (ddlRan ? " Approved schema changes were already committed by Oracle." : "")); }
                catch (SQLException re) { log(log, "Rollback failed: " + re.getMessage()); }
            }
            return new MergeResult(run, RunStatus.FAILED, "Failed and rolled back: " + e.getMessage(), Map.of(), validations, log);
        } finally {
            if (c != null) {
                for (String trg : disabled) {
                    try (Statement st = c.createStatement()) {
                        st.execute("ALTER TRIGGER " + qualified(schema, trg) + " ENABLE");
                        log(log, "Re-enabled trigger " + trg);
                    } catch (SQLException e) {
                        log(log, "COULD NOT RE-ENABLE TRIGGER " + trg + ": " + e.getMessage() + " - enable it manually.");
                    }
                }
                try { c.close(); } catch (SQLException ignored) { /* nothing to do */ }
            }
        }
    }

    // ------------------------------------------------------------------ rows

    /** Applies one planned row; returns the master primary key the row now corresponds to (null if unknown). */
    private Map<String, Object> apply(Connection c, String schema, TableDef def, TableStep step, PlannedRow r, Map<String, Map<String, Object>> keyMap,
                                      Map<String, Map<String, Object>> byBusiness, List<Inserted> inserted) throws SQLException {
        switch (r.action()) {
            case INSERT, INSERT_REMAPPED -> {
                Map<String, Object> vals = resolve(r.values(), keyMap);
                boolean seq = r.action() == RowAction.INSERT_REMAPPED && step.pkMode() == PkMode.SEQUENCE;
                String pkCol = step.pkColumns().size() == 1 ? step.pkColumns().get(0) : null;
                if (seq) vals.put(pkCol, nextVal(c, schema, step.sequence()));
                List<String> cols = new ArrayList<>(vals.keySet());
                List<String> placeholders = new ArrayList<>();
                for (String col : cols) placeholders.add(placeholder(def, col, vals.get(col)));
                String sql = "INSERT INTO " + qualified(schema, step.table()) + " (" + columnList(cols) + ") VALUES ("
                        + String.join(",", placeholders) + ")";
                Map<String, Object> pk = new LinkedHashMap<>();
                try (PreparedStatement ps = pkCol != null ? c.prepareStatement(sql, new String[] {pkCol}) : c.prepareStatement(sql)) {
                    bind(ps, def, cols, vals);
                    ps.executeUpdate();
                    if (pkCol != null) {
                        try (ResultSet gk = ps.getGeneratedKeys()) {
                            if (gk.next()) pk.put(pkCol, vals.get(pkCol) instanceof String ? gk.getString(1) : gk.getBigDecimal(1));
                        }
                    }
                }
                if (pk.isEmpty()) for (String k : step.pkColumns()) pk.put(k, vals.get(k));
                inserted.add(new Inserted(step, r, pk));
                return pk;
            }
            case UPDATE -> {
                Map<String, Object> vals = resolve(r.values(), keyMap);
                List<String> cols = new ArrayList<>(vals.keySet());
                StringBuilder sql = new StringBuilder("UPDATE " + qualified(schema, step.table()) + " SET ");
                for (int i = 0; i < cols.size(); i++) {
                    sql.append(i > 0 ? ", " : "").append(quote(cols.get(i))).append(" = ").append(placeholder(def, cols.get(i), vals.get(cols.get(i))));
                }
                sql.append(" WHERE ");
                for (int i = 0; i < step.pkColumns().size(); i++) sql.append(i > 0 ? " AND " : "").append(quote(step.pkColumns().get(i))).append(" = ?");
                try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                    int n = bind(ps, def, cols, vals);
                    for (int i = 0; i < step.pkColumns().size(); i++) ps.setObject(n + i, r.masterPk().get(i));
                    int updated = ps.executeUpdate();
                    if (updated != 1) throw new SQLException("UPDATE of " + step.table() + " " + r.businessKey() + " changed " + updated + " rows, expected 1");
                }
                return pkOf(step, r);
            }
            default -> {
                Map<String, Object> pk = pkOf(step, r);
                if (pk == null || pk.isEmpty()) pk = byBusiness.get(step.table() + "|" + r.businessKey());
                return pk;
            }
        }
    }

    private static Map<String, Object> pkOf(TableStep step, PlannedRow r) {
        if (r.masterPk() == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < step.pkColumns().size() && i < r.masterPk().size(); i++) m.put(step.pkColumns().get(i), r.masterPk().get(i));
        return m;
    }

    private static Map<String, Object> resolve(Map<String, Object> values, Map<String, Map<String, Object>> keyMap) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : values.entrySet()) {
            Object v = e.getValue();
            if (v instanceof FkRef f) {
                Map<String, Object> target = keyMap.get(f.sourceDb() + "|" + f.parentTable() + "|" + f.oldKey());
                if (target == null || !target.containsKey(f.parentColumn()) || target.get(f.parentColumn()) == null) {
                    throw new IllegalStateException("Parent key not available for " + f.parentTable() + " " + f.oldKey() + " from " + f.sourceDb());
                }
                v = target.get(f.parentColumn());
            }
            out.put(e.getKey(), v);
        }
        return out;
    }

    /** "?" for a bound value, or EMPTY_CLOB()/EMPTY_BLOB() for an empty LOB, which a bind would turn into NULL. */
    private static String placeholder(TableDef def, String col, Object v) {
        String empty = EmptyLobs.expression(def, col, v);
        return empty != null ? empty : "?";
    }

    /** Binds every value that got a "?" from {@link #placeholder}; returns the next parameter index. */
    private static int bind(PreparedStatement ps, TableDef def, List<String> cols, Map<String, Object> vals) throws SQLException {
        int i = 1;
        for (String col : cols) {
            Object v = vals.get(col);
            if (EmptyLobs.expression(def, col, v) != null) continue;
            if (v == null) ps.setNull(i++, Types.NULL);
            else ps.setObject(i++, v);
        }
        return i;
    }

    private static Object nextVal(Connection c, String schema, String seq) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT " + qualified(schema, seq) + ".NEXTVAL FROM dual")) {
            rs.next();
            return rs.getBigDecimal(1);
        }
    }

    // ------------------------------------------------------------ validation

    private List<ValidationResult> validate(Connection c, String schema, MergePlan plan, Map<String, Long> baseline, List<Inserted> inserted)
            throws SQLException {
        List<ValidationResult> out = new ArrayList<>();
        for (TableStep s : plan.steps()) {
            long expected = s.masterRowsAtPlan() + s.count(RowAction.INSERT) + s.count(RowAction.INSERT_REMAPPED);
            long actual = count(c, schema, s.table());
            long fromSources = s.rows().size();
            long dup = s.count(RowAction.SKIP_DUPLICATE) + s.count(RowAction.SKIP_KEEP_MASTER) + s.count(RowAction.UPDATE) + s.count(RowAction.SKIP_ORPHAN);
            out.add(new ValidationResult("row count " + s.table(), actual == expected,
                    "master " + s.masterRowsAtPlan() + " + source rows " + fromSources + " - matched/duplicate/skipped " + dup + " = expected " + expected + ", found " + actual));
        }
        Map<String, Long> after = orphanCounts(c, schema, plan);
        for (var e : after.entrySet()) {
            long before = baseline.getOrDefault(e.getKey(), 0L);
            out.add(new ValidationResult("orphan check " + e.getKey(), e.getValue() <= before,
                    e.getValue() + " orphan row(s) after, " + before + " before the merge"));
        }
        Map<String, Integer> sampled = new HashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        Map<String, Integer> bad = new HashMap<>();
        Map<String, String> firstBad = new HashMap<>();
        for (Inserted in : inserted) {
            String t = in.step().table();
            int n = seen.merge(t, 1, Integer::sum);
            int stride = Math.max(1, (int) Math.ceil(in.step().rows().size() / (double) SAMPLE_PER_TABLE));
            if ((n - 1) % stride != 0) continue;
            sampled.merge(t, 1, Integer::sum);
            String diff = compareRow(c, schema, in);
            if (diff != null) { bad.merge(t, 1, Integer::sum); firstBad.putIfAbsent(t, in.row().businessKey() + ": " + diff); }
        }
        for (var e : sampled.entrySet()) {
            int b = bad.getOrDefault(e.getKey(), 0);
            out.add(new ValidationResult("sample compare " + e.getKey(), b == 0,
                    e.getValue() + " inserted row(s) sampled, " + b + " differ from the source" + (b > 0 ? " (" + firstBad.get(e.getKey()) + ")" : "")));
        }
        return out;
    }

    private String compareRow(Connection c, String schema, Inserted in) throws SQLException {
        List<String> pkCols = in.step().pkColumns();
        if (pkCols.isEmpty()) return null;
        List<String> cols = new ArrayList<>();
        for (var e : in.row().values().entrySet()) if (!(e.getValue() instanceof FkRef) && !pkCols.contains(e.getKey())) cols.add(e.getKey());
        if (cols.isEmpty()) return null;
        StringBuilder sql = new StringBuilder("SELECT " + columnList(cols) + " FROM " + qualified(schema, in.step().table()) + " WHERE ");
        for (int i = 0; i < pkCols.size(); i++) sql.append(i > 0 ? " AND " : "").append(quote(pkCols.get(i))).append(" = ?");
        try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
            for (int i = 0; i < pkCols.size(); i++) ps.setObject(i + 1, in.pk().get(pkCols.get(i)));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return "row not found after insert";
                ResultSetMetaDataHolder md = new ResultSetMetaDataHolder(rs);
                for (int i = 0; i < cols.size(); i++) {
                    Object actual = JdbcMergeData.read(rs, i + 1, md.type(i + 1));
                    if (!Canon.sameValue(actual, in.row().values().get(cols.get(i)))) return "column " + cols.get(i) + " differs";
                }
            }
        }
        return null;
    }

    private record ResultSetMetaDataHolder(ResultSet rs) {
        int type(int i) throws SQLException { return rs.getMetaData().getColumnType(i); }
    }

    private Map<String, Long> orphanCounts(Connection c, String schema, MergePlan plan) throws SQLException {
        Map<String, Long> out = new LinkedHashMap<>();
        for (TableStep s : plan.steps()) {
            TableDef def = plan.effectiveTables().get(s.table());
            if (def == null || !tableExists(c, schema, s.table())) continue;
            for (ConstraintDef fk : def.constraints()) {
                if (!fk.type().equals("R") || !plan.effectiveTables().containsKey(fk.refTable())) continue;
                if (!tableExists(c, schema, fk.refTable())) continue;
                StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM " + qualified(schema, s.table()) + " c WHERE ");
                for (int i = 0; i < fk.columns().size(); i++) sql.append(i > 0 ? " AND " : "").append("c.").append(quote(fk.columns().get(i))).append(" IS NOT NULL");
                sql.append(" AND NOT EXISTS (SELECT 1 FROM ").append(qualified(schema, fk.refTable())).append(" p WHERE ");
                for (int i = 0; i < fk.columns().size(); i++) {
                    sql.append(i > 0 ? " AND " : "").append("p.").append(quote(fk.refColumns().get(i))).append(" = c.").append(quote(fk.columns().get(i)));
                }
                sql.append(')');
                try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql.toString())) {
                    rs.next();
                    out.put(s.table() + "." + fk.name(), rs.getLong(1));
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- misc

    private static boolean tableExists(Connection c, String schema, String table) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM all_tables WHERE owner = ? AND table_name = ?")) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1) > 0; }
        }
    }

    private static long count(Connection c, String schema, String table) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + qualified(schema, table))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String joinPk(Map<String, Object> pk) {
        List<String> parts = new ArrayList<>();
        for (Object v : pk.values()) parts.add(Canon.of(v));
        return String.join("|", parts);
    }

    private static String trunc(String s, int n) {
        return s == null || s.length() <= n ? s : s.substring(0, n);
    }

    private static MergeResult refused(String run, String why, List<String> log) {
        log(log, "REFUSED: " + why);
        return new MergeResult(run, RunStatus.REFUSED, why, Map.of(), List.of(), log);
    }

    private static void log(List<String> log, String msg) {
        String line = java.time.Instant.now() + " " + msg;
        log.add(line);
        LOG.info("{}", msg);
    }
}
