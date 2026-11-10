package com.dbmerge.check;

import com.dbmerge.conn.ConnectionSource;
import com.dbmerge.ddl.Dictionary;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;

/**
 * Checks one connection before anything else runs against a real database: which database it reached, whether the
 * login could change the schema, whether everything the comparison needs is visible and readable, and how big the
 * data is. Every query runs in the tool's READ ONLY transaction and reads only the dictionary and session views;
 * nothing is written and no table is scanned.
 *
 * <p>The verdicts are deliberately strict ("if not sure, stop"): a FAIL blocks the report bundle unless the user
 * overrides that category explicitly.
 */
public final class Preflight {

    public enum Status { OK, WARN, FAIL }

    /** What a check is about; the bundle's overrides work per category. */
    public enum Category { WRITE, VISIBILITY, SUPPORT, CAPACITY }

    public record Check(String name, Category category, Status status, String detail) { }

    public record TableSize(String table, Long rows, long bytes, Instant lastAnalyzed, boolean hasKey, boolean hasLob,
                            boolean partitioned, boolean temporary) { }

    public record ConnectionReport(
            String nickname, String target, String sessionUser, String schema, String dbName, String container,
            String databaseRole, String version, boolean owner, boolean dbaDictionary,
            List<Check> checks, int tables, int views, int plsqlUnits, int triggers, long totalRows, long totalBytes,
            int tablesWithoutStats, int tablesWithoutKey, int lobTables, int partitionedTables, int temporaryTables,
            List<TableSize> largest, List<TableSize> allTables, String error) {

        public Status status() {
            if (error != null) return Status.FAIL;
            Status s = Status.OK;
            for (Check c : checks) if (c.status().ordinal() > s.ordinal()) s = c.status();
            return s;
        }

        public boolean failed(Category cat) {
            return error != null || checks.stream().anyMatch(c -> c.category() == cat && c.status() == Status.FAIL);
        }
    }

    /** Object privileges that only read. Anything else on the schema's objects lets the login change it. */
    static final Set<String> READ_OBJECT_PRIVS = Set.of("SELECT", "READ", "DEBUG", "QUERY REWRITE", "FLASHBACK", "ON COMMIT REFRESH",
            // granted on the user itself (to PUBLIC by default since 12c): about running other users' invoker-rights code
            // or replaying sessions, not about changing this schema's objects
            "INHERIT PRIVILEGES", "INHERIT REMOTE PRIVILEGES", "TRANSLATE SQL", "KEEP SEQUENCE", "KEEP DATE TIME");

    /** System privileges that can change another schema's objects or data without an "ANY" in their name. */
    static final Set<String> WRITE_SYSTEM_PRIVS = Set.of("ALTER SYSTEM", "ALTER DATABASE", "BECOME USER", "ADMINISTER DATABASE TRIGGER",
            "SYSDBA", "SYSOPER", "SYSBACKUP", "SYSDG", "SYSKM", "EXEMPT ACCESS POLICY");

    private static final int LARGEST = 15;

    private final ConnectionSource connections;
    private final int streamAbove;

    /** @param streamAbove tables above this many rows are compared by hash and merged in streaming mode */
    public Preflight(ConnectionSource connections, int streamAbove) {
        this.connections = connections;
        this.streamAbove = streamAbove;
    }

    /** Whether a system privilege lets the holder change objects or data of other schemas. */
    static boolean writeSystemPrivilege(String p) {
        String u = p.toUpperCase(Locale.ROOT);
        if (WRITE_SYSTEM_PRIVS.contains(u) || u.startsWith("GRANT ANY")) return true;
        if (!u.contains(" ANY ")) return false;
        return !(u.startsWith("SELECT ANY ") || u.startsWith("READ ANY ") || u.startsWith("DEBUG ANY ") || u.equals("DEBUG CONNECT ANY"));
    }

    public ConnectionReport check(String nickname, String target) {
        String owner = connections.schemaOf(nickname);
        try (Connection c = connections.openReadOnly(nickname)) {
            return check(c, nickname, target, owner);
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().lines().findFirst().orElse("");
            return new ConnectionReport(nickname, target, null, owner, null, null, null, null, false, false, List.of(),
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), "Could not connect: " + msg);
        }
    }

    private ConnectionReport check(Connection c, String nickname, String target, String owner) throws SQLException {
        List<Check> checks = new ArrayList<>();
        String user = one(c, "SELECT USER FROM dual");
        String dbName = context(c, "DB_NAME");
        String con = context(c, "CON_NAME");
        String role = context(c, "DATABASE_ROLE");
        DatabaseMetaData md = c.getMetaData();
        String version = md.getDatabaseProductVersion() == null ? "?" : md.getDatabaseProductVersion().lines().reduce((a, b) -> b).orElse("?");
        int major = md.getDatabaseMajorVersion();
        int minor = md.getDatabaseMinorVersion();
        boolean isOwner = user.equalsIgnoreCase(owner);

        // ---- can this login change the schema?
        if (isOwner) {
            checks.add(new Check("Login is not the schema owner", Category.WRITE, Status.FAIL, "Connected as the owner " + owner
                    + ": this login can change every table. Use a separate read-only user (docs/readonly_user.sql)."));
        } else {
            checks.add(new Check("Login is not the schema owner", Category.WRITE, Status.OK, "Connected as " + user + ", comparing " + owner + "."));
        }
        List<String> sys = new ArrayList<>();
        for (String p : list(c, "SELECT privilege FROM session_privs ORDER BY privilege")) if (writeSystemPrivilege(p)) sys.add(p);
        checks.add(sys.isEmpty()
                ? new Check("No system privilege that writes to other schemas", Category.WRITE, Status.OK, "None of the session's privileges can change another schema.")
                : new Check("No system privilege that writes to other schemas", Category.WRITE, Status.FAIL,
                        "The session holds " + sys.size() + " such privilege(s): " + cap(sys, 12) + "."));
        List<String> writes = new ArrayList<>();
        List<String> executes = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT privilege, COUNT(DISTINCT table_name), MAX(CASE WHEN grantee = 'PUBLIC' THEN 1 ELSE 0 END)"
                + " FROM all_tab_privs WHERE table_schema = ? AND (grantee = USER OR grantee = 'PUBLIC'"
                + " OR grantee IN (SELECT role FROM session_roles)) GROUP BY privilege ORDER BY privilege")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String p = rs.getString(1);
                    if (READ_OBJECT_PRIVS.contains(p)) continue;
                    String text = p + " on " + rs.getLong(2) + " object(s)" + (rs.getInt(3) == 1 ? " (some granted to PUBLIC)" : "");
                    (p.equals("EXECUTE") ? executes : writes).add(text);
                }
            }
        }
        checks.add(writes.isEmpty()
                ? new Check("No write grants on the schema", Category.WRITE, Status.OK, "Only read grants (SELECT/READ) on " + owner + "'s objects.")
                : new Check("No write grants on the schema", Category.WRITE, Status.FAIL, "This login may change " + owner + ": " + String.join("; ", writes) + "."));
        if (!executes.isEmpty()) {
            checks.add(new Check("No right to run the schema's code", Category.WRITE, Status.WARN, String.join("; ", executes)
                    + ". The tool never calls procedures, but code run with the owner's rights can change data."));
        }

        // ---- can it see everything?
        boolean dba = !isOwner && Dictionary.dbaReadable(c);
        if (isOwner || dba) {
            checks.add(new Check("Whole schema visible", Category.VISIBILITY, Status.OK, isOwner ? "The owner sees all its objects."
                    : "Reads the DBA_* dictionary views: PL/SQL, triggers and grants are compared in full."));
        } else {
            checks.add(new Check("Whole schema visible", Category.VISIBILITY, Status.FAIL, "The DBA_* dictionary views are not readable, so"
                    + " PL/SQL, triggers and grants this user has no rights on are invisible and differences in them would be hidden."
                    + " Grant SELECT_CATALOG_ROLE to " + user + "."));
        }
        Dictionary dict = new Dictionary(dba, user, null);
        String tableFilter = " t.owner = ? AND t.nested = 'NO' AND t.secondary = 'N' AND t.dropped = 'NO' AND t.table_name NOT LIKE 'BIN$%'"
                + " AND NOT EXISTS (SELECT 1 FROM all_mviews m WHERE m.owner = t.owner AND m.mview_name = t.table_name)";
        if (dba) {
            List<String> unreadable = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(dict.sql("SELECT t.table_name FROM all_tables t WHERE" + tableFilter)
                    + " AND t.temporary = 'N' AND NOT EXISTS (SELECT 1 FROM all_tables a WHERE a.owner = t.owner AND a.table_name = t.table_name)"
                    + " ORDER BY t.table_name")) {
                ps.setString(1, owner);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) unreadable.add(rs.getString(1)); }
            }
            checks.add(unreadable.isEmpty()
                    ? new Check("Every table readable", Category.VISIBILITY, Status.OK, "The user may read every table of " + owner + ".")
                    : new Check("Every table readable", Category.VISIBILITY, Status.FAIL, unreadable.size() + " table(s) cannot be read, so their data"
                            + " cannot be compared: " + cap(unreadable, 10) + ". Rerun docs/readonly_user.sql (it grants READ on every table)."));
        } else if (!isOwner) {
            checks.add(new Check("Every table readable", Category.VISIBILITY, Status.WARN,
                    "Cannot tell: tables this user may not read are not listed at all without the DBA_* views."));
        }

        // ---- sizes, from optimizer statistics (no table is scanned)
        List<TableSize> sizes = new ArrayList<>();
        String sizeSql = dict.sql("SELECT t.table_name, t.num_rows, NVL(t.num_rows * t.avg_row_len, NVL(t.blocks, 0) * 8192), t.last_analyzed,"
                + " t.partitioned, t.temporary,"
                + " (SELECT COUNT(*) FROM all_constraints k WHERE k.owner = t.owner AND k.table_name = t.table_name AND k.constraint_type IN ('P', 'U')),"
                + " (SELECT COUNT(*) FROM all_tab_cols l WHERE l.owner = t.owner AND l.table_name = t.table_name"
                + "   AND (l.data_type IN ('CLOB', 'BLOB', 'NCLOB', 'BFILE', 'LONG', 'LONG RAW') OR l.data_type LIKE '%XMLTYPE'))"
                + " FROM all_tables t WHERE" + tableFilter + " ORDER BY t.table_name");
        try (PreparedStatement ps = c.prepareStatement(sizeSql)) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long rows = rs.getLong(2);
                    Long n = rs.wasNull() ? null : rows;
                    Timestamp ts = rs.getTimestamp(4);
                    sizes.add(new TableSize(rs.getString(1), n, rs.getLong(3), ts == null ? null : ts.toInstant(),
                            rs.getInt(7) > 0, rs.getInt(8) > 0, "YES".equals(rs.getString(5)), "Y".equals(rs.getString(6))));
                }
            }
        }
        TreeMap<String, Integer> objects = new TreeMap<>();
        try (PreparedStatement ps = c.prepareStatement(dict.sql("SELECT object_type, COUNT(*) FROM all_objects WHERE owner = ? AND generated = 'N'"
                + " AND object_name NOT LIKE 'BIN$%' GROUP BY object_type"))) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) objects.put(rs.getString(1), rs.getInt(2)); }
        }
        int plsql = 0;
        for (String t : List.of("PACKAGE", "PACKAGE BODY", "PROCEDURE", "FUNCTION", "TYPE", "TYPE BODY")) plsql += objects.getOrDefault(t, 0);

        long totalRows = 0, totalBytes = 0;
        int noStats = 0, noKey = 0, lobs = 0, parts = 0, temps = 0;
        List<String> keyless = new ArrayList<>();
        for (TableSize t : sizes) {
            if (t.temporary()) { temps++; continue; }
            if (t.rows() == null) noStats++; else totalRows += t.rows();
            totalBytes += t.bytes();
            if (!t.hasKey()) { noKey++; keyless.add(t.table()); }
            if (t.hasLob()) lobs++;
            if (t.partitioned()) parts++;
        }
        List<TableSize> largest = sizes.stream().filter(t -> !t.temporary())
                .sorted(Comparator.comparing((TableSize t) -> t.rows() == null ? -1L : t.rows()).reversed().thenComparing(TableSize::table))
                .limit(LARGEST).toList();

        // ---- does the database support what the comparison uses?
        boolean versionOk = major > 12 || (major == 12 && minor >= 2);
        checks.add(new Check("Oracle 12.2 or later", Category.SUPPORT, versionOk ? Status.OK : Status.FAIL,
                "Database version " + major + "." + minor + (versionOk ? "." : ": the dictionary columns the tool reads need 12.2.")));
        checks.add(works(c, "SELECT STANDARD_HASH('x', 'MD5') FROM dual")
                ? new Check("Hashing in the database", Category.SUPPORT, Status.OK, "STANDARD_HASH works: large tables are compared without copying rows.")
                : new Check("Hashing in the database", Category.SUPPORT, Status.WARN, "STANDARD_HASH failed: tables above " + streamAbove + " rows cannot be compared."));
        if (lobs > 0) {
            checks.add(works(c, "SELECT DBMS_CRYPTO.HASH(UTL_RAW.CAST_TO_RAW('x'), 2) FROM dual")
                    ? new Check("Hashing LOB columns", Category.SUPPORT, Status.OK, lobs + " table(s) have LOB columns; DBMS_CRYPTO is available for them.")
                    : new Check("Hashing LOB columns", Category.SUPPORT, Status.WARN, lobs + " table(s) have LOB columns, but EXECUTE on DBMS_CRYPTO is missing:"
                            + " large ones among them are not compared. GRANT EXECUTE ON SYS.DBMS_CRYPTO to the user to include them."));
        }
        String firstTable = sizes.isEmpty() ? null : sizes.get(0).table();
        if (firstTable != null) {
            boolean ddl;
            try (PreparedStatement ps = c.prepareStatement("SELECT DBMS_METADATA.GET_DDL('TABLE', ?, ?) FROM dual")) {
                ps.setString(1, firstTable);
                ps.setString(2, owner);
                try (ResultSet rs = ps.executeQuery()) { ddl = rs.next(); }
            } catch (SQLException e) {
                ddl = false;
            }
            checks.add(ddl ? new Check("DDL text for the reports", Category.SUPPORT, Status.OK, "DBMS_METADATA returns the schema's DDL.")
                    : new Check("DDL text for the reports", Category.SUPPORT, Status.WARN, "DBMS_METADATA cannot read " + owner
                            + "'s DDL (needs SELECT_CATALOG_ROLE): the reports show differences but not the full CREATE statements."));
        }
        if (noStats > 0) {
            checks.add(new Check("Optimizer statistics", Category.CAPACITY, Status.WARN, noStats + " table(s) have no statistics, so their size is"
                    + " unknown and the estimates below leave them out."));
        }
        if (noKey > 0) {
            checks.add(new Check("Tables with a key", Category.SUPPORT, Status.WARN, noKey + " table(s) have no primary or unique key: "
                    + cap(keyless, 10) + ". Their data is compared only when a business key is given (--key TABLE=COLUMNS)."));
        }
        return new ConnectionReport(nickname, target, user, owner, dbName, con, role, version, isOwner, dba, checks,
                (int) sizes.stream().filter(t -> !t.temporary()).count(), objects.getOrDefault("VIEW", 0), plsql, objects.getOrDefault("TRIGGER", 0),
                totalRows, totalBytes, noStats, noKey, lobs, parts, temps, largest, sizes, null);
    }

    // ------------------------------------------------------------------ helpers

    private static String one(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static String context(Connection c, String key) {
        try (PreparedStatement ps = c.prepareStatement("SELECT SYS_CONTEXT('USERENV', ?) FROM dual")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (SQLException e) {
            return null;
        }
    }

    private static List<String> list(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static boolean works(Connection c, String sql) {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    static String cap(List<String> items, int n) {
        if (items.size() <= n) return String.join(", ", items);
        return String.join(", ", items.subList(0, n)) + " and " + (items.size() - n) + " more";
    }
}
