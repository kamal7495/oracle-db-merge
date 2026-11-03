package com.dbmerge.conn;

/** What the API/UI may see of a connection: everything except the password. */
public record ConnectionView(
        String nickname,
        String host,
        int port,
        String serviceName,
        String username,
        String schema) {
}
