package com.dbmerge.ddl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.SqlScriptRunner;
import com.dbmerge.conn.ConnectionRegistry;
import com.dbmerge.conn.ConnectionRequest;
import com.dbmerge.ddl.compare.ComparisonResult;
import com.dbmerge.ddl.compare.DiffKind;
import com.dbmerge.ddl.compare.Difference;
import com.dbmerge.ddl.compare.SchemaComparator;
import com.dbmerge.ddl.compare.Severity;
import com.dbmerge.ddl.model.SchemaModel;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

/**
 * Runs against a real Oracle (gvenzl/oracle-free via Testcontainers). Skipped automatically when Docker is not
 * available. Execute with: mvn verify
 */
@Testcontainers(disabledWithoutDocker = true)
class SchemaExtractorIT {

    private static final String USER_PW = "Sample_pw_1";

    @Container
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
            .withPassword("Sys_pw_1");

    static ConnectionRegistry registry;

    static String url() {
        return "jdbc:oracle:thin:@//" + ORACLE.getHost() + ":" + ORACLE.getOraclePort() + "/" + ORACLE.getDatabaseName();
    }

    @BeforeAll
    static void createSampleSchemas() throws Exception {
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
        registry = new ConnectionRegistry();
        for (String[] db : new String[][] {{"DB_A", "SAMPLE_A"}, {"DB_B", "SAMPLE_B"}}) {
            registry.add(new ConnectionRequest(db[0], ORACLE.getHost(), ORACLE.getOraclePort(),
                    ORACLE.getDatabaseName(), db[1], USER_PW.toCharArray(), null));
        }
    }

    @Test
    void connectionTestSucceeds() {
        assertTrue(registry.test("DB_A").ok());
    }

    @Test
    void extractsTablesConstraintsAndSequences() throws SQLException {
        SchemaModel a = new SchemaExtractor(registry).extract("DB_A", true);
        assertEquals(Set.of("REGION", "PRODUCT", "CUSTOMER", "ORDERS", "ORDER_ITEM"), a.tables().keySet());
        assertEquals(Set.of("SEQ_CUSTOMER", "SEQ_ORDER"), a.sequences().keySet());
        assertEquals(3, a.tables().get("ORDER_ITEM").constraints().stream().filter(c -> !c.type().equals("C")).count());
        assertEquals(1, a.tables().get("CUSTOMER").triggers().size());
        assertTrue(a.ddl().containsKey("TABLE:CUSTOMER"));
    }

    @Test
    void comparesTheTwoSampleSchemas() throws SQLException {
        SchemaExtractor ex = new SchemaExtractor(registry);
        ComparisonResult r = new SchemaComparator().compare(List.of(ex.extract("DB_A", false), ex.extract("DB_B", false)));

        assertHas(r, DiffKind.MISSING_TABLE, "AUDIT_NOTE", Severity.HIGH);
        assertHas(r, DiffKind.MISSING_COLUMN, "CUSTOMER.PHONE", Severity.MEDIUM);
        assertHas(r, DiffKind.MISSING_COLUMN, "CUSTOMER.LOYALTY_TIER", Severity.MEDIUM);
        assertHas(r, DiffKind.LENGTH_MISMATCH, "CUSTOMER.EMAIL", Severity.HIGH);
        assertHas(r, DiffKind.LENGTH_MISMATCH, "PRODUCT.PRICE", Severity.HIGH);
        assertHas(r, DiffKind.TYPE_MISMATCH, "ORDER_ITEM.QTY", Severity.CRITICAL);
        assertHas(r, DiffKind.CONSTRAINT_DEFINITION_MISMATCH, "ORDERS.CK_ORDERS_STATUS", Severity.MEDIUM);
        assertHas(r, DiffKind.MISSING_TRIGGER, "CUSTOMER: TRG_CUSTOMER_BI", Severity.MEDIUM);
        assertHas(r, DiffKind.SEQUENCE_PARAM_MISMATCH, "SEQ_CUSTOMER", Severity.MEDIUM);
        assertTrue(r.differences().stream().anyMatch(d -> d.kind() == DiffKind.MISSING_INDEX
                && d.object().contains("INDEX(REGION_ID)")), "missing IX_CUSTOMER_REGION");
        // System-named PKs/FKs must not show up as differences.
        assertTrue(r.differences().stream().noneMatch(d -> d.kind() == DiffKind.MISSING_CONSTRAINT
                && d.object().startsWith("REGION")), "REGION constraints are identical");
    }

    @Test
    void sourceConnectionsAreReadOnly() throws SQLException {
        try (Connection c = registry.openReadOnly("DB_A"); Statement s = c.createStatement()) {
            SQLException e = assertThrows(SQLException.class,
                    () -> s.execute("INSERT INTO region (region_id, name) VALUES (99, 'Nope')"));
            assertTrue(e.getMessage().contains("ORA-01456"), e.getMessage());
        }
    }

    private static void assertHas(ComparisonResult r, DiffKind kind, String object, Severity sev) {
        Difference d = r.differences().stream().filter(x -> x.kind() == kind && x.object().equals(object)).findFirst()
                .orElseThrow(() -> new AssertionError("Expected " + kind + " on " + object + " but got " + r.differences()));
        assertEquals(sev, d.severity(), kind + " " + object);
    }
}
