package com.dbmerge.merge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

/**
 * The large-table path end to end on Oracle: the sample schemas are loaded twice; one master is merged with the
 * in-memory planner, the other with every table streamed in key order (plan in files, batched inserts). Both masters
 * must end up with exactly the same rows.
 */
@Testcontainers(disabledWithoutDocker = true)
class LargeMergeIT {

    private static final String PW = "Sample_pw_1";
    private static final List<String> TABLES = List.of("REGION", "PRODUCT", "CUSTOMER", "ORDERS", "ORDER_ITEM", "AUDIT_NOTE");
    private static final Map<String, List<String>> KEYS = Map.of(
            "REGION", List.of("NAME"), "PRODUCT", List.of("SKU"), "CUSTOMER", List.of("EMAIL"));

    @Container
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart").withPassword("Sys_pw_1");

    static ConnectionRegistry registry;

    static String url() {
        return "jdbc:oracle:thin:@//" + ORACLE.getHost() + ":" + ORACLE.getOraclePort() + "/" + ORACLE.getDatabaseName();
    }

    @BeforeAll
    static void setUp() throws Exception {
        try (Connection sys = DriverManager.getConnection(url(), "system", ORACLE.getPassword()); Statement s = sys.createStatement()) {
            for (String u : List.of("MEM_A", "MEM_B", "STR_A", "STR_B")) {
                s.execute("CREATE USER " + u + " IDENTIFIED BY \"" + PW + "\"");
                s.execute("GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE TRIGGER, UNLIMITED TABLESPACE TO " + u);
            }
        }
        registry = new ConnectionRegistry();
        for (String u : List.of("MEM_A", "MEM_B", "STR_A", "STR_B")) {
            try (Connection c = DriverManager.getConnection(url(), u, PW)) {
                SqlScriptRunner.run(c, u.endsWith("_A") ? "/sample/schema_a.sql" : "/sample/schema_b.sql");
            }
            // The sample scripts fill these with SYSDATE, so copies loaded a second apart would differ before any merge.
            exec(u, "UPDATE customer SET created_at = DATE '2026-01-01'");
            exec(u, "UPDATE orders SET order_date = DATE '2026-01-02'");
            if (u.endsWith("_A")) {
                // B has an order status the master's CHECK constraint forbids (MergeIT shows the rollback); allow it.
                exec(u, "ALTER TABLE orders DROP CONSTRAINT ck_orders_status");
                exec(u, "ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK (status IN ('NEW','PAID','SHIPPED','CANCELLED','RETURNED'))");
            }
            registry.add(new ConnectionRequest(u, ORACLE.getHost(), ORACLE.getOraclePort(), ORACLE.getDatabaseName(), u, PW.toCharArray(), null));
        }
    }

    static void exec(String user, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url(), user, PW); Statement s = c.createStatement()) { s.execute(sql); }
    }

    /** Every row of a table, as sorted text lines (columns in table order). */
    static List<String> contents(String user, String table) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url(), user, PW); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData md = rs.getMetaData();
            while (rs.next()) {
                StringBuilder b = new StringBuilder();
                for (int i = 1; i <= md.getColumnCount(); i++) b.append(md.getColumnName(i)).append('=').append(rs.getString(i)).append(' ');
                out.add(b.toString());
            }
        }
        out.sort(null);
        return out;
    }

    static Rules keepMaster() {
        Rules r = new Rules();
        for (String t : TABLES) r.getTables().put(t, new Rules.TableRule(ConflictAction.KEEP_MASTER, null));
        return r;
    }

    static MergeResult merge(String master, String source, MergePlanner planner) throws Exception {
        SchemaExtractor ex = new SchemaExtractor(registry);
        Map<String, SchemaModel> models = new LinkedHashMap<>();
        models.put(master, ex.extract(master, false));
        models.put(source, ex.extract(source, false));
        MergePlan p = planner.plan(master, List.of(source), models, KEYS, keepMaster(), Options.defaults());
        assertFalse(p.hasBlockers(), p.issues().toString());
        Set<String> alters = new LinkedHashSet<>();
        p.alters().forEach(a -> alters.add(a.id()));
        MergeResult r = new MergeExecutor(registry).execute(p, alters);
        assertEquals(RunStatus.SUCCEEDED, r.status(), String.join("\n", r.log()));
        assertTrue(r.validations().stream().allMatch(MergeModel.ValidationResult::passed), r.validations().toString());
        return r;
    }

    @Test
    void streamingAndInMemoryPlanningLeaveTheMasterIdentical(@TempDir Path spool) throws Exception {
        merge("MEM_A", "MEM_B", new MergePlanner(new JdbcMergeData(registry, 100_000)));

        MergePlanner streaming = new MergePlanner(new JdbcMergeData(registry, 100_000), 0, spool);
        MergeResult r = merge("STR_A", "STR_B", streaming);
        assertTrue(r.log().stream().anyMatch(l -> l.contains("CUSTOMER:")), String.join("\n", r.log()));

        for (String t : TABLES) assertEquals(contents("MEM_A", t), contents("STR_A", t), t + " differs between the two paths");
        assertEquals(3, contents("STR_A", "customer").size());
        assertEquals(4, contents("STR_A", "region").size());
        assertEquals(contents("MEM_B", "customer"), contents("STR_B", "customer"), "the sources are never changed");
        try (var files = Files.list(spool)) {
            assertTrue(files.findAny().isPresent(), "the streaming plan was kept in files");
        }
    }
}
