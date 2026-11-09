package com.dbmerge.merge;

import com.dbmerge.ddl.model.ColumnDef;
import com.dbmerge.ddl.model.TableDef;

/**
 * Zero-length LOBs. Oracle distinguishes an empty CLOB/BLOB ({@code EMPTY_CLOB()}, {@code EMPTY_BLOB()}) from NULL,
 * and JDBC reads them back as {@code ""} and {@code byte[0]}. Binding those values writes NULL, because Oracle treats
 * an empty string or empty RAW bind as NULL. So for LOB columns the empty value is written as the SQL function instead.
 * Non-LOB columns are left alone: in VARCHAR2/RAW an empty value already is NULL in the source.
 */
final class EmptyLobs {

    private EmptyLobs() { }

    /** {@code EMPTY_CLOB()} / {@code EMPTY_BLOB()} when {@code value} is an empty LOB for this column, otherwise null. */
    static String expression(TableDef def, String column, Object value) {
        if (def == null || value == null) return null;
        ColumnDef c = def.columns().get(column);
        if (c == null) return null;
        String type = c.baseType();
        if (value instanceof String s && s.isEmpty() && (type.equals("CLOB") || type.equals("NCLOB"))) return "EMPTY_CLOB()";
        if (value instanceof byte[] b && b.length == 0 && type.equals("BLOB")) return "EMPTY_BLOB()";
        return null;
    }
}
