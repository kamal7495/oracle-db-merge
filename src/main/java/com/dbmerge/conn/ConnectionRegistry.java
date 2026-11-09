package com.dbmerge.conn;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registry of Oracle connections. Passwords are held as char[] in memory and never logged. They reach disk only
 * for connections saved with "save password", and then only encrypted by {@link ConnectionStore}.
 * Connections opened for extraction/comparison are read-only.
 */
public class ConnectionRegistry implements ConnectionSource {

    private static final Pattern NICKNAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_\\-]{0,29}$");
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9._\\-]{1,255}$");
    private static final Pattern SERVICE = Pattern.compile("^[A-Za-z0-9_.$#\\-]{1,128}$");
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z][A-Za-z0-9_$#]{0,127}$");

    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    private final Map<String, ConnectionInfo> connections = new ConcurrentHashMap<>();
    private final ConnectionStore store; // null: nothing is saved (tests, or saving disabled)

    public ConnectionRegistry() {
        this(null);
    }

    public ConnectionRegistry(ConnectionStore store) {
        this.store = store;
    }

    public ConnectionView add(ConnectionRequest req) {
        char[] pw = req.password();
        if (pw == null || pw.length == 0) {
            throw new IllegalArgumentException("Password is required.");
        }
        if (req.wantsSave() && store == null) {
            throw new IllegalArgumentException("Saving connections is switched off on this server.");
        }
        ConnectionInfo info = build(req.nickname(), req.host(), req.port(), req.serviceName(), req.username(),
                req.schema(), pw.clone(), req.wantsSave(), null);
        if (connections.putIfAbsent(info.nickname(), info) != null) {
            info.wipe();
            throw new IllegalArgumentException("A connection named '" + info.nickname() + "' already exists - remove it first.");
        }
        Arrays.fill(pw, '\0'); // wipe the caller's copy
        if (info.remembered()) {
            try {
                if (req.wantsSavePassword()) info.savedPassword(store.encrypt(info.password(), info.toSaved(null)));
                persist();
            } catch (IOException e) {
                connections.remove(info.nickname(), info);
                info.wipe();
                throw new IllegalArgumentException("Could not save the connection: " + e.getMessage());
            }
        }
        return info.view();
    }

    /**
     * Registers the saved connections. Entries whose password was not saved, or cannot be decrypted, come back
     * locked. Returns one message per problem (never containing a password).
     */
    public List<String> loadSaved() {
        List<String> problems = new ArrayList<>();
        if (store == null) return problems;
        List<ConnectionStore.Saved> saved;
        try {
            saved = store.load();
        } catch (IOException e) {
            problems.add("Could not read " + store.file() + ": " + e.getMessage());
            return problems;
        }
        for (ConnectionStore.Saved s : saved) {
            char[] pw = null;
            if (s.password() != null) {
                try {
                    pw = store.decrypt(s);
                } catch (IOException e) {
                    problems.add("'" + s.nickname() + "': " + e.getMessage() + "; it is locked until you enter the password.");
                }
            }
            try {
                ConnectionInfo info = build(s.nickname(), s.host(), s.port(), s.serviceName(), s.username(), s.schema(),
                        pw, true, s.password());
                if (connections.putIfAbsent(info.nickname(), info) != null) {
                    info.wipe();
                    problems.add("'" + s.nickname() + "' is saved twice; the second entry was ignored.");
                }
            } catch (IllegalArgumentException e) {
                if (pw != null) Arrays.fill(pw, '\0');
                problems.add("Saved connection '" + s.nickname() + "' is invalid and was ignored: " + e.getMessage());
            }
        }
        return problems;
    }

    /** Gives a locked (or any) connection its password; optionally saves it, encrypted. */
    public ConnectionView unlock(String nickname, char[] password, boolean savePassword) {
        if (password == null || password.length == 0) throw new IllegalArgumentException("Password is required.");
        ConnectionInfo info = require(nickname);
        if (savePassword && !info.remembered()) {
            throw new IllegalArgumentException("Only saved connections can save their password.");
        }
        info.unlock(password.clone());
        Arrays.fill(password, '\0');
        if (savePassword) {
            try {
                info.savedPassword(store.encrypt(info.password(), info.toSaved(null)));
                persist();
            } catch (IOException e) {
                throw new IllegalArgumentException("Password set for this session, but could not be saved: " + e.getMessage());
            }
        }
        return info.view();
    }

    public List<ConnectionView> list() {
        List<ConnectionView> out = new ArrayList<>();
        for (ConnectionInfo c : connections.values()) out.add(c.view());
        out.sort(Comparator.comparing(ConnectionView::nickname, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** Removes the connection, including its saved entry. */
    public boolean remove(String nickname) {
        ConnectionInfo removed = connections.remove(nickname);
        if (removed == null) return false;
        removed.wipe();
        if (removed.remembered()) {
            try {
                persist();
            } catch (IOException e) {
                log.warn("Removed '{}' from this session, but could not update the saved connections: {}", nickname,
                        e.getMessage());
                throw new IllegalArgumentException("Removed for this session, but the saved copy could not be deleted: "
                        + e.getMessage());
            }
        }
        return true;
    }

    private synchronized void persist() throws IOException {
        List<ConnectionStore.Saved> out = new ArrayList<>();
        for (ConnectionInfo c : connections.values()) {
            if (c.remembered()) out.add(c.toSaved(c.savedPassword()));
        }
        out.sort(Comparator.comparing(ConnectionStore.Saved::nickname, String.CASE_INSENSITIVE_ORDER));
        store.save(out);
    }

    private static ConnectionInfo build(String nickname0, String host0, Integer port0, String service0, String user0,
                                        String schema0, char[] pw, boolean remembered, ConnectionStore.Encrypted saved) {
        String nickname = trim(nickname0);
        if (!NICKNAME.matcher(nickname).matches()) {
            throw new IllegalArgumentException(
                    "Nickname must start with a letter and contain only letters, digits, '_' or '-' (max 30).");
        }
        String host = trim(host0);
        if (host.isEmpty()) host = "localhost";
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("Host contains invalid characters.");
        }
        int port = port0 == null ? 1521 : port0;
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535.");
        }
        String service = trim(service0);
        if (!SERVICE.matcher(service).matches()) {
            throw new IllegalArgumentException("Service name is required and may contain only letters, digits and _ . $ # -");
        }
        String user = trim(user0);
        if (!IDENT.matcher(user).matches()) {
            throw new IllegalArgumentException("Username is required (letters, digits, _ $ #).");
        }
        String schema = trim(schema0);
        if (!schema.isEmpty() && !IDENT.matcher(schema).matches()) {
            throw new IllegalArgumentException("Schema name is invalid (letters, digits, _ $ #).");
        }
        return new ConnectionInfo(nickname, host, port, service, user, pw, schema.isEmpty() ? null : schema,
                remembered, saved);
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
        if (info.locked()) return new TestResult(false, "Locked: enter the password first.", null, null, 0);
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
