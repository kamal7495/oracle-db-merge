package com.dbmerge.data;

import com.dbmerge.conn.ConnectionSource;
import com.dbmerge.data.DataModel.DataReport;
import com.dbmerge.data.DataModel.Status;
import com.dbmerge.data.DataModel.TableDataResult;
import com.dbmerge.ddl.model.ConstraintDef;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.TableDef;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Phase 2: per-table row counts plus match/conflict classification on a business key (default: the primary key).
 * Opens read-only connections only. Rows are compared in memory, so tables above maxRowsPerTable are reported
 * with counts but not compared.
 */
public class DataCompareService {

    private final ConnectionSource connections;
    private final int maxRowsPerTable;

    public DataCompareService(ConnectionSource connections, int maxRowsPerTable) {
        this.connections = connections;
        this.maxRowsPerTable = maxRowsPerTable;
    }

    public DataReport run(String ddlComparisonId, List<SchemaModel> models, Map<String, List<String>> keyOverrides,
                          Set<String> onlyTables, int sampleLimit) throws SQLException {
        List<String> dbs = models.stream().map(SchemaModel::nickname).toList();
        Map<String, SchemaModel> byNick = new LinkedHashMap<>();
        for (SchemaModel m : models) byNick.put(m.nickname(), m);

        Set<String> tableNames = new TreeSet<>();
        for (SchemaModel m : models) tableNames.addAll(m.tables().keySet());
        if (onlyTables != null && !onlyTables.isEmpty()) tableNames.retainAll(onlyTables);

        Map<String, Connection> open = new LinkedHashMap<>();
        List<TableDataResult> results = new ArrayList<>();
        try {
            for (String db : dbs) open.put(db, connections.openReadOnly(db));
            for (String table : tableNames) {
                results.add(compareTable(table, dbs, byNick, open, keyOverrides == null ? Map.of() : keyOverrides, sampleLimit));
            }
        } finally {
            for (Connection c : open.values()) {
                try { c.close(); } catch (SQLException ignored) { /* nothing useful to do */ }
            }
        }
        return new DataReport(UUID.randomUUID().toString().substring(0, 8), Instant.now(), ddlComparisonId, dbs, sampleLimit, results);
    }

    private TableDataResult compareTable(String table, List<String> dbs, Map<String, SchemaModel> models,
                                         Map<String, Connection> conns, Map<String, List<String>> overrides, int sampleLimit) {
        List<String> warnings = new ArrayList<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        List<String> present = new ArrayList<>();
        for (String db : dbs) {
            if (models.get(db).tables().containsKey(table)) present.add(db);
        }
        try {
            for (String db : dbs) {
                counts.put(db, present.contains(db) ? RowLoader.count(conns.get(db), models.get(db).owner(), table) : -1L);
            }
        } catch (SQLException e) {
            return empty(table, Status.ERROR, "Row count failed: " + firstLine(e), counts, warnings);
        }
        if (present.size() < 2) {
            return empty(table, Status.SINGLE_DATABASE,
                    "Table exists in only " + (present.isEmpty() ? "no database" : present.get(0)) + "; nothing to compare.", counts, warnings);
        }

        // Columns that exist in every database holding the table; others are listed, not compared.
        Set<String> common = null;
        for (String db : present) {
            Set<String> cols = models.get(db).tables().get(table).columns().keySet();
            if (common == null) common = new LinkedHashSet<>(cols); else common.retainAll(cols);
        }
        Map<String, List<String>> uncompared = new LinkedHashMap<>();
        for (String db : present) {
            List<String> extra = new ArrayList<>();
            for (String c : models.get(db).tables().get(table).columns().keySet()) if (!common.contains(c)) extra.add(c);
            if (!extra.isEmpty()) uncompared.put(db, extra);
        }

        List<String> pk = primaryKey(table, present, models, warnings);
        List<String> key;
        boolean keyIsPk;
        List<String> chosen = overrides.get(table);
        if (chosen != null && !chosen.isEmpty()) {
            for (String c : chosen) {
                if (!common.contains(c)) {
                    return empty(table, Status.NO_KEY, "Chosen key column " + c + " does not exist in every database.", counts, warnings);
                }
            }
            key = chosen;
            keyIsPk = new LinkedHashSet<>(chosen).equals(new LinkedHashSet<>(pk));
        } else if (!pk.isEmpty() && common.containsAll(pk)) {
            key = pk;
            keyIsPk = true;
        } else {
            return empty(table, Status.NO_KEY, "No primary key common to all databases - choose a business key for this table.", counts, warnings);
        }

        long max = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        if (max > maxRowsPerTable) {
            return empty(table, Status.TOO_LARGE, "Largest copy has " + max + " rows (limit " + maxRowsPerTable
                    + "). Raise dbmerge.max-rows-per-table to compare it in memory.", counts, warnings);
        }

        List<String> columns = new ArrayList<>(common);
        // When the business key differs from the PK, differing PKs are expected and reported separately.
        List<String> compared = new ArrayList<>(columns);
        if (!keyIsPk) compared.removeAll(pk);

        Map<String, List<Map<String, String>>> rows = new LinkedHashMap<>();
        try {
            for (String db : present) rows.put(db, RowLoader.load(conns.get(db), models.get(db).owner(), table, columns, maxRowsPerTable));
        } catch (SQLException | IllegalStateException e) {
            return empty(table, Status.ERROR, "Reading rows failed: " + firstLine(e), counts, warnings);
        }

        RowMatcher.Output o = RowMatcher.match(new RowMatcher.Input(present, key, pk, compared, rows, sampleLimit));
        warnings.addAll(o.warnings());
        if (!uncompared.isEmpty()) {
            warnings.add("Columns present in only some databases were not compared: " + uncompared + ".");
        }
        if (!compared.isEmpty() && hasForeignKeyColumns(table, present, models, compared)) {
            warnings.add("Foreign-key columns are compared as raw values; they can differ only because the parent table's keys differ.");
        }
        return new TableDataResult(table, Status.COMPARED, null, key, keyIsPk, pk, counts, compared, uncompared,
                o.onlyInCounts(), o.identicalCount(), o.conflictCount(), o.pkCollisionCount(), o.pkDivergenceCount(),
                o.onlyRows(), o.identicalRows(), o.conflicts(), o.pkCollisions(), o.pkDivergences(), warnings);
    }

    private static List<String> primaryKey(String table, List<String> present, Map<String, SchemaModel> models, List<String> warnings) {
        List<String> first = null;
        for (String db : present) {
            TableDef t = models.get(db).tables().get(table);
            List<String> pk = t.constraints().stream().filter(c -> c.type().equals("P")).map(ConstraintDef::columns)
                    .findFirst().orElse(List.of());
            if (first == null) first = pk;
            else if (!first.equals(pk)) {
                warnings.add("Primary key differs between databases (" + db + "); using the first database's definition.");
            }
        }
        return first == null ? List.of() : first;
    }

    private static boolean hasForeignKeyColumns(String table, List<String> present, Map<String, SchemaModel> models, List<String> compared) {
        TableDef t = models.get(present.get(0)).tables().get(table);
        return t.constraints().stream().anyMatch(c -> c.type().equals("R") && compared.containsAll(c.columns()));
    }

    private static TableDataResult empty(String table, Status status, String message, Map<String, Long> counts, List<String> warnings) {
        return new TableDataResult(table, status, message, List.of(), false, List.of(), counts, List.of(), Map.of(),
                Map.of(), 0, 0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of(), warnings);
    }

    private static String firstLine(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m.lines().findFirst().orElse(m);
    }
}
