package com.dbmerge.merge;

import com.dbmerge.ddl.model.ColumnDef;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Canonical string forms so values from different databases can be compared and used as map keys. */
public final class Canon {
    private static final String SEP = "\u0001";
    private static final String NULL_MARK = "\u0000NULL";

    private Canon() { }

    public static String of(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b.signum() == 0 ? "0" : b.stripTrailingZeros().toPlainString();
        if (v instanceof Number n) return of(new BigDecimal(n.toString()));
        if (v instanceof Timestamp t) return t.toLocalDateTime().toString();
        if (v instanceof OffsetDateTime o) return o.toInstant().toString();
        if (v instanceof byte[] b) return HexFormat.of().formatHex(b);
        return v.toString();
    }

    public static String key(Map<String, Object> row, List<String> columns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) sb.append(SEP);
            String c = of(row.get(columns.get(i)));
            sb.append(c == null ? NULL_MARK : c);
        }
        return sb.toString();
    }

    public static String display(String key, List<String> columns) {
        String[] parts = key.split(SEP, -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size() && i < parts.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(columns.get(i)).append('=').append(parts[i].equals(NULL_MARK) ? "NULL" : parts[i]);
        }
        return sb.toString();
    }

    public static boolean sameValue(Object a, Object b) {
        String x = of(a);
        String y = of(b);
        return x == null ? y == null : x.equals(y);
    }

    /** Converts a manually entered string to the master column's type. */
    public static Object convert(String text, ColumnDef col) {
        if (text == null) return null;
        String base = col.baseType();
        try {
            if (base.equals("NUMBER") || base.equals("FLOAT") || base.startsWith("BINARY_")) return new BigDecimal(text.trim());
            if (base.equals("DATE") || base.startsWith("TIMESTAMP")) {
                String t = text.trim();
                if (t.length() == 10) return Timestamp.valueOf(LocalDate.parse(t).atStartOfDay());
                return Timestamp.valueOf(t.replace('T', ' '));
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Value '" + text + "' is not valid for column " + col.name() + " (" + col.displayType() + ")");
        }
        return text;
    }
}
