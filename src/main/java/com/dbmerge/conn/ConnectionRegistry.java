package com.dbmerge.conn;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * In-memory registry of Oracle connections. Passwords are held as char[] in memory only; they are never
 * logged, serialised or written to disk. Connections opened for extraction/comparison are read-only.
 */
@Component
public class ConnectionRegistry implements ConnectionSource {

    private static final Pattern NICKNAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_\\-]{0,29}$");
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9._\\-]{1,255}$");
    private static final Pattern SERVICE = Pattern.compile("^[A-Za-z0-9_.$#\\-]{1,128}$");
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z][A-Za-z0-9_$#]{0,127}$");

    private final Map<String, ConnectionInfo> connections = new ConcurrentHashMap<>();

    public ConnectionView add(ConnectionRequest req) {
        String nickname = trim(req.nickname());
        if (!NICKNAME.matcher(nickname).matches()) {
            throw new IllegalArgumentException(
                    "Nickname must start with a letter and contain only letters, digits, '_' or '-' (max 30).");
        }
        String host = trim(req.host());
        if (host.isEmpty()) host = "localhost";
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("Host contains invalid characters.");
        }
        int port = req.port() == null ? 1521 : req.port();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535.");
        }
        String service = trim(req.serviceName());
        if (!SERVICE.matcher(service).matches()) {
            throw new IllegalArgumentException("Service name is required and may contain only letters, digits and _ . $ # -");
        }
        String user = trim(req.username());
        if (!IDENT.matcher(user).matches()) {
            throw new IllegalArgumentException("Username is required (letters, digits, _ $ #).");
        }
        String schema = trim(req.schema());
        if (!schema.isEmpty() && !IDENT.matcher(schema).matches()) {
            throw new IllegalArgumentException("Schema name is invalid (letters, digits, _ $ #).");
        }
        char[] pw = req.password();
        if (pw == null || pw.length == 0) {
            throw new IllegalArgumentException("Password is required.");
        }
        ConnectionInfo info = new ConnectionInfo(nickname, host, port, service, user, pw.clone(),
                schema.isEmpty() ? null : schema);
        if (connections.putIfAbsent(nickname, info) != null) {
            info.wipe();
            throw new IllegalArgumentException("A connection named '" + nickname + "' already exists - remove it first.");
        }
        java.util.Arrays.fill(pw, '\0'); // wipe the caller's copy
        return info.view();
    }

    public List<ConnectionView> list() {
        List<ConnectionView> out = new ArrayList<>();
        for (ConnectionInfo c : connections.values()) out.add(c.view());
        out.sort(Comparator.comparing(ConnectionView::nickname, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public boolean remove(String nickname) {
        ConnectionInfo removed = connections.remove(nickname);
        if (removed == null) return false;
        removed.wipe();
        return true;
    }

    public ConnectionInfo require(String nickname) {
        ConnectionInfo info = connections.get(nickname);
        if (info == null) throw new IllegalArgumentException("Unknown connection: " + nickname);
        return info;
    }

    /**
     * Opens a read-only connection. Used for every comparison/extraction; nothing in Phase 1 or 2 can write
     * through it. The transaction is explicitly READ ONLY and is never committed.
     */
    @Override
    public String schemaOf(String nickname) {
        return require(nickname).effectiveSchema();
    }

    @Override
    public Connection openWritable(String nickname) throws SQLException {
        Connection c = connect(nickname);
        try {
            c.setAutoCommit(false);
        } catch (SQLException e) {
            try { c.close(); } catch (SQLException ignored) { /* nothing to do */ }
            throw e;
        }
        return c;
    }

    private Connection connect(String nickname) throws SQLException {
        ConnectionInfo info = require(nickname);
        Properties p = new Properties();
        p.setProperty("user", info.username());
        p.setProperty("password", new String(info.password()));
        p.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
        p.setProperty("oracle.jdbc.ReadTimeout", "300000");
        return DriverManager.getConnection(info.jdbcUrl(), p);
    }

    @Override
    public Connection openReadOnly(String nickname) throws SQLException {
        Connection c = connect(nickname);
        try {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SET TRANSACTION READ ONLY");
            }
        } catch (SQLException e) {
            try { c.close(); } catch (SQLException ignored) { /* nothing to do */ }
            throw e;
        }
        return c;
    }

    public TestResult test(String nickname) {
        ConnectionInfo info = require(nickname);
        long start = System.nanoTime();
        try (Connection c = openReadOnly(nickname);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT sys_context('USERRENV','CURRENT_SCHEMA') FROM dual")) {
            String current = rs.next() ? rs.getString(1) : null;
            String version = c.getMetaData().getDatabaseProductVersion();
            String firstLine = version == null ? null : version.lines().findFirst().orElse(version);
            long ms = (System.nanoTime() - start) / 1_000_000;
            String note = "Connected.";
            if (current != null && !current.equalsIgnoreCase(info.effectiveSchema())) {
                note += " Comparing schema " + info.effectiveSchema() + " (connected as " + current + ").";
            }
            return new TestResult(true, note, firstLine, current, ms);
        } catch (SQLException e) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            String msg = e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage().lines().findFirst().orElse("Connection failed");
            return new TestResult(false, msg, null, null, ms);
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
