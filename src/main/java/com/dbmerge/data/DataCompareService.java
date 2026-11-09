package com.dbmerge.data;

import com.dbmerge.conn.ConnectionSource;
import com.dbmerge.conn.ReadOnlyRetry;
import com.dbmerge.data.DataModel.DataReport;
import com.dbmerge.data.DataModel.Status;
import com.dbmerge.data.DataModel.TableDataResult;
import com.dbmerge.ddl.model.ConstraintDef;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.TableDef;
import com.dbmerge.ddl.model.ColumnDef;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * Opens read-only connections only. Tables up to maxRowsPerTable are compared in memory ({@link RowMatcher}); larger
 * ones are compared with hashes computed in the databases ({@link HashCompare}), so memory stays bounded by the
 * number of differing rows instead of the table size.
 */
public class DataCompareService {

    /** Default timeout for each hash query on a large table. */
    public static final int DEFAULT_HASH_QUERY_TIMEOUT_SECONDS = 1800;
    static final int ROWS_PER_BUCKET = 10_000;

    private final ConnectionSource connections;
    private final int maxRowsPerTable;
    private final int hashQueryTimeoutSeconds;

    public DataCompareService(ConnectionSource connections, int maxRowsPerTable) {
        this(connections, maxRowsPerTable, DEFAULT_HASH_QUERY_TIMEOUT_SECONDS);
    }

    /** @param hashQueryTimeoutSeconds timeout of each server-side hash query (large tables only) */
    public DataCompareService(ConnectionSource connections, int maxRowsPerTable, int hashQueryTimeoutSeconds) {
        this.connections = connections;
        this.maxRowsPerTable = maxRowsPerTable;
        this.hashQueryTimeoutSeconds = hashQueryTimeoutSeconds;
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

    @FunctionalInterface
    interface ConnectionRead<T> {
        T apply(Connection c) throws SQLException;
    }

    /**
     * Runs a read on the shared read-only connection for {@code db}. On ORA-01466 the connection's READ ONLY
     * transaction is older than (or too close to) the table's DDL, so it is replaced by a fresh one before the retry.
     */
    private <T> T read(Map<String, Connection> conns, String db, ConnectionRead<T> read) throws SQLException {
        return ReadOnlyRetry.call(() -> {
            try {
                return read.apply(conns.get(db));
            } catch (SQLException e) {
                if (ReadOnlyRetry.isTableDefinitionChanged(e)) reopen(conns, db);
                throw e;
            }
        });
    }

    private void reopen(Map<String, Connection> conns, String db) throws SQLException {
        Connection old = conns.remove(db);
        if (old != null) {
            try { old.close(); } catch (SQLException ignored) { /* replaced below */ }
        }
        conns.put(db, connections.openReadOnly(db));
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
                counts.put(db, present.contains(db)
                        ? read(conns, db, c -> RowLoader.count(c, models.get(db).owner(), table)) : -1L);
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

        List<String> columns = new ArrayList<>(common);
        // When the business key differs from the PK, differing PKs are expected and reported separately.
        List<String> compared = new ArrayList<>(columns);
        if (!keyIsPk) compared.removeAll(pk);

        long max = counts.values().stream().mapToLong(Long::longValue).max().orElse(0);
        if (max > maxRowsPerTable) {
            return hashed(table, present, models, key, keyIsPk, pk, columns, compared, uncompared, counts, warnings,
                    sampleLimit, max);
        }

        Map<String, List<Map<String, String>>> rows = new LinkedHashMap<>();
        try {
            for (String db : present) {
                rows.put(db, read(conns, db, c -> RowLoader.load(c, models.get(db).owner(), table, columns, maxRowsPerTable)));
            }
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

    /** Large table: compare with server-side hashes on dedicated read-only connections (one snapshot per table). */
    private TableDataResult hashed(String table, List<String> present, Map<String, SchemaModel> models, List<String> key,
                                   boolean keyIsPk, List<String> pk, List<String> columns, List<String> compared,
                                   Map<String, List<String>> uncompared, Map<String, Long> counts, List<String> warnings,
                                   int sampleLimit, long maxRows) {
        Map<String, TableDef> defs = new LinkedHashMap<>();
        Map<String, String> owners = new LinkedHashMap<>();
        for (String db : present) {
            defs.put(db, models.get(db).tables().get(table));
            owners.put(db, models.get(db).owner());
        }
        List<String> pkPart = keyIsPk ? List.of() : pk;
        for (String c : concat(key, pkPart)) {
            for (String db : present) {
                ColumnDef col = defs.get(db).columns().get(c);
                if (col == null || !hashableWithoutLobs(col)) {
                    return empty(table, Status.ERROR, "Table has " + maxRows + " rows (above the in-memory limit of "
                            + maxRowsPerTable + ") and key column " + c + (col == null ? " is missing in " + db
                            : " has type " + col.displayType() + " in " + db) + ", which cannot be hashed. Choose another key.",
                            counts, warnings);
                }
            }
        }
        boolean anyLob = compared.stream().anyMatch(c -> present.stream().anyMatch(db -> OracleHashSource.isLob(defs.get(db).columns().get(c))));
        int buckets = HashCompare.bucketsFor(maxRows, ROWS_PER_BUCKET);
        try {
            return ReadOnlyRetry.call(() -> {
                Map<String, Connection> conns = new LinkedHashMap<>();
                try {
                    for (String db : present) conns.put(db, connections.openReadOnly(db));
                    boolean lobs = anyLob && present.stream().allMatch(db -> canHashLobs(conns.get(db)));
                    Set<String> charsets = new LinkedHashSet<>();
                    for (String db : present) charsets.add(charsets(conns.get(db)));
                    boolean unicode = charsets.size() > 1;

                    List<String> hashedCols = new ArrayList<>();
                    List<String> excluded = new ArrayList<>();
                    for (String c : compared) {
                        boolean ok = present.stream().allMatch(db -> OracleHashSource.piece(defs.get(db).columns().get(c), lobs, false) != null);
                        if (ok) hashedCols.add(c);
                        else excluded.add(c + " (" + defs.get(present.get(0)).columns().get(c).displayType() + ")");
                    }
                    List<String> rowCols = new ArrayList<>();
                    for (String c : columns) {
                        if (present.stream().noneMatch(db -> OracleHashSource.isLong(defs.get(db).columns().get(c)))) rowCols.add(c);
                    }
                    OracleHashSource src = new OracleHashSource(conns, owners, defs, table, key, pkPart, hashedCols, rowCols,
                            new OracleHashSource.Options(lobs, unicode, hashQueryTimeoutSeconds));
                    RowMatcher.Output o = HashCompare.run(new HashCompare.Input(present, hashedCols, keyIsPk, !pk.isEmpty(),
                            buckets, sampleLimit), src);

                    StringBuilder note = new StringBuilder("Compared with server-side hashes: the largest copy has ")
                            .append(maxRows).append(" rows, above the in-memory limit of ").append(maxRowsPerTable)
                            .append(" (").append(buckets).append(" bucket(s)). Identical rows are counted, not listed.");
                    if (!excluded.isEmpty()) {
                        note.append(" Not compared (no reliable hash").append(anyLob && !lobs ? "; LOBs need EXECUTE on DBMS_CRYPTO" : "")
                                .append("): ").append(String.join(", ", excluded)).append('.');
                    }
                    if (unicode) note.append(" Text was hashed as AL32UTF8 because the databases use different character sets ")
                            .append(charsets).append('.');
                    List<String> w = new ArrayList<>(warnings);
                    w.addAll(o.warnings());
                    for (String c : hashedCols) {
                        Set<String> types = new LinkedHashSet<>();
                        for (String db : present) types.add(defs.get(db).columns().get(c).baseType());
                        if (types.size() > 1) {
                            w.add("Column " + c + " has different types " + types + "; equal values can hash differently, so its rows may show as conflicts.");
                        }
                    }
                    if (!uncompared.isEmpty()) w.add("Columns present in only some databases were not compared: " + uncompared + ".");
                    if (!hashedCols.isEmpty() && hasForeignKeyColumns(table, present, models, hashedCols)) {
                        w.add("Foreign-key columns are compared as raw values; they can differ only because the parent table's keys differ.");
                    }
                    return new TableDataResult(table, Status.COMPARED, note.toString(), key, keyIsPk, pk, counts, hashedCols,
                            uncompared, o.onlyInCounts(), o.identicalCount(), o.conflictCount(), o.pkCollisionCount(),
                            o.pkDivergenceCount(), o.onlyRows(), o.identicalRows(), o.conflicts(), o.pkCollisions(),
                            o.pkDivergences(), w);
                } finally {
                    for (Connection c : conns.values()) {
                        try { c.close(); } catch (SQLException ignored) { /* nothing useful to do */ }
                    }
                }
            });
        } catch (SQLException | RuntimeException e) {
            return empty(table, Status.ERROR, "Hash comparison failed (needs Oracle 12c or later for STANDARD_HASH): "
                    + firstLine(e), counts, warnings);
        }
    }

    private static boolean hashableWithoutLobs(ColumnDef c) {
        return OracleHashSource.piece(c, false, false) != null;
    }

    private static boolean canHashLobs(Connection c) {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT DBMS_CRYPTO.HASH(UTL_RAW.CAST_TO_RAW('x'), 2) FROM dual")) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    private static String charsets(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT value FROM nls_database_parameters "
                     + "WHERE parameter IN ('NLS_CHARACTERSET', 'NLS_NCHAR_CHARACTERSET') ORDER BY parameter")) {
            StringBuilder sb = new StringBuilder();
            while (rs.next()) sb.append(sb.length() == 0 ? "" : "/").append(rs.getString(1));
            return sb.toString();
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(a);
        for (String x : b) if (!out.contains(x)) out.add(x);
        return out;
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
