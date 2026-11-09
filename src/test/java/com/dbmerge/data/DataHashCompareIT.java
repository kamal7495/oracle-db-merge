package com.dbmerge.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.SqlScriptRunner;
import com.dbmerge.conn.ConnectionRegistry;
import com.dbmerge.conn.ConnectionRequest;
import com.dbmerge.data.DataModel.DataReport;
import com.dbmerge.data.DataModel.Status;
import com.dbmerge.data.DataModel.TableDataResult;
import com.dbmerge.ddl.SchemaExtractor;
import com.dbmerge.ddl.model.SchemaModel;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

/**
 * The server-side hash comparison must give the same counts as the in-memory one. A tiny row limit forces every
 * table onto the hash path; the same data compared with a high limit is the reference. Execute with: mvn verify
 */
@Testcontainers(disabledWithoutDocker = true)
class DataHashCompareIT {

    private static final String USER_PW = "Sample_pw_1";

    @Container
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withPassword("Sys_pw_1");

    static ConnectionRegistry registry;

    static String url() {
        return "jdbc:oracle:thin:@//" + ORACLE.getHost() + ":" + ORACLE.getOraclePort() + "/" + ORACLE.getDatabaseName();
    }

    @BeforeAll
    static void createSchemas() throws Exception {
        try (Connection sys = DriverManager.getConnection(url(), "system", ORACLE.getPassword());
             Statement s = sys.createStatement()) {
            for (String u : List.of("SAMPLE_A", "SAMPLE_B")) {
                s.execute("CREATE USER " + u + " IDENTIFIED BY \"" + USER_PW + "\"");
                s.execute("GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE TRIGGER, UNLIMITED TABLESPACE TO " + u);
            }
        }
        try (Connection a = DriverManager.getConnection(url(), "SAMPLE_A", USER_PW)) {
            SqlScriptRunner.run(a, "/sample/schema_a.sql");
        }
        try (Connection b = DriverManager.getConnection(url(), "SAMPLE_B", USER_PW)) {
            SqlScriptRunner.run(b, "/sample/schema_b.sql");
        }
        // A bigger table (25 000 rows, several buckets) with every hashed type, a few differences and LOBs.
        for (String u : List.of("SAMPLE_A", "SAMPLE_B")) {
            try (Connection c = DriverManager.getConnection(url(), u, USER_PW); Statement s = c.createStatement()) {
                s.execute("CREATE TABLE trade (trade_id NUMBER(10) PRIMARY KEY, ext_ref VARCHAR2(30) NOT NULL, "
                        + "notional NUMBER(18,2), rate BINARY_DOUBLE, trade_date DATE, booked_at TIMESTAMP(6) WITH TIME ZONE, "
                        + "flag CHAR(1), raw_id RAW(8), note CLOB, payload BLOB)");
                s.execute("INSERT INTO trade SELECT level, 'EXT-' || level, level * 1000.25, level / 7, "
                        + "DATE '2026-01-01' + MOD(level, 365), TIMESTAMP '2026-01-01 10:00:00 +01:00' + NUMTODSINTERVAL(level, 'SECOND'), "
                        + "CASE WHEN MOD(level, 3) = 0 THEN NULL ELSE 'Y' END, HEXTORAW(LPAD(TO_CHAR(level, 'FMXXXX'), 16, '0')), "
                        + "CASE WHEN MOD(level, 1000) = 0 THEN TO_CLOB('note ' || level) END, "
                        + "CASE WHEN MOD(level, 1000) = 0 THEN HEXTORAW('CAFE') END FROM dual CONNECT BY level <= 25000");
                if (u.equals("SAMPLE_B")) {
                    s.execute("UPDATE trade SET notional = notional + 0.01 WHERE trade_id IN (10, 20000)");  // conflicts
                    s.execute("UPDATE trade SET booked_at = booked_at + INTERVAL '1' SECOND WHERE trade_id = 777");
                    s.execute("UPDATE trade SET note = TO_CLOB('changed') WHERE trade_id = 5000");          // LOB-only change
                    s.execute("DELETE FROM trade WHERE trade_id BETWEEN 100 AND 104");                      // only in A
                    s.execute("INSERT INTO trade (trade_id, ext_ref) VALUES (30000, 'EXT-NEW')");           // only in B
                    // Same instant written in another time zone: equal after normalising to UTC.
                    s.execute("UPDATE trade SET booked_at = booked_at AT TIME ZONE '-05:00' WHERE trade_id = 1234");
                    // Business key EXT-15 moves to another id: PK divergence and a PK collision on 15.
                    s.execute("UPDATE trade SET ext_ref = 'EXT-MOVED' WHERE trade_id = 15");
                    s.execute("UPDATE trade SET ext_ref = 'EXT-15' WHERE trade_id = 16");
                }
                s.execute("COMMIT");
            }
        }
        registry = new ConnectionRegistry();
        for (String[] db : new String[][] {{"DB_A", "SAMPLE_A"}, {"DB_B", "SAMPLE_B"}}) {
            registry.add(new ConnectionRequest(db[0], ORACLE.getHost(), ORACLE.getOraclePort(),
                    ORACLE.getDatabaseName(), db[1], USER_PW.toCharArray(), null));
        }
    }

    static List<SchemaModel> models() throws Exception {
        SchemaExtractor ex = new SchemaExtractor(registry);
        return List.of(ex.extract("DB_A", false), ex.extract("DB_B", false));
    }

    static Map<String, TableDataResult> byTable(DataReport r) {
        return r.tables().stream().collect(Collectors.toMap(TableDataResult::table, Function.identity()));
    }

    static void assertSameCounts(TableDataResult mem, TableDataResult hash) {
        String t = mem.table();
        assertEquals(mem.status(), hash.status(), t + ": " + hash.message());
        if (mem.status() != Status.COMPARED) return;
        assertEquals(mem.onlyInCounts(), hash.onlyInCounts(), t);
        assertEquals(mem.identicalCount(), hash.identicalCount(), t);
        assertEquals(mem.conflictCount(), hash.conflictCount(), t);
        assertEquals(mem.pkCollisionCount(), hash.pkCollisionCount(), t);
        assertEquals(mem.pkDivergenceCount(), hash.pkDivergenceCount(), t);
    }

    @Test
    void sampleSchemasGiveTheSameCountsAsInMemory() throws Exception {
        List<SchemaModel> m = models();
        Map<String, List<String>> keys = Map.of("CUSTOMER", List.of("EMAIL"));
        Set<String> tables = Set.of("REGION", "PRODUCT", "CUSTOMER", "ORDERS", "ORDER_ITEM", "AUDIT_NOTE");
        Map<String, TableDataResult> mem = byTable(new DataCompareService(registry, 1_000_000).run("it", m, keys, tables, 100));
        Map<String, TableDataResult> hash = byTable(new DataCompareService(registry, 1).run("it", m, keys, tables, 100));
        for (String t : mem.keySet()) {
            assertSameCounts(mem.get(t), hash.get(t));
            if (mem.get(t).status() == Status.COMPARED && mem.get(t).rowCounts().values().stream().anyMatch(n -> n > 1)) {
                assertNotNull(hash.get(t).message(), t + " should report the hash strategy");
                assertNull(mem.get(t).message());
            }
        }
        // Conflicts are listed with their rows and the columns that differ.
        TableDataResult c = hash.get("CUSTOMER");
        for (var conflict : c.conflicts()) {
            assertFalse(conflict.rowsByDb().values().iterator().next().isEmpty());
            assertFalse(conflict.differingColumns().isEmpty());
        }
    }

    @Test
    void largeTableOnThePrimaryKey() throws Exception {
        List<SchemaModel> m = models();
        TableDataResult mem = new DataCompareService(registry, 1_000_000).run("it", m, Map.of(), Set.of("TRADE"), 50).tables().get(0);
        TableDataResult hash = new DataCompareService(registry, 1_000).run("it", m, Map.of(), Set.of("TRADE"), 50).tables().get(0);
        assertEquals(Status.COMPARED, hash.status(), hash.message());
        assertTrue(hash.message().contains("server-side hashes") && hash.message().contains("25000 rows")
                && hash.message().contains("(3 bucket(s))"), hash.message());
        // Without DBMS_CRYPTO the LOB columns are left out, so the CLOB-only change on 5000 is not seen.
        assertTrue(hash.message().contains("NOTE (CLOB)") && hash.message().contains("PAYLOAD (BLOB)"), hash.message());
        assertEquals(Map.of("DB_A", 5L, "DB_B", 1L), hash.onlyInCounts());
        assertEquals(mem.onlyInCounts(), hash.onlyInCounts());
        // 10, 20000 (notional), 777 (timestamp), 15 and 16 (ext_ref). 1234 is the same instant, equal in UTC.
        assertEquals(Set.of("TRADE_ID=10", "TRADE_ID=20000", "TRADE_ID=777", "TRADE_ID=15", "TRADE_ID=16"),
                hash.conflicts().stream().map(DataModel.Conflict::key).collect(Collectors.toSet()));
        assertEquals(24_995 - 5, hash.identicalCount());
        assertEquals(24_995, hash.identicalCount() + hash.conflictCount());
        assertEquals(mem.identicalCount() + mem.conflictCount(), hash.identicalCount() + hash.conflictCount());
        assertTrue(hash.identicalRows().isEmpty() || hash.identicalRows().size() <= 50);
    }

    @Test
    void largeTableOnABusinessKeyFindsDivergencesAndCollisions() throws Exception {
        List<SchemaModel> m = models();
        Map<String, List<String>> keys = Map.of("TRADE", List.of("EXT_REF"));
        TableDataResult mem = new DataCompareService(registry, 1_000_000).run("it", m, keys, Set.of("TRADE"), 50).tables().get(0);
        TableDataResult hash = new DataCompareService(registry, 1_000).run("it", m, keys, Set.of("TRADE"), 50).tables().get(0);
        assertEquals(mem.pkDivergenceCount(), hash.pkDivergenceCount());
        assertEquals(mem.pkCollisionCount(), hash.pkCollisionCount());
        assertTrue(hash.pkDivergenceCount() >= 1 && hash.pkCollisionCount() >= 1,
                hash.pkDivergences() + " / " + hash.pkCollisions());
        assertEquals(mem.onlyInCounts(), hash.onlyInCounts());
    }

    @Test
    void lobsAreHashedWhenDbmsCryptoIsGranted() throws Exception {
        try (Connection sys = DriverManager.getConnection(url(), "sys as sysdba", ORACLE.getPassword());
             Statement s = sys.createStatement()) {
            for (String u : List.of("SAMPLE_A", "SAMPLE_B")) s.execute("GRANT EXECUTE ON SYS.DBMS_CRYPTO TO " + u);
        }
        try {
            List<SchemaModel> m = models();
            TableDataResult mem = new DataCompareService(registry, 1_000_000).run("it", m, Map.of(), Set.of("TRADE"), 50).tables().get(0);
            TableDataResult hash = new DataCompareService(registry, 1_000).run("it", m, Map.of(), Set.of("TRADE"), 50).tables().get(0);
            assertFalse(hash.message().contains("Not compared"), hash.message());
            assertTrue(hash.comparedColumns().containsAll(List.of("NOTE", "PAYLOAD")));
            assertEquals(6, hash.conflictCount(), "now the CLOB-only change on 5000 is seen too");
            assertTrue(hash.conflicts().stream().anyMatch(c -> c.key().equals("TRADE_ID=5000") && c.differingColumns().equals(List.of("NOTE"))),
                    hash.conflicts().toString());
            assertEquals(mem.onlyInCounts(), hash.onlyInCounts());
            assertEquals(mem.identicalCount() + mem.conflictCount(), hash.identicalCount() + hash.conflictCount());
        } finally {
            try (Connection sys = DriverManager.getConnection(url(), "sys as sysdba", ORACLE.getPassword());
                 Statement s = sys.createStatement()) {
                for (String u : List.of("SAMPLE_A", "SAMPLE_B")) s.execute("REVOKE EXECUTE ON SYS.DBMS_CRYPTO FROM " + u);
            }
        }
    }
}
