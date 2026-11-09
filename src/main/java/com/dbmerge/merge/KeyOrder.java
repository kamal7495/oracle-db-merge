package com.dbmerge.merge;

import static com.dbmerge.util.Sql.quote;

import com.dbmerge.ddl.model.ColumnDef;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The order in which large tables are streamed: by business key, the same in Oracle ({@link #orderBy}) and in Java
 * ({@link #compare}), so the master and every source can be walked side by side.
 *
 * <p>Numbers numerically, dates and timestamps chronologically (time-zone values by instant), RAW as unsigned bytes,
 * text in binary order ({@code NLSSORT(..., 'NLS_SORT=BINARY')}, independent of the session's NLS settings): code-point
 * order for VARCHAR2/CHAR (AL32UTF8 bytes sort that way), UTF-16 order for NVARCHAR2/NCHAR. NULLs last. The planner
 * checks every stream is non-decreasing under {@link #compare} while reading it, so a mismatch stops the plan instead of
 * pairing the wrong rows.
 */
final class KeyOrder implements Comparator<List<Object>> {

    private final List<String> columns;
    private final List<ColumnDef> types;

    /** @param types the key columns' definitions, in key order (the same base type in every database) */
    KeyOrder(List<String> columns, List<ColumnDef> types) {
        this.columns = List.copyOf(columns);
        this.types = List.copyOf(types);
    }

    List<Object> keyOf(Map<String, Object> row) {
        List<Object> k = new ArrayList<>(columns.size());
        for (String c : columns) k.add(row.get(c));
        return k;
    }

    /** ORDER BY clause matching {@link #compare}. */
    String orderBy() {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            String c = quote(columns.get(i));
            String b = types.get(i).baseType();
            boolean text = types.get(i).isCharType();
            parts.add((text ? "NLSSORT(" + c + ", 'NLS_SORT=BINARY')" : c) + " ASC NULLS LAST");
            if (b.startsWith("TIMESTAMP") && b.contains("TIME ZONE")) parts.set(i, "SYS_EXTRACT_UTC(" + c + ") ASC NULLS LAST");
        }
        return " ORDER BY " + String.join(", ", parts);
    }

    @Override
    public int compare(List<Object> a, List<Object> b) {
        for (int i = 0; i < columns.size(); i++) {
            int c = compareValue(a.get(i), b.get(i), types.get(i));
            if (c != 0) return c;
        }
        return 0;
    }

    static int compareValue(Object x, Object y, ColumnDef type) {
        if (x == null || y == null) return x == null ? (y == null ? 0 : 1) : -1; // NULLS LAST
        if (x instanceof BigDecimal p && y instanceof BigDecimal q) return p.compareTo(q);
        if (x instanceof Number p && y instanceof Number q) return new BigDecimal(p.toString()).compareTo(new BigDecimal(q.toString()));
        if (x instanceof Timestamp p && y instanceof Timestamp q) return p.compareTo(q);
        if (x instanceof OffsetDateTime p && y instanceof OffsetDateTime q) return p.toInstant().compareTo(q.toInstant());
        if (x instanceof byte[] p && y instanceof byte[] q) return java.util.Arrays.compareUnsigned(p, q);
        String p = Canon.of(x), q = Canon.of(y);
        boolean national = type != null && type.baseType().startsWith("N") && type.isCharType();
        return national ? p.compareTo(q) : codePointCompare(p, q);
    }

    private static int codePointCompare(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i), cb = b.codePointAt(j);
            if (ca != cb) return Integer.compare(ca, cb);
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
