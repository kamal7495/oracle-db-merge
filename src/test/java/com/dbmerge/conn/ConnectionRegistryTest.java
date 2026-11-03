package com.dbmerge.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConnectionRegistryTest {

    private static final String PW = "S3cret-Pa$$word";

    private static ConnectionRequest req(String nick, String host, Integer port, String svc, String user, String schema) {
        return new ConnectionRequest(nick, host, port, svc, user, PW.toCharArray(), schema);
    }

    @Test
    void addsListsAndNeverExposesThePassword() {
        ConnectionRegistry reg = new ConnectionRegistry();
        ConnectionView v = reg.add(req("DB_A", "", null, "FREEPDB1", "sample_a", null));
        assertEquals("localhost", v.host());
        assertEquals(1521, v.port());
        assertEquals("SAMPLE_A", v.schema());
        assertFalse(v.toString().contains(PW));
        assertFalse(reg.require("DB_A").toString().contains(PW));
        assertFalse(req("X", "h", 1, "s", "u", null).toString().contains(PW));
        assertEquals(1, reg.list().size());
        assertEquals("jdbc:oracle:thin:@//localhost:1521/FREEPDB1", reg.require("DB_A").jdbcUrl());
    }

    @Test
    void callersPasswordArrayIsWipedAfterAdd() {
        ConnectionRegistry reg = new ConnectionRegistry();
        char[] pw = PW.toCharArray();
        reg.add(new ConnectionRequest("DB_A", "localhost", 1521, "SVC", "scott", pw, null));
        for (char c : pw) assertEquals('\0', c);
    }

    @Test
    void schemaOverrideIsUsedWhenGiven() {
        ConnectionRegistry reg = new ConnectionRegistry();
        reg.add(req("DB_A", "localhost", 1521, "SVC", "scott", "hr"));
        assertEquals("HR", reg.schemaOf("DB_A"));
    }

    @Test
    void rejectsDuplicatesAndInvalidInput() {
        ConnectionRegistry reg = new ConnectionRegistry();
        reg.add(req("DB_A", "localhost", 1521, "SVC", "scott", null));
        assertThrows(IllegalArgumentException.class, () -> reg.add(req("DB_A", "localhost", 1521, "SVC", "scott", null)));
        assertThrows(IllegalArgumentException.class, () -> reg.add(req("1bad", "localhost", 1521, "SVC", "scott", null)));
        assertThrows(IllegalArgumentException.class, () -> reg.add(req("OK", "bad host", 1521, "SVC", "scott", null)));
        assertThrows(IllegalArgumentException.class, () -> reg.add(req("OK", "h:1521/x?y=z", 1521, "SVC", "scott", null)));
        assertThrows(IllegalArgumentException.class, () -> reg.add(req("OK", "localhost", 70000, "SVC", "scott", null)));
        assertThrows(IllegalArgumentException.class, () -> reg.add(req("OK", "localhost", 1521, "SVC)(HOST=evil", "scott", null)));
        assertThrows(IllegalArgumentException.class, () -> reg.add(req("OK", "localhost", 1521, "SVC", "sc ott", null)));
        assertThrows(IllegalArgumentException.class,
                () -> reg.add(new ConnectionRequest("OK", "localhost", 1521, "SVC", "scott", new char[0], null)));
        assertEquals(1, reg.list().size());
    }

    @Test
    void removeWipesAndForgets() {
        ConnectionRegistry reg = new ConnectionRegistry();
        reg.add(req("DB_A", "localhost", 1521, "SVC", "scott", null));
        assertTrue(reg.remove("DB_A"));
        assertFalse(reg.remove("DB_A"));
        assertThrows(IllegalArgumentException.class, () -> reg.require("DB_A"));
    }

    @Test
    void failedConnectionReportsErrorWithoutLeakingPassword() {
        ConnectionRegistry reg = new ConnectionRegistry();
        reg.add(req("DB_A", "127.0.0.1", 1, "SVC", "scott", null)); // nothing listens on port 1
        TestResult r = reg.test("DB_A");
        assertFalse(r.ok());
        assertFalse(r.message().contains(PW));
    }
}
