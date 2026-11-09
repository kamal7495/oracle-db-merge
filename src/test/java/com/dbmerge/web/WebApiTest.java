package com.dbmerge.web;

import static com.dbmerge.ddl.compare.Fixtures.fk;
import static com.dbmerge.ddl.compare.Fixtures.number;
import static com.dbmerge.ddl.compare.Fixtures.pk;
import static com.dbmerge.ddl.compare.Fixtures.table;
import static com.dbmerge.ddl.compare.Fixtures.varchar;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dbmerge.ddl.SchemaExtractor;
import com.dbmerge.ddl.compare.Fixtures;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.TableDef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The REST API as the UI uses it, without a database. Schema extraction is replaced by fixed models (DB_A, DB_B);
 * calls that really need Oracle reach an address nothing listens on, which tests the error path. Saved connections,
 * reports and merge rules go to a temporary folder, never to ~/.dbmerge.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE) // request dumps on failure would print the test password into build logs
@ExtendWith(OutputCaptureExtension.class)
class WebApiTest {

    private static final Path TMP;
    static {
        try {
            TMP = Files.createTempDirectory("dbmerge-webtest");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    private static final String PW = "Very-S3cret-pw";
    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("dbmerge.store-dir", () -> TMP.resolve("store").toString());
        r.add("dbmerge.reports-dir", () -> TMP.resolve("reports").toString());
        r.add("dbmerge.rules-file", () -> TMP.resolve("rules.json").toString());
    }

    @AfterAll
    static void cleanUp() throws IOException {
        try (Stream<Path> files = Files.walk(TMP)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    @Autowired MockMvc mvc;

    /**
     * Fixed schemas instead of reading Oracle: DB_B has an extra column. A plain subclass rather than a Mockito mock,
     * so the test also runs on JDKs newer than Mockito's bytecode library supports.
     */
    @TestConfiguration
    static class FakeSchemas {
        @Bean
        @Primary
        SchemaExtractor fakeExtractor() {
            return new SchemaExtractor(null) {
                @Override
                public SchemaModel extract(String nickname, boolean includeDdl) {
                    return schema(nickname, "DB_B".equals(nickname));
                }
            };
        }
    }

    static SchemaModel schema(String nick, boolean extraColumn) {
        TableDef book = table("BOOK").col(number("BOOK_ID", 1, 10, 0, false)).cons(pk("PK_BOOK", false, "BOOK_ID")).build();
        var trade = table("TRADE").col(number("TRADE_ID", 1, 12, 0, false), number("BOOK_ID", 2, 10, 0, false))
                .cons(pk("PK_TRADE", false, "TRADE_ID"), fk("FK_TRADE_BOOK", false, "BOOK_ID", "BOOK", "BOOK_ID"));
        if (extraColumn) trade = trade.col(varchar("NOTES", 3, 100, true));
        TableDef kw = table("TRADE_KEYWORD").col(number("TRADE_ID", 1, 12, 0, false), varchar("NAME", 2, 30, false))
                .cons(pk("PK_KW", false, "TRADE_ID", "NAME")).build();
        TableDef bkp = table("BKP_TRADE").col(number("TRADE_ID", 1, 12, 0, false), varchar(extraColumn ? "X" : "Y", 2, 5, true)).build();
        return Fixtures.model(nick, book, trade.build(), kw, bkp);
    }

    @BeforeEach
    void setUp() throws Exception {
        for (String n : List.of("DB_A", "DB_B", "OTHER")) mvc.perform(delete("/api/connections/" + n));
        for (String n : List.of("DB_A", "DB_B")) addConnection(n, false, false).andExpect(status().isOk());
    }

    // --------------------------------------------------------------------------------- helpers

    private ResultActions addConnection(String nick, boolean save, boolean savePassword) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nickname", nick);
        body.put("host", "127.0.0.1");
        body.put("port", 1); // nothing listens here: connecting fails fast
        body.put("serviceName", "FREEPDB1");
        body.put("username", "scott");
        body.put("password", PW);
        body.put("save", save);
        body.put("savePassword", savePassword);
        return mvc.perform(post("/api/connections").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private JsonNode compare(Map<String, Object> body) throws Exception {
        String r = postJson("/api/ddl/compare", body).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(r);
    }

    // --------------------------------------------------------------------------------- connections

    @Test
    void connectionsAreListedWithoutTheirPassword() throws Exception {
        String body = mvc.perform(get("/api/connections")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].nickname", hasItem("DB_A")))
                .andExpect(jsonPath("$[0].saved").value(false))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains(PW));
        addConnection("DB_A", false, false).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("already exists")));
        mvc.perform(post("/api/connections").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"1bad\",\"serviceName\":\"X\",\"username\":\"u\",\"password\":\"p\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("Nickname")));
    }

    @Test
    void savedConnectionsGoToTheStoreEncrypted() throws Exception {
        addConnection("OTHER", true, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.saved").value(true)).andExpect(jsonPath("$.passwordSaved").value(true));
        String file = Files.readString(TMP.resolve("store/connections.json"));
        assertTrue(file.contains("OTHER"));
        assertFalse(file.contains(PW), "the password is stored encrypted only");
        mvc.perform(delete("/api/connections/OTHER")).andExpect(status().isNoContent());
        assertFalse(Files.readString(TMP.resolve("store/connections.json")).contains("OTHER"), "removing deletes the saved copy");
        mvc.perform(delete("/api/connections/OTHER")).andExpect(status().isNotFound());
    }

    @Test
    void unlockAndTestNeverEchoThePassword(CapturedOutput output) throws Exception {
        String r = postJson("/api/connections/DB_A/unlock", Map.of("password", PW)).andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false)).andReturn().getResponse().getContentAsString();
        assertFalse(r.contains(PW));
        postJson("/api/connections/NOPE/unlock", Map.of("password", PW)).andExpect(status().isBadRequest());
        postJson("/api/connections/DB_A/unlock", Map.of("password", PW, "savePassword", true)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Only saved connections")));
        String test = mvc.perform(post("/api/connections/DB_A/test")).andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false)).andReturn().getResponse().getContentAsString();
        assertFalse(test.contains(PW));
        assertFalse(output.getAll().contains(PW), "the password must never reach the log");
    }

    // --------------------------------------------------------------------------------- phase 1

    @Test
    void ddlCompareWritesReportsThatCanBeDownloaded() throws Exception {
        JsonNode r = compare(Map.of("nicknames", List.of("DB_A", "DB_B")));
        assertTrue(r.get("differenceCount").asInt() >= 2, r.toString());
        assertEquals("", r.get("filter").asText());
        mvc.perform(get(r.get("htmlReport").asText())).andExpect(status().isOk())
                .andExpect(content().string(containsString("Oracle DDL comparison")));
        mvc.perform(get(r.get("excelReport").asText())).andExpect(status().isOk());
        for (String bad : List.of("..%2F..%2Fetc%2Fpasswd", "ddl-zzzz.html", "merge-rules.json", "ddl-12345678.exe")) {
            mvc.perform(get("/api/reports/" + bad)).andExpect(status().isNotFound());
        }
        postJson("/api/ddl/compare", Map.of("nicknames", List.of("DB_A"))).andExpect(status().isBadRequest());
    }

    @Test
    void filtersRemoveObjectsAndFindingsAndAreReported() throws Exception {
        JsonNode all = compare(Map.of("nicknames", List.of("DB_A", "DB_B")));
        assertTrue(all.toString().contains("BKP_TRADE"));
        JsonNode f = compare(Map.of("nicknames", List.of("DB_A", "DB_B"), "exclude", List.of("BKP_*"), "ignore", List.of("COLUMN")));
        assertEquals("exclude BKP_*; ignore DEFAULT_MISMATCH, LENGTH_MISMATCH, MISSING_COLUMN, NULLABLE_MISMATCH, TYPE_MISMATCH",
                f.get("filter").asText());
        assertEquals(0, f.get("differenceCount").asInt(), f.toString());
        mvc.perform(get(f.get("htmlReport").asText())).andExpect(content().string(containsString("Filtered:")));
        // keeping TRADE but not BOOK warns: the merge would copy TRADE.BOOK_ID unchanged
        JsonNode parent = compare(Map.of("nicknames", List.of("DB_A", "DB_B"), "include", List.of("TRADE")));
        assertTrue(parent.get("databases").get(0).get("warnings").toString().contains("excludes its parent BOOK"), parent.toString());
        postJson("/api/ddl/compare", Map.of("nicknames", List.of("DB_A", "DB_B"), "exclude", List.of("X'; DROP")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("Invalid name pattern")));
        postJson("/api/ddl/compare", Map.of("nicknames", List.of("DB_A", "DB_B"), "ignore", List.of("EVERYTHING")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void snapshotsDownloadUploadAndAreRefusedForDataAndMerge() throws Exception {
        JsonNode r = compare(Map.of("nicknames", List.of("DB_A", "DB_B")));
        String id = r.get("id").asText();
        String snap = mvc.perform(get("/api/ddl/" + id + "/snapshot/DB_A")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment; filename=\"snapshot-DB_A-")))
                .andReturn().getResponse().getContentAsString();
        assertTrue(snap.contains("\"format\" : \"dbmerge-schema-snapshot\""));
        mvc.perform(get("/api/ddl/" + id + "/snapshot/NOPE")).andExpect(status().isBadRequest());

        JsonNode withSnap = compare(Map.of("nicknames", List.of("DB_A"), "snapshots", List.of(Map.of("name", "baseline.json", "content", snap))));
        assertEquals("baseline", withSnap.get("snapshots").get(0).asText());
        assertEquals(0, withSnap.get("differenceCount").asInt(), "DB_A against its own snapshot: " + withSnap);
        String snapId = withSnap.get("id").asText();
        postJson("/api/data/compare", Map.of("ddlId", snapId)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("need live databases")));
        postJson("/api/merge/plan", Map.of("ddlId", snapId, "master", "DB_A", "sources", List.of("baseline")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("need live databases")));

        postJson("/api/ddl/compare", Map.of("nicknames", List.of("DB_A"), "snapshots", List.of(Map.of("name", "DB_A.json", "content", snap))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("rename the file")));
        postJson("/api/ddl/compare", Map.of("nicknames", List.of("DB_A"), "snapshots", List.of(Map.of("name", "x.json", "content", "{\"a\":1}"))))
                .andExpect(status().isBadRequest());
    }

    // --------------------------------------------------------------------------------- phase 2 and 3

    @Test
    void dataAndMergeNeedAComparisonAndReportDatabaseErrorsBriefly() throws Exception {
        postJson("/api/data/compare", Map.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("ddlId")));
        postJson("/api/data/compare", Map.of("ddlId", "deadbeef")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", containsString("Unknown DDL comparison")));
        String id = compare(Map.of("nicknames", List.of("DB_A", "DB_B"))).get("id").asText();
        mvc.perform(get("/api/ddl/" + id + "/tables")).andExpect(status().isOk()).andExpect(content().string(containsString("TRADE")));

        // nothing listens on port 1: the database error is reported in one line, without a stack trace
        String err = postJson("/api/merge/plan", Map.of("ddlId", id, "master", "DB_A", "sources", List.of("DB_B")))
                .andExpect(status().isBadGateway()).andReturn().getResponse().getContentAsString();
        assertFalse(err.contains("at com.") || err.contains("\\n"), err);
        assertFalse(err.contains(PW));
        postJson("/api/merge/plan", Map.of("ddlId", id, "master", "DB_A", "sources", List.of("DB_B"), "orphanPolicy", "maybe"))
                .andExpect(status().isBadRequest());
        postJson("/api/merge/execute", Map.of("planId", "nope", "confirmation", "MERGE INTO DB_A", "backupConfirmed", true))
                .andExpect(status().isBadRequest());
    }

    @Test
    void conflictRulesAreSavedAndCleared() throws Exception {
        mvc.perform(delete("/api/merge/rules")).andExpect(status().isOk());
        postJson("/api/merge/rules/table", Map.of("table", "TRADE", "action", "KEEP_MASTER")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tables.TRADE.action").value("KEEP_MASTER"));
        postJson("/api/merge/rules/row", Map.of("table", "TRADE", "businessKey", "TRADE_ID=7", "action", "TAKE_SOURCE"))
                .andExpect(status().isOk());
        assertTrue(Files.readString(TMP.resolve("rules.json")).contains("TRADE_ID=7"));
        mvc.perform(get("/api/merge/rules")).andExpect(jsonPath("$.rows.TRADE['TRADE_ID=7'].action").value("TAKE_SOURCE"));
        postJson("/api/merge/rules/table", Map.of("table", "TRADE")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/merge/rules")).andExpect(status().isOk()).andExpect(jsonPath("$.tables.TRADE").doesNotExist());
    }

    // --------------------------------------------------------------------------------- references and UI

    @Test
    void referenceReportFromTheSchema() throws Exception {
        JsonNode r = JSON.readTree(postJson("/api/refs/detect", Map.of("nickname", "DB_A", "checkData", false))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertTrue(r.toString().contains("TRADE_KEYWORD.TRADE_ID"), "undeclared reference to TRADE: " + r);
        assertFalse(r.toString().contains("\"TRADE.BOOK_ID\""), "declared foreign keys are not reported");
        mvc.perform(get(r.get("htmlReport").asText())).andExpect(status().isOk());
    }

    @Test
    void theUiPageServesEveryControlTheScriptUses() throws Exception {
        String page = mvc.perform(get("/")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        if (page.isEmpty()) page = mvc.perform(get("/index.html")).andReturn().getResponse().getContentAsString();
        for (String id : List.of("addForm", "optSave", "optSavePw", "connTable", "runBtn", "fltExclude", "fltInclude", "fltIgnore",
                "snapFiles", "refsConn", "refsBtn", "optAck")) {
            assertTrue(page.contains("id=\"" + id + "\""), "missing #" + id);
        }
        // every $('#x') the script looks up exists in the page
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\$\\('#([A-Za-z0-9_-]+)").matcher(page);
        while (m.find()) assertTrue(page.contains("id=\"" + m.group(1) + "\""), "script uses #" + m.group(1) + " which is not in the page");
        mvc.perform(get("/api/nothing-here")).andExpect(status().isNotFound()).andExpect(content().string(not(containsString("Exception"))));
        mvc.perform(delete("/api/ddl/compare")).andExpect(status().isMethodNotAllowed());
        mvc.perform(post("/api/ddl/compare").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error", containsString("not valid JSON")));
        postJson("/api/merge/rules/table", Map.of("table", "TRADE", "action", "DELETE_EVERYTHING")).andExpect(status().isBadRequest());
    }
}
