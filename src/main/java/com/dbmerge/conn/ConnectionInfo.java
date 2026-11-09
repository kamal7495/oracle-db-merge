package com.dbmerge.conn;

import java.util.Arrays;
import java.util.Locale;

/**
 * A registered connection. The password lives in this object's memory and is wiped on removal. A saved
 * connection whose password was not saved (or could not be decrypted) is <em>locked</em>: its password is null
 * until it is unlocked.
 */
public final class ConnectionInfo {
    private final String nickname;
    private final String host;
    private final int port;
    private final String serviceName;
    private final String username;
    private final String schema; // optional; defaults to the username
    private char[] password;              // null while locked
    private final boolean remembered;     // saved to the connection store
    private ConnectionStore.Encrypted savedPassword; // ciphertext kept as saved, or null

    ConnectionInfo(String nickname, String host, int port, String serviceName,
                   String username, char[] password, String schema) {
        this(nickname, host, port, serviceName, username, password, schema, false, null);
    }

    ConnectionInfo(String nickname, String host, int port, String serviceName, String username, char[] password,
                   String schema, boolean remembered, ConnectionStore.Encrypted savedPassword) {
        this.nickname = nickname;
        this.host = host;
        this.port = port;
        this.serviceName = serviceName;
        this.username = username;
        this.password = password;
        this.schema = schema;
        this.remembered = remembered;
        this.savedPassword = savedPassword;
    }

    public String nickname() { return nickname; }
    public String host() { return host; }
    public int port() { return port; }
    public String serviceName() { return serviceName; }
    public String username() { return username; }
    public String schemaOverride() { return schema; }

    /** Schema (owner) whose objects are compared: explicit override, otherwise the connecting user. */
    public String effectiveSchema() {
        return (schema == null || schema.isBlank() ? username : schema).toUpperCase(Locale.ROOT);
    }

    public String jdbcUrl() {
        return "jdbc:oracle:thin:@//" + host + ":" + port + "/" + serviceName;
    }

    synchronized char[] password() {
        if (password == null) {
            throw new IllegalArgumentException("Connection '" + nickname + "' is locked: enter its password first.");
        }
        return password;
    }

    synchronized boolean locked() { return password == null; }

    synchronized void unlock(char[] pw) {
        if (password != null) Arrays.fill(password, '\0');
        password = pw;
    }

    boolean remembered() { return remembered; }

    synchronized ConnectionStore.Encrypted savedPassword() { return savedPassword; }

    synchronized void savedPassword(ConnectionStore.Encrypted e) { savedPassword = e; }

    /** The store entry for this connection (with the given ciphertext, or none). */
    ConnectionStore.Saved toSaved(ConnectionStore.Encrypted pw) {
        return new ConnectionStore.Saved(nickname, host, port, serviceName, username, schema, pw);
    }

    synchronized void wipe() {
        if (password != null) Arrays.fill(password, '\0');
    }

    public synchronized ConnectionView view() {
        return new ConnectionView(nickname, host, port, serviceName, username, effectiveSchema(),
                remembered, savedPassword != null, password == null);
    }

    @Override
    public String toString() {
        return "ConnectionInfo[" + nickname + " @ " + host + ":" + port + "/" + serviceName
                + ", user=" + username + ", password=****]";
    }
}
