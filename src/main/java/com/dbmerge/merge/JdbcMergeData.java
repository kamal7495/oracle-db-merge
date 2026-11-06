package com.dbmerge.merge;

import static com.dbmerge.util.Sql.columnList;
import static com.dbmerge.util.Sql.qualified;

import com.dbmerge.conn.ConnectionSource;
import com.dbmerge.conn.ReadOnlyRetry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads typed rows through read-only connections. Used for the master and the sources while planning. */
public class JdbcMergeData implements MergePlanner.MergeData {

    private final ConnectionSource connections;
    private final int maxRowsPerTable;

    public JdbcMergeData(ConnectionSource connections, int maxRowsPerTable) {
        this.connections = connections;
        this.maxRowsPerTable = maxRowsPerTable;
    }

    @Override
    public List<Map<String, Object>> rows(String db, String table, List<String> columns) throws SQLException {
        if (columns.isEmpty()) return new ArrayList<>();
        String sql = "SELECT " + columnList(columns) + " FROM " + qualified(connections.schemaOf(db), table);
        // Each attempt opens a fresh READ ONLY transaction, so a retry after ORA-01466 gets a newer snapshot.
        return ReadOnlyRetry.call(() -> load(db, table, columns, sql));
    }

    private List<Map<String, Object>> load(String db, String table, List<String> columns, String sql) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Connection c = connections.openReadOnly(db);
             Statement st = c.createStatement()) {
            st.setFetchSize(1000);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                int[] types = new int[columns.size()];
                for (int i = 0; i < types.length; i++) types[i] = md.getColumnType(i + 1);
                while (rs.next()) {
                    if (out.size() >= maxRowsPerTable) {
                        throw new IllegalStateException("Table " + table + " in " + db + " has more than " + maxRowsPerTable
                                + " rows; the merge works in memory and refuses to continue (raise dbmerge.max-rows-per-table).");
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < types.length; i++) row.put(columns.get(i), read(rs, i + 1, types[i]));
                    out.add(row);
                }
            }
        } finally {
            // the read-only connection was closed above; nothing was written
        }
        return out;
    }

    static Object read(ResultSet rs, int idx, int type) throws SQLException {
        switch (type) {
            case Types.NUMERIC, Types.DECIMAL, Types.INTEGER, Types.SMALLINT, Types.BIGINT, Types.FLOAT, Types.DOUBLE, Types.REAL:
                return rs.getBigDecimal(idx);
            case Types.DATE, Types.TIMESTAMP:
                return rs.getTimestamp(idx);
            case Types.TIMESTAMP_WITH_TIMEZONE, -101, -102:
                return rs.getObject(idx, OffsetDateTime.class);
            case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY:
                return rs.getBytes(idx);
            case Types.CLOB, Types.NCLOB:
                return rs.getString(idx);
            default:
                return rs.getString(idx);
        }
    }
}
