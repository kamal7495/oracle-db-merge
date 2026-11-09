package com.dbmerge.data;

import com.dbmerge.data.HashCompare.BucketAgg;
import com.dbmerge.data.HashCompare.Cursor;
import com.dbmerge.data.HashCompare.KeyRow;
import com.dbmerge.data.HashCompare.PkRow;
import com.dbmerge.ddl.model.ColumnDef;
import com.dbmerge.ddl.model.TableDef;
import com.dbmerge.util.Sql;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@link HashCompare.Source} for Oracle: every hash is computed in the database with {@code STANDARD_HASH}
 * (12c and later), so only aggregates, the rows of differing buckets (hashes and key values) and the sample rows
 * cross the network.
 *
 * <p>Each column becomes a self-delimiting piece of text, so values cannot run together and column order matters:
 * {@code 'N'} for NULL, {@code 'E'} for an empty LOB, {@code 'V<length>:<canonical text>'} for a short value written
 * inline, or {@code 'H<32 hex>'} (the MD5 of the canonical text) for a long one. Canonical forms do not depend on
 * session NLS settings: numbers via {@code TO_CHAR(x,'TM9')} with '.' as decimal sign, dates and timestamps with an
 * explicit format (time-zone values in UTC), text as stored (or as AL32UTF8 bytes when the databases use different
 * character sets), RAW as hex. LOBs are hashed with {@code DBMS_CRYPTO.HASH} when every database allows it. Types
 * without a canonical form (LONG, LONG RAW, XMLTYPE, BFILE, UROWID, object types) return null from {@link #piece} and
 * are left out by the caller.
 *
 * <p>Writing short values inline means a row costs about three MD5 calls (key, compared columns, row) instead of one
 * per column, which is what dominates the time on large tables. Whether a column is inline, and how pieces are grouped
 * so that no concatenation exceeds the 4000-byte VARCHAR2 limit, is decided once from the largest definition of each
 * column across all databases, so every database builds exactly the same expression shape.
 *
 * <p>All identifiers come from the data dictionary and are quoted; the only bind values are key hashes. Internal
 * aliases use a {@code DBM$} prefix so they cannot clash with ordinary column names.
 */
final class OracleHashSource implements HashCompare.Source {

    static final int GROUP = 100;
    private static final String NUM_FMT = "'TM9', 'NLS_NUMERIC_CHARACTERS=''.,'''";
    private static final String DATE_FMT = "'SYYYY-MM-DD HH24:MI:SS'";
    private static final String TS_FMT = "'SYYYY-MM-DD HH24:MI:SS.FF9'";
    private static final int IN_LIST = 1000;
    private static final int FIRST_RAW = 5; // rowsSql: bucket, three hashes, then the raw key/PK columns

    /** How to hash: LOBs via DBMS_CRYPTO, text via AL32UTF8 bytes, and the per-query timeout. */
    record Options(boolean hashLobs, boolean unicodeText, int timeoutSeconds) { }

    private final Map<String, Connection> conns;
    private final Map<String, String> owners;
    private final Map<String, TableDef> defs;
    private final String table;
    private final List<String> keyCols;
    private final List<String> pkCols;       // empty when the business key is the primary key, or there is none
    private final List<String> compared;     // hashable compared columns
    private final List<String> rowColumns;   // columns fetched for sample rows
    private final Options opt;

    OracleHashSource(Map<String, Connection> conns, Map<String, String> owners, Map<String, TableDef> defs, String table,
                     List<String> keyCols, List<String> pkCols, List<String> compared, List<String> rowColumns, Options opt) {
        this.conns = conns;
        this.owners = owners;
        this.defs = defs;
        this.table = table;
        this.keyCols = keyCols;
        this.pkCols = pkCols;
        this.compared = compared;
        this.rowColumns = rowColumns;
        this.opt = opt;
    }

    // ------------------------------------------------------------------ SQL building

    /** Largest canonical text, in bytes, that is still written inline; longer values are hashed on their own. */
    static final int INLINE_MAX = 200;
    /** Budget per concatenation: below Oracle's 4000-byte VARCHAR2 limit for SQL expressions. */
    static final int GROUP_BYTES = 3800;
    private static final int HASHED_PIECE = 34; // 'H' + 32 hex (+1 spare)

    /** Canonical text expression of a non-null value, or null when the type has none. */
    static String canonical(ColumnDef col, boolean unicodeText) {
        String c = "t." + Sql.quote(col.name());
        String b = col.baseType();
        if (b.equals("NUMBER") || b.equals("FLOAT") || b.equals("BINARY_FLOAT") || b.equals("BINARY_DOUBLE")) {
            return "TO_CHAR(" + c + ", " + NUM_FMT + ")";
        } else if (b.equals("DATE")) {
            return "TO_CHAR(" + c + ", " + DATE_FMT + ")";
        } else if (b.equals("TIMESTAMP")) {
            return "TO_CHAR(" + c + ", " + TS_FMT + ")";
        } else if (b.equals("TIMESTAMP WITH TIME ZONE")) {
            return "TO_CHAR(SYS_EXTRACT_UTC(" + c + "), " + TS_FMT + ")";
        } else if (b.equals("TIMESTAMP WITH LOCAL TIME ZONE")) {
            return "TO_CHAR(SYS_EXTRACT_UTC(CAST(" + c + " AS TIMESTAMP WITH TIME ZONE)), " + TS_FMT + ")";
        } else if (b.startsWith("INTERVAL")) {
            return "TO_CHAR(" + c + ")";
        } else if (col.isCharType()) {
            return unicodeText ? "UTL_I18N.STRING_TO_RAW(" + c + ", 'AL32UTF8')" : c;
        } else if (b.equals("RAW")) {
            return c;
        } else if (b.equals("ROWID")) {
            return "ROWIDTOCHAR(" + c + ")";
        }
        return null;
    }

    /**
     * Upper bound, in bytes, of a column's inline text, or {@link Integer#MAX_VALUE} when it must be hashed on its
     * own: LOBs, national character types (their concatenation would turn the whole group into NVARCHAR2), text
     * compared as AL32UTF8 bytes, and anything long.
     */
    static int inlineBytes(ColumnDef col, boolean unicodeText) {
        String b = col.baseType();
        int len = col.length() == null ? 4000 : col.length();
        int n;
        if (b.equals("NUMBER") || b.equals("FLOAT") || b.equals("BINARY_FLOAT") || b.equals("BINARY_DOUBLE")) n = 64;
        else if (b.equals("DATE")) n = 21;
        else if (b.startsWith("TIMESTAMP")) n = 31;
        else if (b.startsWith("INTERVAL")) n = 40;
        else if (b.equals("ROWID")) n = 20;
        else if (b.equals("RAW")) n = len * 2;
        else if (col.isCharType() && !b.startsWith("N") && !unicodeText) n = "C".equals(col.charUsed()) ? len * 4 : len;
        else return Integer.MAX_VALUE;
        return n <= INLINE_MAX ? n : Integer.MAX_VALUE;
    }

    /** SQL piece for one column hashed on its own ('N', 'E' or 'H' + MD5), or null when the type has no canonical form. */
    static String piece(ColumnDef col, boolean hashLobs, boolean unicodeText) {
        String c = "t." + Sql.quote(col.name());
        if (isLob(col)) {
            if (!hashLobs) return null;
            return "CASE WHEN " + c + " IS NULL THEN 'N' WHEN DBMS_LOB.GETLENGTH(" + c + ") = 0 THEN 'E' "
                    + "ELSE 'H' || RAWTOHEX(DBMS_CRYPTO.HASH(" + c + ", 2)) END";
        }
        String canonical = canonical(col, unicodeText);
        if (canonical == null) return null;
        return "CASE WHEN " + c + " IS NULL THEN 'N' ELSE 'H' || RAWTOHEX(STANDARD_HASH(" + canonical + ", 'MD5')) END";
    }

    /** SQL piece for one short column written inline: 'N', or 'V' + length + ':' + canonical text. */
    static String inlinePiece(ColumnDef col) {
        String c = "t." + Sql.quote(col.name());
        String x = col.baseType().equals("RAW") ? "RAWTOHEX(" + c + ")" : canonical(col, false);
        return "CASE WHEN " + c + " IS NULL THEN 'N' ELSE 'V' || TO_CHAR(LENGTH(" + x + ")) || ':' || " + x + " END";
    }

    static boolean isLob(ColumnDef col) {
        String b = col.baseType();
        return b.equals("CLOB") || b.equals("NCLOB") || b.equals("BLOB");
    }

    /** LONG and LONG RAW cannot be selected next to expressions on other columns in every context; never fetched. */
    static boolean isLong(ColumnDef col) {
        String b = col.baseType();
        return b.equals("LONG") || b.equals("LONG RAW");
    }

    /** One 32-hex hash over pieces of unknown size, hashed in groups of {@link #GROUP} (kept for callers and tests). */
    static String combine(List<String> pieces) {
        if (pieces.isEmpty()) return "'0'";
        List<List<String>> groups = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i += GROUP) groups.add(pieces.subList(i, Math.min(pieces.size(), i + GROUP)));
        return combineGroups(groups);
    }

    /** One 32-hex hash: one MD5 per group, and one more over the group hashes when there are several. */
    static String combineGroups(List<List<String>> groups) {
        if (groups.isEmpty()) return "'0'";
        List<String> hashed = new ArrayList<>();
        for (List<String> g : groups) hashed.add("RAWTOHEX(STANDARD_HASH(" + String.join(" || ", g) + ", 'MD5'))");
        return hashed.size() == 1 ? hashed.get(0) : "RAWTOHEX(STANDARD_HASH(" + String.join(" || ", hashed) + ", 'MD5'))";
    }

    /** Inline size of a column across every database: the largest, or MAX_VALUE if any database must hash it. */
    private int inlineBytes(String col) {
        int max = 0;
        for (TableDef def : defs.values()) {
            ColumnDef c = def.columns().get(col);
            if (c == null) continue;
            int n = inlineBytes(c, opt.unicodeText());
            if (n == Integer.MAX_VALUE) return n;
            max = Math.max(max, n);
        }
        return max;
    }

    private String hashOf(String db, List<String> cols) {
        TableDef def = defs.get(db);
        List<List<String>> groups = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int used = 0;
        for (String col : cols) {
            int inline = inlineBytes(col);
            String p;
            int size;
            if (inline != Integer.MAX_VALUE) {
                p = inlinePiece(def.columns().get(col));
                size = inline + 8; // 'V', up to 5 length digits, ':'
            } else {
                p = piece(def.columns().get(col), opt.hashLobs(), opt.unicodeText());
                size = HASHED_PIECE;
            }
            if (p == null) throw new IllegalStateException("Column " + col + " cannot be hashed");
            if (!current.isEmpty() && (used + size > GROUP_BYTES || current.size() >= GROUP)) {
                groups.add(current);
                current = new ArrayList<>();
                used = 0;
            }
            current.add(p);
            used += size;
        }
        if (!current.isEmpty()) groups.add(current);
        return combineGroups(groups);
    }

    /** Inner select: key hash, primary-key hash, data hash and the raw key/PK columns. */
    String inner(String db) {
        StringBuilder sb = new StringBuilder("SELECT ")
                .append(hashOf(db, keyCols)).append(" DBM$KH, ")
                .append(pkCols.isEmpty() ? "NULL" : hashOf(db, pkCols)).append(" DBM$PH, ")
                .append(hashOf(db, compared)).append(" DBM$CH");
        for (String c : rawColumns()) sb.append(", t.").append(Sql.quote(c));
        return sb.append(" FROM ").append(Sql.qualified(owners.get(db), table)).append(" t").toString();
    }

    private List<String> rawColumns() {
        List<String> out = new ArrayList<>(keyCols);
        for (String c : pkCols) if (!out.contains(c)) out.add(c);
        return out;
    }

    static String bucketExpr(String hashColumn, int buckets) {
        return "ORA_HASH(" + hashColumn + ", " + (buckets - 1) + ")";
    }

    String keyBucketsSql(String db, int buckets) {
        return "SELECT DBM$B, COUNT(*), COUNT(DISTINCT DBM$KH), SUM(TO_NUMBER(SUBSTR(DBM$RH, 1, 15), 'XXXXXXXXXXXXXXX')) FROM ("
                + "SELECT " + bucketExpr("DBM$KH", buckets) + " DBM$B, DBM$KH, "
                + "RAWTOHEX(STANDARD_HASH(DBM$KH || DBM$PH || DBM$CH, 'MD5')) DBM$RH FROM (" + inner(db) + ")) GROUP BY DBM$B";
    }

    String pkBucketsSql(String db, int buckets) {
        return "SELECT DBM$B, COUNT(*), COUNT(DISTINCT DBM$PH), SUM(TO_NUMBER(SUBSTR(DBM$RH, 1, 15), 'XXXXXXXXXXXXXXX')) FROM ("
                + "SELECT " + bucketExpr("DBM$PH", buckets) + " DBM$B, DBM$PH, "
                + "RAWTOHEX(STANDARD_HASH(DBM$PH || DBM$KH, 'MD5')) DBM$RH FROM (" + inner(db) + ")) GROUP BY DBM$B";
    }

    /**
     * Rows of the given buckets, ordered by bucket. Columns: bucket, key hash, PK hash, data hash, raw key/PK columns.
     * For more than 1000 buckets the filter is left out and the cursor skips the other buckets.
     */
    String rowsSql(String db, String hashColumn, int buckets, List<Integer> which) {
        String filter = which.size() <= IN_LIST
                ? " WHERE " + bucketExpr("x." + hashColumn, buckets) + " IN ("
                        + which.stream().map(String::valueOf).collect(Collectors.joining(", ")) + ")"
                : "";
        StringBuilder cols = new StringBuilder("x.DBM$KH, x.DBM$PH, x.DBM$CH");
        for (String c : rawColumns()) cols.append(", x.").append(Sql.quote(c));
        return "SELECT " + bucketExpr("x." + hashColumn, buckets) + " DBM$B, " + cols + " FROM (" + inner(db) + ") x"
                + filter + " ORDER BY 1";
    }

    /** Sample rows by key hash. The hash is computed in the WHERE clause, so no inline view is needed. */
    String sampleSql(String db, int binds) {
        String kh = hashOf(db, keyCols);
        return "SELECT " + rowColumns.stream().map(c -> "t." + Sql.quote(c)).collect(Collectors.joining(", ")) + ", " + kh
                + " FROM " + Sql.qualified(owners.get(db), table) + " t WHERE " + kh
                + " IN (" + String.join(", ", Collections.nCopies(binds, "?")) + ")";
    }

    // ------------------------------------------------------------------ Source

    @Override
    public List<BucketAgg> keyBuckets(String db, int buckets) throws SQLException {
        return aggregates(db, keyBucketsSql(db, buckets));
    }

    @Override
    public List<BucketAgg> pkBuckets(String db, int buckets) throws SQLException {
        return aggregates(db, pkBucketsSql(db, buckets));
    }

    private List<BucketAgg> aggregates(String db, String sql) throws SQLException {
        List<BucketAgg> out = new ArrayList<>();
        try (Statement s = conns.get(db).createStatement()) {
            s.setQueryTimeout(opt.timeoutSeconds());
            s.setFetchSize(1000);
            try (ResultSet rs = s.executeQuery(sql)) {
                while (rs.next()) {
                    BigDecimal sum = rs.getBigDecimal(4);
                    out.add(new BucketAgg(rs.getInt(1), rs.getLong(2), rs.getLong(3), sum == null ? BigDecimal.ZERO : sum));
                }
            }
        }
        return out;
    }

    @Override
    public Cursor<KeyRow> keyRows(String db, int buckets, List<Integer> which) throws SQLException {
        int[] key = positions(keyCols);
        int[] pk = positions(pkCols);
        boolean[] bin = binary(db);
        boolean raw = bindable(db);
        return cursor(db, rowsSql(db, "DBM$KH", buckets, which), rs -> new KeyRow(rs.getInt(1), rs.getString(2),
                display(rs, keyCols, key, bin), pkCols.isEmpty() ? null : display(rs, pkCols, pk, bin), rs.getString(4),
                raw ? rawValues(rs, key) : null));
    }

    @Override
    public Cursor<PkRow> pkRows(String db, int buckets, List<Integer> which) throws SQLException {
        int[] key = positions(keyCols);
        int[] pk = positions(pkCols);
        boolean[] bin = binary(db);
        boolean raw = bindable(db);
        return cursor(db, rowsSql(db, "DBM$PH", buckets, which), rs -> new PkRow(rs.getInt(1), rs.getString(3),
                display(rs, pkCols, pk, bin), rs.getString(2), display(rs, keyCols, key, bin),
                raw ? rawValues(rs, key) : null));
    }

    private int[] positions(List<String> cols) {
        List<String> raw = rawColumns();
        int[] out = new int[cols.size()];
        for (int i = 0; i < cols.size(); i++) out[i] = FIRST_RAW + raw.indexOf(cols.get(i));
        return out;
    }

    /** Per result-set position: is the column RAW (shown as hex, as {@link RowLoader} does)? */
    private boolean[] binary(String db) {
        List<String> raw = rawColumns();
        boolean[] out = new boolean[FIRST_RAW + raw.size()];
        for (int i = 0; i < raw.size(); i++) out[FIRST_RAW + i] = defs.get(db).columns().get(raw.get(i)).baseType().equals("RAW");
        return out;
    }

    /** Key display exactly as {@link RowMatcher} builds it from loaded rows. */
    private static String display(ResultSet rs, List<String> cols, int[] pos, boolean[] binary) throws SQLException {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < cols.size(); i++) {
            String v;
            if (binary[pos[i]]) {
                byte[] b = rs.getBytes(pos[i]);
                v = b == null ? null : HexFormat.of().formatHex(b);
            } else {
                v = rs.getString(pos[i]);
            }
            row.put(cols.get(i), v);
        }
        return RowMatcher.display(RowMatcher.keyOf(row, cols), cols);
    }

    @FunctionalInterface
    private interface RowReader<T> {
        T read(ResultSet rs) throws SQLException;
    }

    private <T> Cursor<T> cursor(String db, String sql, RowReader<T> reader) throws SQLException {
        Statement s = conns.get(db).createStatement();
        ResultSet rs;
        try {
            s.setQueryTimeout(opt.timeoutSeconds());
            s.setFetchSize(1000);
            rs = s.executeQuery(sql);
        } catch (SQLException e) {
            s.close();
            throw e;
        }
        return new Cursor<>() {
            @Override
            public T next() throws SQLException {
                return rs.next() ? reader.read(rs) : null;
            }

            @Override
            public void close() throws SQLException {
                try {
                    rs.close();
                } finally {
                    s.close();
                }
            }
        };
    }

    /**
     * Key types whose JDBC values can be bound back to find the same row (and use its key index). Time-zone types,
     * floats in binary form and anything exotic fall back to matching by key hash.
     */
    private boolean bindable(String db) {
        for (String c : keyCols) {
            String b = defs.get(db).columns().get(c).baseType();
            if (!(b.equals("NUMBER") || b.equals("VARCHAR2") || b.equals("CHAR") || b.equals("NVARCHAR2")
                    || b.equals("NCHAR") || b.equals("DATE") || b.equals("TIMESTAMP") || b.equals("RAW"))) {
                return false;
            }
        }
        return true;
    }

    private static List<Object> rawValues(ResultSet rs, int[] pos) throws SQLException {
        List<Object> out = new ArrayList<>(pos.length);
        for (int p : pos) out.add(rs.getObject(p));
        return out;
    }

    /** Sample rows looked up by their key values: an index range or unique scan instead of hashing every row. */
    String sampleByKeySql(String db, int tuples) {
        String kh = hashOf(db, keyCols);
        String cols = keyCols.stream().map(c -> "t." + Sql.quote(c)).collect(Collectors.joining(", "));
        String one = keyCols.size() == 1 ? "?" : "(" + String.join(", ", Collections.nCopies(keyCols.size(), "?")) + ")";
        String lhs = keyCols.size() == 1 ? cols : "(" + cols + ")";
        return "SELECT " + rowColumns.stream().map(c -> "t." + Sql.quote(c)).collect(Collectors.joining(", ")) + ", " + kh
                + " FROM " + Sql.qualified(owners.get(db), table) + " t WHERE " + lhs
                + " IN (" + String.join(", ", Collections.nCopies(tuples, one)) + ")";
    }

    @Override
    public Map<String, Map<String, String>> rows(String db, Map<String, List<Object>> keys) throws SQLException {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        List<String> cols = new ArrayList<>(rowColumns);
        cols.add("\u0000KH");
        List<List<Object>> byKey = new ArrayList<>();
        List<String> byHash = new ArrayList<>();
        keys.forEach((hash, raw) -> {
            if (raw != null && raw.size() == keyCols.size() && raw.stream().allMatch(Objects::nonNull)) byKey.add(raw);
            else byHash.add(hash);
        });
        int perChunk = Math.max(1, IN_LIST / keyCols.size());
        for (int i = 0; i < byKey.size(); i += perChunk) {
            List<List<Object>> chunk = byKey.subList(i, Math.min(byKey.size(), i + perChunk));
            try (PreparedStatement ps = conns.get(db).prepareStatement(sampleByKeySql(db, chunk.size()))) {
                ps.setQueryTimeout(opt.timeoutSeconds());
                int n = 1;
                for (List<Object> k : chunk) for (Object v : k) ps.setObject(n++, v);
                read(ps, cols, out);
            }
        }
        for (int i = 0; i < byHash.size(); i += IN_LIST) {
            List<String> chunk = byHash.subList(i, Math.min(byHash.size(), i + IN_LIST));
            try (PreparedStatement ps = conns.get(db).prepareStatement(sampleSql(db, chunk.size()))) {
                ps.setQueryTimeout(opt.timeoutSeconds());
                for (int j = 0; j < chunk.size(); j++) ps.setString(j + 1, chunk.get(j));
                read(ps, cols, out);
            }
        }
        return out;
    }

    private void read(PreparedStatement ps, List<String> cols, Map<String, Map<String, String>> out) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            for (Map<String, String> row : RowLoader.read(rs, cols, Integer.MAX_VALUE, table)) {
                out.putIfAbsent(row.remove("\u0000KH"), row);
            }
        }
    }
}
