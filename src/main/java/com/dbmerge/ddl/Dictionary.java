package com.dbmerge.ddl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which dictionary views describe the schema: ALL_* or DBA_*.
 *
 * <p>ALL_* views list only what the connected user has rights on. The schema owner sees everything; a separate
 * read-only user with just READ on the tables does <em>not</em> see the schema's PL/SQL, most triggers, or grants
 * made to others, and a comparison through such a user cannot tell "not visible" from "does not exist". So when the
 * user is not the owner and can read the DBA_* views (SELECT_CATALOG_ROLE or SELECT ANY DICTIONARY), every
 * dictionary query is sent to the DBA_* view of the same name instead. Otherwise the ALL_* views are used and the
 * model carries a warning saying what may be missing.
 */
public record Dictionary(boolean dba, String sessionUser, String warning) {

    /** ALL_ views the extractor reads that have a DBA_ twin with the same columns. */
    private static final Pattern SAME_COLUMNS = Pattern.compile(
            "\\ball_(tables|tab_cols|constraints|cons_columns|ind_columns|indexes|triggers|sequences|objects|views|mviews|source|synonyms)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TAB_PRIVS = Pattern.compile("\\ball_tab_privs\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_SCHEMA = Pattern.compile("\\btable_schema\\b", Pattern.CASE_INSENSITIVE);

    /** A query that fails unless the DBA_ views the extractor needs are readable. */
    static final String PROBE = "SELECT (SELECT COUNT(*) FROM dba_tables WHERE ROWNUM = 1) + (SELECT COUNT(*) FROM dba_source WHERE ROWNUM = 1)"
            + " + (SELECT COUNT(*) FROM dba_tab_privs WHERE ROWNUM = 1) + (SELECT COUNT(*) FROM dba_triggers WHERE ROWNUM = 1) FROM dual";

    public static Dictionary choose(Connection c, String owner) throws SQLException {
        String user;
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT USER FROM dual")) {
            rs.next();
            user = rs.getString(1);
        }
        if (user.equalsIgnoreCase(owner)) return new Dictionary(false, user, null);
        if (dbaReadable(c)) return new Dictionary(true, user, null);
        return new Dictionary(false, user, "Connected as " + user + ", not the schema owner " + owner + ", and without access to the"
                + " DBA_* dictionary views: PL/SQL, triggers and grants this user has no rights on are invisible, so differences in"
                + " them can be hidden. Grant SELECT_CATALOG_ROLE to " + user + " (read-only) for a complete comparison.");
    }

    /** True when the DBA_ views can be queried (savepoint-free: a failed SELECT does not end the read-only transaction). */
    public static boolean dbaReadable(Connection c) {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(PROBE)) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    /** The query with ALL_ views replaced by their DBA_ twins when this dictionary is DBA. */
    public String sql(String sql) {
        if (!dba) return sql;
        String out = SAME_COLUMNS.matcher(sql).replaceAll(m -> "dba_" + m.group(1).toLowerCase(Locale.ROOT));
        if (TAB_PRIVS.matcher(out).find()) {
            // DBA_TAB_PRIVS names the object's schema OWNER where ALL_TAB_PRIVS says TABLE_SCHEMA.
            out = TABLE_SCHEMA.matcher(TAB_PRIVS.matcher(out).replaceAll("dba_tab_privs")).replaceAll(Matcher.quoteReplacement("owner"));
        }
        return out;
    }

    /** A view of {@code c} whose prepareStatement sends every query through {@link #sql}. Closing it closes {@code c}. */
    public Connection wrap(Connection c) {
        if (!dba) return c;
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, m, args) -> {
            if (m.getName().equals("prepareStatement") && args != null && args.length >= 1 && args[0] instanceof String q) {
                args = args.clone();
                args[0] = sql(q);
            }
            try {
                return m.invoke(c, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }
}
