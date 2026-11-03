package com.dbmerge.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.SqlScriptRunner;
import com.dbmerge.conn.ConnectionRegistry;
import com.dbmerge.conn.ConnectionRequest;
import com.dbmerge.ddl.SchemaExtractor;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.merge.MergeModel.ConflictAction;
import com.dbmerge.merge.MergeModel.MergePlan;
import com.dbmerge.merge.MergeModel.MergeResult;
import com.dbmerge.merge.MergeModel.Options;
import com.dbmerge.merge.MergeModel.RunStatus;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

/**
 * End-to-end merge of the sample schemas (A = master, B = source) against a real Oracle. Skipped without Docker.
 * The tests run in order and share one container: dry run, a run that must roll back, then a run that must succeed.
 * Run with: mvn verify
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MergeIT {

    private static final String USER_PW = "Sample_pw_1";

    @Container
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart").withPassword("Sys_pw_1");

    static ConnectionRegistry registry;
    static final Map<String, List<String>> KEYS = Map.of(
            "REGION", List.of("NAME"), "PRODUCT", List.of("SKU"), "CUSTOMER", List.of("EMAIL"));

    static String url() {
        return "jdbc:oracle:thin:@//" + ORACLE.getHost() + ":" + ORACLE.getOraclePort() + "/" + ORACLE.getDatabaseName();
    }

    @BeforeAll
    static void setUp() throws Exception {
        try (Connection sys = DriverManager.getConnection(url(), "system", ORACLE.getPassword()); Statement s = sys.createStatement()) {
            for (String u : List.of("SAMPLE_A", "SAMPLE_B")) {
                s.execute("CREATE USER " + u + " IDENTIFIED BY \"" + USER_PW + "\"");
                s.execute("GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE TRIGGER, UNLIMITED TABLESPACE TO " + u);
            }
        }
        try (Connection a = DriverManager.getConnection(url(), "SAMPLE_A", USER_PW)) { SqlScriptRunner.run(a, "/sample/schema_a.sql"); }
        try (Connection b = DriverManager.getConnection(url(), "SAMPLE_B", USER_PW)) { SqlScriptRunner.run(b, "/sample/schema_b.sql"); }
        registry = new ConnectionRegistry();
        for (String[] db : new String[][] {{"MASTER", "SAMPLE_A"}, {"SRC_B", "SAMPLE_B"}}) {
            registry.add(new ConnectionRequest(db[0], ORACLE.getHost(), ORACLE.getOraclePort(), ORACLE.getDatabaseName(), db[1], USER_PW.toCharArray(), null));
        }
    }

    static long count(String user, String table) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(), user, USER_PW); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static void exec(String user, String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(), user, USER_PW); Statement s = c.createStatement()) { s.execute(sql); }
    }

    static MergePlan plan(Rules rules) throws Exception {
        SchemaExtractor ex = new SchemaExtractor(registry);
        Map<String, SchemaModel> models = new LinkedHashMap<>();
        models.put("MASTER", ex.extract("MASTER", false));
        models.put("SRC_B", ex.extract("SRC_B", false));
        return new MergePlanner(new JdbcMergeData(registry, 100_000)).plan("MASTER", List.of("SRC_B"), models, KEYS, rules, Options.defaults());
    }

    static Rules keepMasterEverywhere() {
        Rules r = new Rules();
        for (String t : List.of("REGION", "PRODUCT", "CUSTOMER", "ORDERS", "ORDER_ITEM")) r.getTables().put(t, new Rules.TableRule(ConflictAction.KEEP_MASTER, null));
        return r;
    }

    static Set<String> allAlters(MergePlan p) {
        Set<String> ids = new LinkedHashSet<>();
        p.alters().forEach(a -> ids.add(a.id()));
        return ids;
    }

    @Test
    @Order(1)
    void dryRunChangesNothingAndBlocksOnUnresolvedConflicts() throws Exception {
        long customers = count("SAMPLE_A", "customer");
        long orders = count("SAMPLE_A", "orders");
        MergePlan p = plan(new Rules());
        assertTrue(p.hasBlockers(), "conflicting rows without a rule must block execution");
        String script = ScriptRenderer.render(p, Set.of());
        assertTrue(script.contains("Data Pump"));
        assertEquals(customers, count("SAMPLE_A", "customer"));
        assertEquals(orders, count("SAMPLE_A", "orders"));
        long b = count("SAMPLE_B", "customer");
        assertEquals(3, b);
        MergeResult refused = new MergeExecutor(registry).execute(p, allAlters(p));
        assertEquals(RunStatus.REFUSED, refused.status());
        assertEquals(customers, count("SAMPLE_A", "customer"));
    }

    @Test
    @Order(2)
    void checkConstraintViolationRollsTheWholeDataTransactionBack() throws Exception {
        // B has an order with status RETURNED, which the master's CHECK constraint forbids.
        long customers = count("SAMPLE_A", "customer");
        long orders = count("SAMPLE_A", "orders");
        MergePlan p = plan(keepMasterEverywhere());
        assertFalse(p.hasBlockers(), p.issues().toString());
        MergeResult r = new MergeExecutor(registry).execute(p, allAlters(p));
        assertEquals(RunStatus.FAILED, r.status());
        assertEquals(customers, count("SAMPLE_A", "customer"), "customers inserted before the failure must be rolled back");
        assertEquals(orders, count("SAMPLE_A", "orders"));
        assertEquals(3, count("SAMPLE_B", "customer"), "the source must never change");
    }

    @Test
    @Order(3)
    void mergeSucceedsOnceTheMasterAcceptsTheData() throws Exception {
        exec("SAMPLE_A", "ALTER TABLE orders DROP CONSTRAINT ck_orders_status");
        exec("SAMPLE_A", "ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK (status IN ('NEW','PAID','SHIPPED','CANCELLED','RETURNED'))");
        MergePlan p = plan(keepMasterEverywhere());
        assertFalse(p.hasBlockers(), p.issues().toString());
        MergeResult r = new MergeExecutor(registry).execute(p, allAlters(p));
        assertEquals(RunStatus.SUCCEEDED, r.status(), String.join("\n", r.log()));
        assertTrue(r.validations().stream().allMatch(MergeModel.ValidationResult::passed), r.validations().toString());
        assertEquals(3, count("SAMPLE_A", "customer"));       // alice, bob (kept), carol (re-keyed)
        assertEquals(4, count("SAMPLE_A", "region"));         // North (dup), South, West, East (re-keyed)
        assertEquals(3, count("SAMPLE_A", "product"));
        assertEquals(1, count("SAMPLE_A", "audit_note"));     // table created by the approved schema change
        assertTrue(count("SAMPLE_A", AlterScriptGenerator.AUDIT_TABLE) > 0);
        assertEquals(3, count("SAMPLE_B", "customer"));
        assertNotNull(r.log());
    }
}
