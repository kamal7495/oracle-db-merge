package com.dbmerge.refs;

import static com.dbmerge.util.Sql.qualified;
import static com.dbmerge.util.Sql.quote;

import com.dbmerge.conn.ConnectionSource;
import com.dbmerge.refs.RefModel.Candidate;
import com.dbmerge.refs.RefModel.DataCheck;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks detected references against the data, read-only: for each likely parent, how many non-null child values
 * exist and how many have no matching parent row. One LEFT JOIN query per (candidate, parent), each with a query
 * timeout and in its own fresh READ ONLY transaction, so long runs do not hold an old snapshot (ORA-01555/ORA-01466).
 */
public class ReferenceVerifier {

    static final int MAX_PARENTS_CHECKED = 5;
    static final int EXAMPLES = 3;

    private final ConnectionSource connections;

    public ReferenceVerifier(ConnectionSource connections) {
        this.connections = connections;
    }

    public record Outcome(List<Candidate> candidates, List<String> warnings) { }

    /**
     * @param maxChecks upper bound on queries run; the remaining candidates are left unchecked and a warning says so
     */
    public Outcome verify(String nickname, List<Candidate> candidates, int timeoutSeconds, int maxChecks) throws SQLException {
        String owner = connections.schemaOf(nickname);
        List<Candidate> out = new ArrayList<>(candidates.size());
        List<String> warnings = new ArrayList<>();
        int queries = 0;
        int skipped = 0;
        try (Connection c = connections.openReadOnly(nickname)) {
            for (Candidate cand : candidates) {
                if (cand.parentTables().isEmpty() || cand.parentColumn() == null) {
                    out.add(cand);
                    continue;
                }
                List<String> parents = cand.parentTables().subList(0, Math.min(MAX_PARENTS_CHECKED, cand.parentTables().size()));
                if (queries + parents.size() > maxChecks) {
                    skipped++;
                    out.add(cand);
                    continue;
                }
                List<DataCheck> checks = new ArrayList<>();
                for (String parent : parents) {
                    checks.add(check(c, owner, cand, parent, timeoutSeconds));
                    queries++;
                }
                out.add(cand.withDataChecks(checks));
            }
        }
        if (skipped > 0) warnings.add(skipped + " candidate(s) were not checked against the data: the limit of " + maxChecks + " queries was reached.");
        return new Outcome(out, warnings);
    }

    private static DataCheck check(Connection c, String owner, Candidate cand, String parent, int timeoutSeconds) {
        String col = quote(cand.column());
        String pk = quote(cand.parentColumn());
        String child = qualified(owner, cand.table());
        String par = qualified(owner, parent);
        try {
            fresh(c);
            long nonNull;
            long unresolved;
            try (Statement s = c.createStatement()) {
                s.setQueryTimeout(timeoutSeconds);
                try (ResultSet rs = s.executeQuery("SELECT COUNT(c." + col + "), COUNT(c." + col + ") - COUNT(p." + pk + ") FROM "
                        + child + " c LEFT JOIN " + par + " p ON p." + pk + " = c." + col + " WHERE c." + col + " IS NOT NULL")) {
                    rs.next();
                    nonNull = rs.getLong(1);
                    unresolved = rs.getLong(2);
                }
            }
            List<String> examples = new ArrayList<>();
            if (unresolved > 0) {
                try (Statement s = c.createStatement()) {
                    s.setQueryTimeout(timeoutSeconds);
                    try (ResultSet rs = s.executeQuery("SELECT DISTINCT c." + col + " FROM " + child + " c WHERE c." + col
                            + " IS NOT NULL AND NOT EXISTS (SELECT 1 FROM " + par + " p WHERE p." + pk + " = c." + col
                            + ") FETCH FIRST " + EXAMPLES + " ROWS ONLY")) {
                        while (rs.next()) examples.add(rs.getString(1));
                    }
                }
            }
            return new DataCheck(parent, nonNull, unresolved, examples, null);
        } catch (SQLException e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().lines().findFirst().orElse("query failed");
            return new DataCheck(parent, 0, 0, List.of(), msg);
        }
    }

    /** Ends the previous read-only transaction and starts a new one, so every check sees a current snapshot. */
    private static void fresh(Connection c) throws SQLException {
        c.rollback();
        try (Statement s = c.createStatement()) {
            s.execute("SET TRANSACTION READ ONLY");
        }
    }
}
