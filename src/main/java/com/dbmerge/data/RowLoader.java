package com.dbmerge.data;

import com.dbmerge.util.Sql;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only row access used by the data comparison. Values become strings; binary values become hex. */
public final class RowLoader {
    private RowLoader() { }

    public static long count(Connection c, String owner, String table) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + Sql.qualified(owner, table))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public static List<Map<String, String>> load(Connection c, String owner, String table,
                                                  List<String> columns, int maxRows) throws SQLException {
        String sql = "SELECT " + Sql.columnList(columns) + " FROM " + Sql.qualified(owner, table);
        try (Statement s = c.createStatement()) {
            s.setFetchSize(500);
            try (ResultSet rs = s.executeQuery(sql)) {
                return read(rs, columns, maxRows, table);
            }
        }
    }

    /** Reads every row of {@code rs}; result-set column i is stored under {@code columns.get(i)}. */
    static List<Map<String, String>> read(ResultSet rs, List<String> columns, int maxRows, String table) throws SQLException {
        List<Map<String, String>> rows = new ArrayList<>();
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        boolean[] binary = new boolean[n];
        for (int i = 0; i < n; i++) {
            int t = md.getColumnType(i + 1);
            binary[i] = t == Types.BLOB || t == Types.BINARY || t == Types.VARBINARY || t == Types.LONGVARBINARY;
        }
        while (rs.next()) {
            if (rows.size() >= maxRows) {
                throw new IllegalStateException("Table " + table + " has more than " + maxRows + " rows.");
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {   // read strictly left to right (LONG columns require it)
                String v;
                if (binary[i]) {
                    byte[] b = rs.getBytes(i + 1);
                    v = b == null ? null : HexFormat.of().formatHex(b);
                } else {
                    v = rs.getString(i + 1);
                }
                row.put(columns.get(i), v);
            }
            rows.add(row);
        }
        return rows;
    }
}
