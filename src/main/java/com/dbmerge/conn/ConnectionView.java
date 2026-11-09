package com.dbmerge.conn;

/**
 * What the API/UI may see of a connection: everything except the password.
 *
 * @param saved         details are saved and come back after a restart
 * @param passwordSaved the password is saved too (encrypted)
 * @param locked        no password in memory: enter it before using the connection
 */
public record ConnectionView(
        String nickname,
        String host,
        int port,
        String serviceName,
        String username,
        String schema,
        boolean saved,
        boolean passwordSaved,
        boolean locked) {
}
