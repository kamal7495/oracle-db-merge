package com.dbmerge.conn;

/**
 * Payload from the UI form. The password is never printed; it is written to disk only when
 * {@code savePassword} is set, and then only encrypted (see {@link ConnectionStore}).
 *
 * @param save         keep the connection details after a restart
 * @param savePassword also save the password, encrypted (implies {@code save})
 */
public record ConnectionRequest(
        String nickname,
        String host,
        Integer port,
        String serviceName,
        String username,
        char[] password,
        String schema,
        Boolean save,
        Boolean savePassword) {

    public ConnectionRequest(String nickname, String host, Integer port, String serviceName, String username,
                             char[] password, String schema) {
        this(nickname, host, port, serviceName, username, password, schema, false, false);
    }

    boolean wantsSave() { return Boolean.TRUE.equals(save) || wantsSavePassword(); }

    boolean wantsSavePassword() { return Boolean.TRUE.equals(savePassword); }

    @Override
    public String toString() {
        return "ConnectionRequest[" + nickname + "]";
    }
}
