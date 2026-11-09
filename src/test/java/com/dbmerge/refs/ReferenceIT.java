package com.dbmerge.refs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.conn.ConnectionRegistry;
import com.dbmerge.conn.ConnectionRequest;
import com.dbmerge.ddl.SchemaExtractor;
import com.dbmerge.refs.RefModel.Candidate;
import com.dbmerge.refs.RefModel.DataCheck;
import com.dbmerge.refs.RefModel.Kind;
import com.dbmerge.refs.RefModel.ReferenceReport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

/**
 * Undeclared references against a real Oracle: a Calypso-style schema with NO declared foreign keys, and data
 * that is deliberately imperfect (an orphan, a 1:1 table, an ambiguous reference, a type mismatch). Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class ReferenceIT {

    private static final String USER_PW = "Trading_pw_1";

    @Container
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart").withPassword("Sys_pw_1");

    static ConnectionRegistry registry;

    static String url() {
        return "jdbc:oracle:thin:@//" + ORACLE.getHost() + ":" + ORACLE.getOraclePort() + "/" + ORACLE.getDatabaseName();
    }

    @BeforeAll
    static void setUp() throws Exception {
        try (Connection sys = DriverManager.getConnection(url(), "system", ORACLE.getPassword()); Statement s = sys.createStatement()) {
            s.execute("CREATE USER TRADING IDENTIFIED BY \"" + USER_PW + "\"");
            s.execute("GRANT CREATE SESSION, CREATE TABLE, UNLIMITED TABLESPACE TO TRADING");
        }
        try (Connection c = DriverManager.getConnection(url(), "TRADING", USER_PW); Statement s = c.createStatement()) {
            for (String sql : List.of(
                    "CREATE TABLE legal_entity (legal_entity_id NUMBER(10) PRIMARY KEY, short_name VARCHAR2(64) NOT NULL)",
                    "CREATE TABLE book (book_id NUMBER(10) PRIMARY KEY, legal_entity_id NUMBER(10) NOT NULL, book_name VARCHAR2(64))",
                    "CREATE TABLE product_desc (product_id NUMBER(10) PRIMARY KEY, product_type VARCHAR2(32))",
                    "CREATE TABLE product_bond (product_id NUMBER(10) PRIMARY KEY, coupon NUMBER(12,6))",
                    "CREATE TABLE trade (trade_id NUMBER(10) PRIMARY KEY, product_id NUMBER(10), book_id NUMBER(10),"
                            + " parent_trade_id NUMBER(10), trade_date DATE)",
                    "CREATE INDEX ix_trade_book ON trade (book_id)",
                    "CREATE TABLE trade_keyword (trade_id NUMBER(10), keyword_name VARCHAR2(64), keyword_value VARCHAR2(255),"
                            + " PRIMARY KEY (trade_id, keyword_name))",
                    "CREATE TABLE bo_message (message_id NUMBER(10) PRIMARY KEY, object_id NUMBER(10), object_class VARCHAR2(64),"
                            + " trade_id VARCHAR2(32))",
                    "INSERT INTO legal_entity VALUES (1, 'PO_LONDON')",
                    "INSERT INTO book VALUES (10, 1, 'RATES_LDN')",
                    "INSERT INTO product_desc VALUES (1, 'Swap')",
                    "INSERT INTO product_desc VALUES (2, 'Bond')",
                    "INSERT INTO product_desc VALUES (3, 'Bond')",
                    "INSERT INTO product_bond VALUES (2, 4.25)",
                    "INSERT INTO product_bond VALUES (3, 3.1)",
                    "INSERT INTO trade VALUES (100, 1, 10, NULL, SYSDATE)",
                    "INSERT INTO trade VALUES (101, 2, 10, 100, SYSDATE)",
                    "INSERT INTO trade_keyword VALUES (100, 'Desk', 'Rates')",
                    "INSERT INTO trade_keyword VALUES (999, 'Desk', 'Credit')",              // orphan: trade 999 does not exist
                    "INSERT INTO bo_message VALUES (1, 100, 'Trade', '100')",
                    "INSERT INTO bo_message VALUES (2, 2, 'Product', 'not-a-number')",     // makes the NUMBER join fail
                    "COMMIT")) {
                s.execute(sql);
            }
        }
        registry = new ConnectionRegistry();
        registry.add(new ConnectionRequest("TRD", ORACLE.getHost(), ORACLE.getOraclePort(), ORACLE.getDatabaseName(), "TRADING",
                USER_PW.toCharArray(), null));
    }

    static ReferenceService service() {
        return new ReferenceService(registry, new SchemaExtractor(registry));
    }

    static Map<String, Candidate> byColumn(ReferenceReport r) {
        return r.candidates().stream().collect(Collectors.toMap(Candidate::qualifiedColumn, Function.identity()));
    }

    static DataCheck check(Candidate c, String parent) {
        return c.dataChecks().stream().filter(d -> d.parentTable().equals(parent)).findFirst()
                .orElseThrow(() -> new AssertionError("no check against " + parent + " for " + c.qualifiedColumn()));
    }

    @Test
    void namesOnlyRunFindsEveryUndeclaredReferenceWithoutQueryingData() throws Exception {
        ReferenceReport r = service().run("TRD", false, 30, 1_000);
        assertEquals(0, r.declaredForeignKeys());
        assertEquals(7, r.tableCount());
        Map<String, Candidate> f = byColumn(r);
        for (String col : List.of("BOOK.LEGAL_ENTITY_ID", "TRADE.BOOK_ID", "TRADE.PRODUCT_ID", "TRADE.PARENT_TRADE_ID",
                "TRADE_KEYWORD.TRADE_ID", "BO_MESSAGE.TRADE_ID", "PRODUCT_BOND.PRODUCT_ID", "BO_MESSAGE.OBJECT_ID")) {
            assertNotNull(f.get(col), col + " should be reported; found " + f.keySet());
        }
        assertEquals(Kind.POLYMORPHIC, f.get("BO_MESSAGE.OBJECT_ID").kind());
        assertTrue(f.get("TRADE.BOOK_ID").indexed(), "IX_TRADE_BOOK");
        assertFalse(r.dataChecked());
        assertTrue(r.candidates().stream().allMatch(c -> c.dataChecks().isEmpty()));
    }

    @Test
    void dataCheckCountsOrphansAndDisambiguatesParents() throws Exception {
        ReferenceReport r = service().run("TRD", true, 30, 1_000);
        Map<String, Candidate> f = byColumn(r);

        DataCheck kw = check(f.get("TRADE_KEYWORD.TRADE_ID"), "TRADE");
        assertNull(kw.error());
        assertEquals(2, kw.nonNullValues());
        assertEquals(1, kw.unresolvedValues(), "trade 999 has no TRADE row");
        assertEquals(List.of("999"), kw.unresolvedExamples());

        assertEquals(0, check(f.get("TRADE.BOOK_ID"), "BOOK").unresolvedValues());
        assertEquals(0, check(f.get("BOOK.LEGAL_ENTITY_ID"), "LEGAL_ENTITY").unresolvedValues());
        DataCheck parent = check(f.get("TRADE.PARENT_TRADE_ID"), "TRADE");
        assertEquals(1, parent.nonNullValues(), "NULLs are not counted");
        assertEquals(0, parent.unresolvedValues());

        // Ambiguous TRADE.PRODUCT_ID: every value is in PRODUCT_DESC, the swap is not in PRODUCT_BOND.
        Candidate prod = f.get("TRADE.PRODUCT_ID");
        assertEquals(1.0, check(prod, "PRODUCT_DESC").resolvedShare());
        assertEquals(1, check(prod, "PRODUCT_BOND").unresolvedValues());

        // Shared key: bonds all exist as products (so PRODUCT_DESC is the parent), but not every product is a bond.
        assertEquals(0, check(f.get("PRODUCT_BOND.PRODUCT_ID"), "PRODUCT_DESC").unresolvedValues());
        assertEquals(1, check(f.get("PRODUCT_DESC.PRODUCT_ID"), "PRODUCT_BOND").unresolvedValues());

        // VARCHAR2 child of a NUMBER key: the comparison fails in Oracle; recorded, not thrown.
        DataCheck mismatch = check(f.get("BO_MESSAGE.TRADE_ID"), "TRADE");
        assertNotNull(mismatch.error());
        assertTrue(mismatch.error().contains("ORA-01722"), mismatch.error());

        String html = new ReferenceHtmlReportWriter().render(r);
        assertTrue(html.contains("TRADE_KEYWORD.TRADE_ID") && html.contains("50.0% of 2 resolve"), "report shows the data check");
        assertTrue(html.contains("ORA-01722"));
    }

    @Test
    void stopsAtTheQueryLimitAndSaysSo() throws Exception {
        ReferenceReport r = service().run("TRD", true, 30, 2);
        long checked = r.candidates().stream().filter(c -> !c.dataChecks().isEmpty()).count();
        assertTrue(checked >= 1 && checked <= 2, "at most 2 queries ran, checked " + checked);
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("limit of 2 queries")), r.warnings().toString());
    }
}
