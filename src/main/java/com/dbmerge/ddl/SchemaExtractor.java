package com.dbmerge.ddl;

import com.dbmerge.conn.ConnectionSource;
import com.dbmerge.ddl.model.ColumnDef;
import com.dbmerge.ddl.model.ConstraintDef;
import com.dbmerge.ddl.model.IndexDef;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.SequenceDef;
import com.dbmerge.ddl.model.TableDef;
import com.dbmerge.ddl.model.TriggerDef;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Reads tables, columns, PK/FK/unique/check constraints, indexes, sequences and triggers for one schema from the
 * ALL_* dictionary views, plus DBMS_METADATA DDL text for display. Everything runs in a READ ONLY transaction.
 * Requires Oracle 12.2+ (ALL_CONSTRAINTS.SEARCH_CONDITION_VC).
 */
public class SchemaExtractor {

    private static final Pattern SYSTEM_NAME = Pattern.compile("^(SYS_C|SYS_IL|SYS_IOT|SYS_NC)\\d+.*$|^SYS_.*$");

    private final ConnectionSource connections;

    public SchemaExtractor(ConnectionSource connections) {
        this.connections = connections;
    }

    public SchemaModel extract(String nickname, boolean includeDdl) throws SQLException {
        String owner = connections.schemaOf(nickname);
        List<String> warnings = new ArrayList<>();
        try (Connection c = connections.openReadOnly(nickname)) {
            List<String> tableNames = loadTableNames(c, owner);
            Map<String, Map<String, ColumnDef>> columns = loadColumns(c, owner);
            Map<String, List<ConstraintDef>> constraints = loadConstraints(c, owner);
            Map<String, List<IndexDef>> indexes = loadIndexes(c, owner, constraints);
            Map<String, List<TriggerDef>> triggers = loadTriggers(c, owner);
            Map<String, SequenceDef> sequences = loadSequences(c, owner);

            Map<String, TableDef> tables = new TreeMap<>();
            for (String t : tableNames) {
                tables.put(t, new TableDef(t,
                        columns.getOrDefault(t, new LinkedHashMap<>()),
                        constraints.getOrDefault(t, List.of()),
                        indexes.getOrDefault(t, List.of()),
                        triggers.getOrDefault(t, List.of())));
            }
            Map<String, String> ddl = new LinkedHashMap<>();
            if (includeDdl) loadDdl(c, owner, tables, sequences, ddl, warnings);
            return new SchemaModel(nickname, owner, tables, new TreeMap<>(sequences), ddl, warnings, Instant.now());
        }
    }

    // ----------------------------------------------------------------- tables

    private List<String> loadTableNames(Connection c, String owner) throws SQLException {
        String sql = "SELECT t.table_name FROM all_tables t WHERE t.owner = ? "
                + "AND t.nested = 'NO' AND t.secondary = 'N' AND t.dropped = 'NO' "
                + "AND (t.iot_type IS NULL OR t.iot_type = 'IOT') "
                + "AND t.table_name NOT LIKE 'BIN$%' "
                + "AND NOT EXISTS (SELECT 1 FROM all_mviews m WHERE m.owner = t.owner AND m.mview_name = t.table_name) "
                + "ORDER BY t.table_name";
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(rs.getString(1));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- columns

    private Map<String, Map<String, ColumnDef>> loadColumns(Connection c, String owner) throws SQLException {
        // DATA_DEFAULT is a LONG column: it must be read last and in select order.
        String sql = "SELECT table_name, column_name, column_id, data_type, data_length, data_precision, data_scale, "
                + "char_length, char_used, nullable, identity_column, virtual_column, data_default "
                + "FROM all_tab_cols WHERE owner = ? AND hidden_column = 'NO' ORDER BY table_name, column_id";
        Map<String, Map<String, ColumnDef>> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    String name = rs.getString(2);
                    int pos = rs.getInt(3);
                    String type = rs.getString(4);
                    Integer dataLength = nullableInt(rs, 5);
                    Integer precision = nullableInt(rs, 6);
                    Integer scale = nullableInt(rs, 7);
                    Integer charLength = nullableInt(rs, 8);
                    String charUsed = rs.getString(9);
                    boolean nullable = "Y".equals(rs.getString(10));
                    boolean identity = "YES".equals(rs.getString(11));
                    boolean virtual = "YES".equals(rs.getString(12));
                    String dflt = rs.getString(13);

                    boolean charType = type.startsWith("VARCHAR") || type.startsWith("NVARCHAR")
                            || type.equals("CHAR") || type.equals("NCHAR");
                    Integer length = charType ? charLength : dataLength;
                    // Identity columns default to a system sequence whose name differs per database: ignore it.
                    String normalizedDefault = identity ? null : normalizeDefault(dflt);
                    out.computeIfAbsent(table, k -> new LinkedHashMap<>())
                            .put(name, new ColumnDef(name, pos, type, length, precision, scale, charUsed,
                                    nullable, normalizedDefault, identity, virtual));
                }
            }
        }
        return out;
    }

    private static String normalizeDefault(String d) {
        if (d == null) return null;
        String n = d.trim().replaceAll("\\s+", " ");
        return n.isEmpty() || n.equalsIgnoreCase("NULL") ? null : n;
    }

    // ------------------------------------------------------------ constraints

    private record RawCons(String table, String name, String type, String cond, String rOwner, String rName,
                           String deleteRule, String status, boolean generated) { }

    private Map<String, List<ConstraintDef>> loadConstraints(Connection c, String owner) throws SQLException {
        String sql = "SELECT table_name, constraint_name, constraint_type, search_condition_vc, r_owner, "
                + "r_constraint_name, delete_rule, status, generated FROM all_constraints "
                + "WHERE owner = ? AND constraint_type IN ('P','U','R','C') AND table_name NOT LIKE 'BIN$%' "
                + "ORDER BY table_name, constraint_name";
        List<RawCons> raw = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    raw.add(new RawCons(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                            "GENERATED NAME".equals(rs.getString(9))));
                }
            }
        }
        Map<String, List<String>> consCols = loadConsColumns(c, owner);
        Map<String, String> consTable = new LinkedHashMap<>();
        for (RawCons r : raw) consTable.put(r.name(), r.table());

        Map<String, List<ConstraintDef>> out = new LinkedHashMap<>();
        for (RawCons r : raw) {
            List<String> cols = consCols.getOrDefault(r.name(), List.of());
            ConstraintDef def;
            if (r.type().equals("R")) {
                String refTable;
                List<String> refCols;
                if (owner.equals(r.rOwner())) {
                    refTable = consTable.get(r.rName());
                    refCols = consCols.getOrDefault(r.rName(), List.of());
                } else {
                    String[] ref = lookupForeignReference(c, r.rOwner(), r.rName());
                    refTable = r.rOwner() + "." + ref[0];
                    refCols = ref[1].isEmpty() ? List.of() : List.of(ref[1].split(","));
                }
                def = new ConstraintDef(r.name(), "R", cols, null, refTable, refCols, r.deleteRule(), r.status(), r.generated());
            } else {
                def = new ConstraintDef(r.name(), r.type(), cols, r.type().equals("C") ? r.cond() : null,
                        null, List.of(), null, r.status(), r.generated());
            }
            out.computeIfAbsent(r.table(), k -> new ArrayList<>()).add(def);
        }
        return out;
    }

    private Map<String, List<String>> loadConsColumns(Connection c, String owner) throws SQLException {
        String sql = "SELECT constraint_name, column_name FROM all_cons_columns WHERE owner = ? "
                + "ORDER BY constraint_name, position";
        Map<String, List<String>> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
            }
        }
        return out;
    }

    /** Cross-schema foreign key target: returns {table, "COL1,COL2"}. */
    private String[] lookupForeignReference(Connection c, String rOwner, String rName) throws SQLException {
        String sql = "SELECT cc.table_name, cc.column_name FROM all_cons_columns cc "
                + "WHERE cc.owner = ? AND cc.constraint_name = ? ORDER BY cc.position";
        String table = "?";
        List<String> cols = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, rOwner);
            ps.setString(2, rName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { table = rs.getString(1); cols.add(rs.getString(2)); }
            }
        }
        return new String[] {table, String.join(",", cols)};
    }

    // ---------------------------------------------------------------- indexes

    private Map<String, List<IndexDef>> loadIndexes(Connection c, String owner,
                                                    Map<String, List<ConstraintDef>> constraints) throws SQLException {
        Map<String, List<String>> idxCols = new LinkedHashMap<>();
        String colSql = "SELECT index_name, column_name, descend FROM all_ind_columns "
                + "WHERE index_owner = ? ORDER BY index_name, column_position";
        try (PreparedStatement ps = c.prepareStatement(colSql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String col = rs.getString(2);
                    if (col != null && col.startsWith("SYS_NC")) col = "<expression>";
                    if ("DESC".equals(rs.getString(3))) col += " DESC";
                    idxCols.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(col);
                }
            }
        }
        String sql = "SELECT table_name, index_name, uniqueness, index_type FROM all_indexes "
                + "WHERE owner = ? AND table_owner = ? AND index_type NOT IN ('LOB') AND index_name NOT LIKE 'BIN$%' "
                + "ORDER BY table_name, index_name";
        Map<String, List<IndexDef>> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner);
            ps.setString(2, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    String name = rs.getString(2);
                    boolean unique = "UNIQUE".equals(rs.getString(3));
                    List<String> cols = idxCols.getOrDefault(name, List.of());
                    // Skip the implicit index that backs a PK/unique constraint: the constraint already covers it.
                    if (backsConstraint(constraints.get(table), name, unique, cols)) continue;
                    out.computeIfAbsent(table, k -> new ArrayList<>())
                            .add(new IndexDef(name, unique, rs.getString(4), cols, SYSTEM_NAME.matcher(name).matches()));
                }
            }
        }
        return out;
    }

    private static boolean backsConstraint(List<ConstraintDef> cons, String indexName, boolean unique, List<String> cols) {
        if (cons == null || !unique) return false;
        for (ConstraintDef k : cons) {
            if (!(k.type().equals("P") || k.type().equals("U"))) continue;
            if (k.name().equals(indexName) || k.columns().equals(cols)) return true;
        }
        return false;
    }

    // --------------------------------------------------------------- triggers

    private Map<String, List<TriggerDef>> loadTriggers(Connection c, String owner) throws SQLException {
        // TRIGGER_BODY is a LONG column: read last.
        String sql = "SELECT table_name, trigger_name, trigger_type, triggering_event, status, when_clause, trigger_body "
                + "FROM all_triggers WHERE owner = ? AND base_object_type = 'TABLE' ORDER BY table_name, trigger_name";
        Map<String, List<TriggerDef>> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    String name = rs.getString(2);
                    String type = rs.getString(3);
                    String event = rs.getString(4);
                    String status = rs.getString(5);
                    String when = rs.getString(6);
                    String body = TriggerDef.normalizeBody(rs.getString(7));
                    out.computeIfAbsent(table, k -> new ArrayList<>())
                            .add(new TriggerDef(name, type == null ? "" : type.trim(), event == null ? "" : event.trim(),
                                    status, when, body));
                }
            }
        }
        return out;
    }

    // -------------------------------------------------------------- sequences

    private Map<String, SequenceDef> loadSequences(Connection c, String owner) throws SQLException {
        String sql = "SELECT sequence_name, min_value, max_value, increment_by, cycle_flag, order_flag, cache_size "
                + "FROM all_sequences WHERE sequence_owner = ? AND sequence_name NOT LIKE 'ISEQ$$%' ORDER BY sequence_name";
        Map<String, SequenceDef> out = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString(1), new SequenceDef(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), "Y".equals(rs.getString(5)), "Y".equals(rs.getString(6)), rs.getLong(7)));
                }
            }
        }
        return out;
    }

    // -------------------------------------------------------------------- DDL

    private void loadDdl(Connection c, String owner, Map<String, TableDef> tables, Map<String, SequenceDef> sequences,
                         Map<String, String> ddl, List<String> warnings) {
        try (CallableStatement cs = c.prepareCall("BEGIN "
                + "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'SEGMENT_ATTRIBUTES',FALSE); "
                + "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'STORAGE',FALSE); "
                + "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'TABLESPACE',FALSE); "
                + "DBMS_METADATA.SET_TRANSFORM_PARAM(DBMS_METADATA.SESSION_TRANSFORM,'SQLTERMINATOR',TRUE); "
                + "END;")) {
            cs.execute();
        } catch (SQLException e) {
            warnings.add("DBMS_METADATA transform settings failed (" + firstLine(e) + "); DDL text may include storage clauses.");
        }
        Set<String> seen = new HashSet<>();
        for (TableDef t : tables.values()) {
            fetchDdl(c, owner, "TABLE", t.name(), ddl, warnings);
            for (IndexDef i : t.indexes()) if (seen.add(i.name())) fetchDdl(c, owner, "INDEX", i.name(), ddl, warnings);
            for (TriggerDef g : t.triggers()) fetchDdl(c, owner, "TRIGGER", g.name(), ddl, warnings);
        }
        for (String s : sequences.keySet()) fetchDdl(c, owner, "SEQUENCE", s, ddl, warnings);
    }

    private void fetchDdl(Connection c, String owner, String type, String name,
                          Map<String, String> ddl, List<String> warnings) {
        try (PreparedStatement ps = c.prepareStatement("SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM dual")) {
            ps.setString(1, type);
            ps.setString(2, name);
            ps.setString(3, owner);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Clob clob = rs.getClob(1);
                    if (clob != null) ddl.put(type + ":" + name, clob.getSubString(1, (int) clob.length()).trim());
                }
            }
        } catch (SQLException e) {
            warnings.add("DDL not available for " + type + " " + name + ": " + firstLine(e));
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Integer nullableInt(ResultSet rs, int idx) throws SQLException {
        int v = rs.getInt(idx);
        return rs.wasNull() ? null : v;
    }

    private static String firstLine(SQLException e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.lines().findFirst().orElse(m);
    }
}
