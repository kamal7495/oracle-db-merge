package com.dbmerge.conn;

import java.util.Arrays;
import java.util.Locale;

/** A registered connection. The password lives only in this object's memory and is wiped on removal. */
public final class ConnectionInfo {
    private final String nickname;
    private final String host;
    private final int port;
    private final String serviceName;
    private final String username;
    private final String schema; // optional; defaults to the username
    private final char[] password;

    ConnectionInfo(String nickname, String host, int port, String serviceName,
                   String username, char[] password, String schema) {
        this.nickname = nickname;
        this.host = host;
        this.port = port;
        this.serviceName = serviceName;
        this.username = username;
        this.password = password;
        this.schema = schema;
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

    char[] password() { return password; }

    void wipe() { Arrays.fill(password, '\0'); }

    public ConnectionView view() {
        return new ConnectionView(nickname, host, port, serviceName, username, effectiveSchema());
    }

    @Override
    public String toString() {
        return "ConnectionInfo[" + nickname + " @ " + host + ":" + port + "/" + serviceName
                + ", user=" + username + ", password=****]";
    }
}
