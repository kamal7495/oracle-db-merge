package com.dbmerge.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.SqlScriptRunner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The running application against a real Oracle with the sample schemas, end to end over HTTP.
 *
 * <p>1. Every step the UI takes, through the REST API, including every way the merge must refuse to run.
 * <p>2. The UI itself in headless Chromium: add connections in the form, compare with a filter, run the reference
 * report and the data comparison, pick business keys, decide conflicts, approve schema changes, type the confirmation
 * phrase and execute the merge, then check the master in the database.
 *
 * <p>Skipped without Docker. The browser is downloaded by Playwright on first use.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebFlowIT {

    private static final String USER_PW = "Sample_pw_1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path TMP;
    static {
        try {
            TMP = Files.createTempDirectory("dbmerge-webflow");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    private static final Map<String, List<String>> KEYS = Map.of(
            "REGION", List.of("NAME"), "PRODUCT", List.of("SKU"), "CUSTOMER", List.of("EMAIL"));
    private static final List<String> TABLES = List.of("REGION", "PRODUCT", "CUSTOMER", "ORDERS", "ORDER_ITEM");

    @Container
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart").withPassword("Sys_pw_1");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("dbmerge.store-dir", () -> TMP.resolve("store").toString());
        r.add("dbmerge.reports-dir", () -> TMP.resolve("reports").toString());
        r.add("dbmerge.rules-file", () -> TMP.resolve("rules.json").toString());
    }

    @LocalServerPort int port;
    private final HttpClient http = HttpClient.newHttpClient();

    static String url() {
        return "jdbc:oracle:thin:@//" + ORACLE.getHost() + ":" + ORACLE.getOraclePort() + "/" + ORACLE.getDatabaseName();
    }

    @BeforeAll
    static void schemas() throws Exception {
        try (Connection sys = DriverManager.getConnection(url(), "system", ORACLE.getPassword()); Statement s = sys.createStatement()) {
            for (String u : List.of("SAMPLE_A", "SAMPLE_B")) {
                s.execute("CREATE USER " + u + " IDENTIFIED BY \"" + USER_PW + "\"");
                s.execute("GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE TRIGGER, UNLIMITED TABLESPACE TO " + u);
            }
        }
        try (Connection a = DriverManager.getConnection(url(), "SAMPLE_A", USER_PW)) { SqlScriptRunner.run(a, "/sample/schema_a.sql"); }
        try (Connection b = DriverManager.getConnection(url(), "SAMPLE_B", USER_PW)) { SqlScriptRunner.run(b, "/sample/schema_b.sql"); }
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

    // ------------------------------------------------------------------------------ HTTP helpers

    private record Reply(int status, String body) {
        JsonNode json() throws IOException { return JSON.readTree(body); }
    }

    private Reply call(String method, String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (body == null) b.method(method, HttpRequest.BodyPublishers.noBody());
        else b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r.body());
    }

    private JsonNode ok(String method, String path, Object body) throws Exception {
        Reply r = call(method, path, body);
        assertEquals(200, r.status(), method + " " + path + " -> " + r.body());
        return r.json();
    }

    private Map<String, Object> connection(String nick, String user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nickname", nick);
        m.put("host", ORACLE.getHost());
        m.put("port", ORACLE.getOraclePort());
        m.put("serviceName", ORACLE.getDatabaseName());
        m.put("username", user);
        m.put("password", USER_PW);
        return m;
    }

    // ------------------------------------------------------------------------------ 1. REST

    @Test
    @Order(1)
    void everyUiStepOverRestAndEveryWayTheMergeRefuses() throws Exception {
        ok("POST", "/api/connections", connection("MASTER", "SAMPLE_A"));
        ok("POST", "/api/connections", connection("SRC_B", "SAMPLE_B"));
        for (String n : List.of("MASTER", "SRC_B")) assertTrue(ok("POST", "/api/connections/" + n + "/test", null).get("ok").asBoolean());

        // phase 1, plain and filtered; the filtered comparison is what later phases build on
        JsonNode all = ok("POST", "/api/ddl/compare", Map.of("nicknames", List.of("MASTER", "SRC_B")));
        assertTrue(all.toString().contains("AUDIT_NOTE"), "the table only in B is a finding");
        JsonNode filtered = ok("POST", "/api/ddl/compare", Map.of("nicknames", List.of("MASTER", "SRC_B"), "exclude", List.of("AUDIT_*")));
        assertFalse(filtered.get("differences").toString().contains("AUDIT_NOTE"), filtered.toString());
        assertEquals(200, call("GET", filtered.get("htmlReport").asText(), null).status());

        // snapshot of the live master compares equal to the master
        Reply snap = call("GET", "/api/ddl/" + all.get("id").asText() + "/snapshot/MASTER", null);
        assertEquals(200, snap.status());
        JsonNode vsSnap = ok("POST", "/api/ddl/compare", Map.of("nicknames", List.of("MASTER"),
                "snapshots", List.of(Map.of("name", "master-baseline.json", "content", snap.body()))));
        assertEquals(0, vsSnap.get("differenceCount").asInt(), vsSnap.toString());

        // phase 2 on the filtered comparison: AUDIT_NOTE is not compared
        JsonNode data = ok("POST", "/api/data/compare", Map.of("ddlId", filtered.get("id").asText(), "keys", KEYS));
        List<String> compared = new ArrayList<>();
        data.get("tables").forEach(t -> compared.add(t.get("table").asText()));
        assertFalse(compared.contains("AUDIT_NOTE"), compared.toString());
        assertTrue(compared.containsAll(TABLES), compared.toString());
        assertEquals(200, call("GET", data.get("htmlReport").asText(), null).status());

        // phase 3: conflicts without decisions block; every refusal leaves the master as it was
        String ddlId = all.get("id").asText();
        long customers = count("SAMPLE_A", "customer");
        ok("DELETE", "/api/merge/rules", null);
        JsonNode plan = ok("POST", "/api/merge/plan", Map.of("ddlId", ddlId, "master", "MASTER", "sources", List.of("SRC_B"), "keys", KEYS));
        assertTrue(plan.get("hasBlockers").asBoolean());
        assertTrue(plan.get("unresolved").size() > 0);
        assertEquals("MERGE INTO MASTER", plan.get("confirmationPhrase").asText());
        assertEquals(200, call("GET", plan.get("sqlScript").asText(), null).status());
        String planId = plan.get("planId").asText();
        assertEquals("REFUSED", ok("POST", "/api/merge/execute", Map.of("planId", planId, "confirmation", "MERGE INTO MASTER",
                "backupConfirmed", false)).get("status").asText(), "no backup confirmed");
        assertEquals("REFUSED", ok("POST", "/api/merge/execute", Map.of("planId", planId, "confirmation", "merge into master",
                "backupConfirmed", true)).get("status").asText(), "the phrase must match exactly");
        assertEquals("REFUSED", ok("POST", "/api/merge/execute", Map.of("planId", planId, "confirmation", "MERGE INTO MASTER",
                "backupConfirmed", true)).get("status").asText(), "blockers");
        assertEquals(customers, count("SAMPLE_A", "customer"));
        assertEquals(400, call("POST", "/api/merge/plan", Map.of("ddlId", vsSnap.get("id").asText(), "master", "MASTER",
                "sources", List.of("master-baseline"))).status(), "a snapshot cannot be merged");

        for (String n : List.of("MASTER", "SRC_B")) assertEquals(204, call("DELETE", "/api/connections/" + n, null).status());
    }

    // ------------------------------------------------------------------------------ 2. the UI in a browser

    @Test
    @Order(2)
    void aFullMergeThroughTheUi() throws Exception {
        // B has an order status the master's CHECK constraint forbids (MergeIT shows the rollback); allow it here.
        exec("SAMPLE_A", "ALTER TABLE orders DROP CONSTRAINT ck_orders_status");
        exec("SAMPLE_A", "ALTER TABLE orders ADD CONSTRAINT ck_orders_status CHECK (status IN ('NEW','PAID','SHIPPED','CANCELLED','RETURNED'))");
        ok("DELETE", "/api/merge/rules", null);

        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true))) {
            Page page = browser.newPage();
            List<String> consoleErrors = new ArrayList<>();
            page.onConsoleMessage(m -> { if ("error".equals(m.type())) consoleErrors.add(m.text()); });
            page.onPageError(consoleErrors::add);
            page.setDefaultTimeout(120_000);
            page.navigate("http://localhost:" + port + "/");

            // 1. connections through the form
            for (String[] c : new String[][] {{"UI_A", "SAMPLE_A"}, {"UI_B", "SAMPLE_B"}}) {
                page.fill("#addForm input[name=nickname]", c[0]);
                page.fill("#addForm input[name=host]", ORACLE.getHost());
                page.fill("#addForm input[name=port]", String.valueOf(ORACLE.getOraclePort()));
                page.fill("#addForm input[name=serviceName]", ORACLE.getDatabaseName());
                page.fill("#addForm input[name=username]", c[1]);
                page.fill("#addForm input[name=password]", USER_PW);
                page.click("#addForm button[type=submit]");
                page.locator("#connTable td", new Page.LocatorOptions().setHasText(c[0])).first().waitFor();
            }
            assertEquals("", page.inputValue("#addForm input[name=password]"), "the form forgets the password");
            Locator testBtn = page.locator("#connTable tr", new Page.LocatorOptions().setHasText("UI_A")).locator("button", new Locator.LocatorOptions().setHasText("Test"));
            testBtn.click();
            page.locator("#connTable td.good").first().waitFor();

            // 2. phase 1 with a filter
            page.check("#connTable input[value=UI_A]");
            page.check("#connTable input[value=UI_B]");
            page.fill("#fltExclude", "AUDIT_*");
            page.click("#runBtn");
            page.locator("#result >> text=difference(s)").waitFor();
            assertTrue(page.textContent("#result").contains("Filtered: exclude AUDIT_*"));
            assertFalse(page.textContent("#result").contains("AUDIT_NOTE"));
            assertTrue(page.locator("#result a", new Page.LocatorOptions().setHasText("UI_A")).count() > 0, "snapshot links");

            // 3. reference report
            page.selectOption("#refsConn", "UI_A");
            page.click("#refsBtn");
            page.locator("#refsResult >> text=undeclared reference(s)").waitFor();

            // 4. phase 2 with business keys picked in the key table
            page.locator("#dataSection").waitFor();
            for (var e : KEYS.entrySet()) {
                page.locator("#keyTable input[data-table=" + e.getKey() + "]").all().forEach(Locator::uncheck);
                for (String col : e.getValue()) page.check("#keyTable input[data-table=" + e.getKey() + "][value=" + col + "]");
            }
            page.click("#dataBtn");
            page.locator("#dataResult table").waitFor();
            assertTrue(page.textContent("#dataResult").contains("CUSTOMER"));

            // 5. dry run: conflicts need decisions
            page.selectOption("#masterSel", "UI_A");
            page.click("#planBtn");
            page.locator("#planResult >> text=Dry run only").waitFor();
            assertTrue(page.textContent("#planResult").contains("Blockers present"));
            assertFalse(page.isVisible("#execBox"));
            for (Locator b : page.locator("#planResult button", new Page.LocatorOptions().setHasText("Remember for table")).all()) {
                b.click(); // first option: keep master
            }
            page.locator("#planResult >> text=saved").first().waitFor();

            // 6. dry run again, approve schema changes, confirm and execute
            page.click("#planBtn");
            page.locator("#execBox").waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
            page.locator(".alterCb").all().forEach(Locator::check);
            assertEquals("MERGE INTO UI_A", page.textContent("#phrase").trim());
            page.check("#backupOk");
            page.fill("#confirmText", "MERGE INTO UI_A");
            page.click("#execBtn");
            page.locator("#execMsg.good").waitFor();
            assertTrue(page.textContent("#execMsg").startsWith("SUCCEEDED"), page.textContent("#execMsg"));
            assertFalse(page.textContent("#execResult").contains("FAIL "), page.textContent("#execResult"));
            assertTrue(consoleErrors.isEmpty(), "browser errors: " + consoleErrors);
        }

        // the master holds the merged data; the source is unchanged (same expectations as MergeIT)
        assertEquals(3, count("SAMPLE_A", "customer"));
        assertEquals(4, count("SAMPLE_A", "region"));
        assertEquals(3, count("SAMPLE_A", "product"));
        assertEquals(3, count("SAMPLE_B", "customer"));
        assertEquals(0, count("SAMPLE_A", "user_tables WHERE table_name = 'AUDIT_NOTE'"),
                "AUDIT_NOTE was filtered out in phase 1, so the merge built on that comparison leaves it out too");
    }
}
