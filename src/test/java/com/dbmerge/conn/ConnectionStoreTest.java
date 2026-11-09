package com.dbmerge.conn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionStoreTest {

    private static final String PW = "S3cret-Pa$$wörd";

    @TempDir Path dir;

    private static ConnectionRequest req(String nick, boolean save, boolean savePw) {
        return new ConnectionRequest(nick, "db.example", 1521, "FREEPDB1", "scott", PW.toCharArray(), null, save, savePw);
    }

    private ConnectionRegistry registry() {
        return new ConnectionRegistry(new ConnectionStore(dir, null));
    }

    private static boolean posix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    @Test
    void savedConnectionWithPasswordComesBackUnlocked() throws Exception {
        registry().add(req("DB_A", true, true));
        String json = Files.readString(dir.resolve("connections.json"));
        assertFalse(json.contains(PW), "the password must not be stored in plain text");
        assertTrue(json.contains("db.example"));

        ConnectionRegistry after = registry();
        assertTrue(after.loadSaved().isEmpty());
        ConnectionView v = after.list().get(0);
        assertTrue(v.saved() && v.passwordSaved());
        assertFalse(v.locked());
        assertEquals(PW, new String(after.require("DB_A").password()));
    }

    @Test
    void savedWithoutPasswordComesBackLockedAndCanBeUnlocked() throws Exception {
        registry().add(req("DB_A", true, false));
        assertFalse(Files.readString(dir.resolve("connections.json")).contains(PW));
        assertFalse(Files.exists(dir.resolve("master.key")), "no key is needed when no password is saved");

        ConnectionRegistry after = registry();
        after.loadSaved();
        assertTrue(after.list().get(0).locked());
        assertFalse(after.test("DB_A").ok());
        assertThrows(IllegalArgumentException.class, () -> after.require("DB_A").password());

        after.unlock("DB_A", PW.toCharArray(), true);
        assertFalse(after.list().get(0).locked());
        ConnectionRegistry third = registry();
        third.loadSaved();
        assertFalse(third.list().get(0).locked(), "unlock with save stores the password");
    }

    @Test
    void unsavedConnectionsAreNotWritten() {
        registry().add(req("DB_A", false, false));
        assertFalse(Files.exists(dir.resolve("connections.json")));
    }

    @Test
    void removeDeletesTheSavedCopy() {
        ConnectionRegistry r = registry();
        r.add(req("DB_A", true, true));
        r.add(req("DB_B", true, false));
        r.remove("DB_A");
        ConnectionRegistry after = registry();
        after.loadSaved();
        assertEquals(List.of("DB_B"), after.list().stream().map(ConnectionView::nickname).toList());
    }

    @Test
    void editingTheHostMakesTheSavedPasswordUnusable() throws Exception {
        registry().add(req("DB_A", true, true));
        Path f = dir.resolve("connections.json");
        Files.writeString(f, Files.readString(f).replace("db.example", "attacker.example"));

        ConnectionRegistry after = registry();
        List<String> problems = after.loadSaved();
        assertEquals(1, problems.size(), problems.toString());
        assertFalse(problems.get(0).contains(PW));
        assertTrue(after.list().get(0).locked(), "the password is not sent to the edited host");
    }

    @Test
    void aDifferentKeyLeavesTheConnectionLocked() {
        byte[] k1 = new byte[32];
        byte[] k2 = new byte[32];
        k2[0] = 1;
        new ConnectionRegistry(new ConnectionStore(dir, Base64.getEncoder().encodeToString(k1))).add(req("DB_A", true, true));
        assertFalse(Files.exists(dir.resolve("master.key")), "the environment key is used instead of a key file");

        ConnectionRegistry after = new ConnectionRegistry(new ConnectionStore(dir, Base64.getEncoder().encodeToString(k2)));
        assertEquals(1, after.loadSaved().size());
        assertTrue(after.list().get(0).locked());
    }

    @Test
    void filesAreOwnerOnlyAndAWorldReadableKeyIsRefused() throws Exception {
        assumeTrue(posix());
        registry().add(req("DB_A", true, true));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("master.key"))));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve("connections.json"))));

        Files.setPosixFilePermissions(dir.resolve("master.key"), PosixFilePermissions.fromString("rw-r--r--"));
        ConnectionRegistry after = registry();
        List<String> problems = after.loadSaved();
        assertEquals(1, problems.size());
        assertTrue(after.list().get(0).locked());
        assertThrows(IllegalArgumentException.class, () -> after.add(req("DB_B", true, true)));
        assertTrue(after.list().stream().noneMatch(v -> v.nickname().equals("DB_B")), "a failed save is not kept");
    }

    @Test
    void savingNeedsAStore() {
        assertThrows(IllegalArgumentException.class, () -> new ConnectionRegistry().add(req("DB_A", true, false)));
        assertNull(new ConnectionRegistry().list().stream().findFirst().orElse(null));
    }
}
