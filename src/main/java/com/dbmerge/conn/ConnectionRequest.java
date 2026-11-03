package com.dbmerge.conn;

/** Payload from the UI form. The password is never printed or persisted. */
public record ConnectionRequest(
        String nickname,
        String host,
        Integer port,
        String serviceName,
        String username,
        char[] password,
        String schema) {

    @Override
    public String toString() {
        return "ConnectionRequest[" + nickname + "]";
    }
}
