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
                s.execute("GRANT CREATE VIEW, CREATE PROCEDURE, CREATE SYNONYM TO " + u);
            }
            s.execute("CREATE ROLE DBM_REPORTING");
        }
        try (Connection a = DriverManager.getConnection(url(), "SAMPLE_A", USER_PW)) {
            SqlScriptRunner.run(a, "/sample/schema_a.sql");
            createCodeObjects(a, true);
        }
        try (Connection b = DriverManager.getConnection(url(), "SAMPLE_B", USER_PW)) {
            SqlScriptRunner.run(b, "/sample/schema_b.sql");
            createCodeObjects(b, false);
        }
        registry = new ConnectionRegistry();
        for (String[] db : new String[][] {{"DB_A", "SAMPLE_A"}, {"DB_B", "SAMPLE_B"}}) {
            registry.add(new ConnectionRequest(db[0], ORACLE.getHost(), ORACLE.getOraclePort(),
                    ORACLE.getDatabaseName(), db[1], USER_PW.toCharArray(), null));
        }
    }

    /**
     * Same view and package spec in both (written differently: case, comments, whitespace, schema prefix);
     * different package body; a procedure, a synonym and a grant only in A; a function INVALID only in B.
     */
    private static void createCodeObjects(Connection c, boolean isA) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(isA
                    ? "CREATE VIEW v_customer_email AS SELECT customer_id, email FROM sample_a.customer -- emails"
                    : "CREATE VIEW V_CUSTOMER_EMAIL AS\n  select CUSTOMER_ID,\n         EMAIL\n    from CUSTOMER");
            s.execute(isA
                    ? "CREATE PACKAGE pkg_pricing AS\n  FUNCTION discount RETURN NUMBER;\nEND pkg_pricing;"
                    : "CREATE PACKAGE PKG_PRICING AS /* same spec */ function DISCOUNT return number; END PKG_PRICING;");
            s.execute("CREATE PACKAGE BODY pkg_pricing AS\n  FUNCTION discount RETURN NUMBER IS\n  BEGIN\n    RETURN "
                    + (isA ? "5" : "10") + ";\n  END;\nEND pkg_pricing;");
            s.execute("CREATE SYNONYM s_customer FOR customer");
            // Compiles with errors in B (unknown identifier): Oracle creates it INVALID without raising an error.
            s.execute("CREATE FUNCTION f_region_count RETURN NUMBER IS n NUMBER; BEGIN SELECT COUNT(*) INTO n FROM "
                    + (isA ? "region" : "no_such_table") + "; RETURN n; END;");
            if (isA) {
                s.execute("CREATE PROCEDURE only_in_a IS BEGIN NULL; END;");
                s.execute("CREATE SYNONYM s_region FOR region");
                s.execute("GRANT SELECT ON region TO DBM_REPORTING");
            }
        }
    }

    @Test
    void extractsViewsPlsqlSynonymsAndGrants() throws SQLException {
        SchemaModel a = new SchemaExtractor(registry).extract("DB_A", false);
        assertTrue(a.codeObjects().containsKey("VIEW:V_CUSTOMER_EMAIL"), a.codeObjects().keySet().toString());
        assertTrue(a.codeObjects().containsKey("PACKAGE:PKG_PRICING"));
        assertTrue(a.codeObjects().containsKey("PACKAGE BODY:PKG_PRICING"));
        assertTrue(a.codeObjects().containsKey("PROCEDURE:ONLY_IN_A"));
        assertEquals("VALID", a.codeObjects().get("FUNCTION:F_REGION_COUNT").status());
        assertEquals(com.dbmerge.ddl.model.SynonymDef.OWN_SCHEMA, a.synonyms().get("S_CUSTOMER").targetOwner());
        assertTrue(a.grants().containsKey("REGION:SELECT:DBM_REPORTING"), a.grants().keySet().toString());
        SchemaModel b = new SchemaExtractor(registry).extract("DB_B", false);
        assertEquals("INVALID", b.codeObjects().get("FUNCTION:F_REGION_COUNT").status());
    }

    @Test
    void comparesViewsPlsqlSynonymsAndGrants() throws SQLException {
        SchemaExtractor ex = new SchemaExtractor(registry);
        ComparisonResult r = new SchemaComparator().compare(List.of(ex.extract("DB_A", false), ex.extract("DB_B", false)));

        assertHas(r, DiffKind.PLSQL_SOURCE_MISMATCH, "PACKAGE BODY PKG_PRICING", Severity.MEDIUM);
        assertHas(r, DiffKind.MISSING_PLSQL, "PROCEDURE ONLY_IN_A", Severity.HIGH);
        assertHas(r, DiffKind.OBJECT_STATUS_MISMATCH, "FUNCTION F_REGION_COUNT", Severity.MEDIUM);
        assertHas(r, DiffKind.MISSING_SYNONYM, "SYNONYM S_REGION", Severity.MEDIUM);
        assertHas(r, DiffKind.MISSING_GRANT, "GRANT SELECT ON REGION TO DBM_REPORTING", Severity.MEDIUM);
        Difference body = r.differences().stream().filter(d -> d.object().equals("PACKAGE BODY PKG_PRICING")).findFirst().orElseThrow();
        assertTrue(body.description().contains("RETURN 5;") && body.description().contains("RETURN 10;"), body.description());
        // Written differently but the same: no findings.
        for (String same : List.of("VIEW V_CUSTOMER_EMAIL", "PACKAGE PKG_PRICING", "SYNONYM S_CUSTOMER")) {
            assertTrue(r.differences().stream().noneMatch(d -> d.object().equals(same)), same + " is the same in both: "
                    + r.differences().stream().filter(d -> d.object().equals(same)).toList());
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
