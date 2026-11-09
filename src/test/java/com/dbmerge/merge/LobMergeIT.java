package com.dbmerge.merge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.conn.ConnectionRegistry;
import com.dbmerge.conn.ConnectionRequest;
import com.dbmerge.data.DataCompareService;
import com.dbmerge.data.DataModel.DataReport;
import com.dbmerge.data.DataModel.Status;
import com.dbmerge.data.DataModel.TableDataResult;
import com.dbmerge.ddl.SchemaExtractor;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.merge.MergeModel.ConflictAction;
import com.dbmerge.merge.MergeModel.MergePlan;
import com.dbmerge.merge.MergeModel.MergeResult;
import com.dbmerge.merge.MergeModel.Options;
import com.dbmerge.merge.MergeModel.PlannedRow;
import com.dbmerge.merge.MergeModel.RowAction;
import com.dbmerge.merge.MergeModel.RunStatus;
import com.dbmerge.merge.MergeModel.TableStep;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
 * BLOB/CLOB columns through Phase 2 and Phase 3 against a real Oracle. Skipped without Docker.
 *
 * <p>DOC (business key CODE) and its child DOC_NOTE exist in both schemas. A = master, B = source:
 * <pre>
 *   CODE        A (master)                       B (source)                               expected
 *   SAME        id 1, small CLOB + BLOB          id 1, identical                          SKIP_DUPLICATE
 *   CONF        id 2, "master version", 16 B     id 2, "source version", 1 MB BLOB        conflict; TAKE_SOURCE -> UPDATE
 *   NULLS       id 3, NULL, NULL                 id 3, NULL, NULL                         SKIP_DUPLICATE
 *   MASTERONLY  id 10                            -                                        untouched
 *   BIG         -                                id 10 (collides), 100k-char CLOB, 2 MB   INSERT_REMAPPED (MAX+1)
 *   NEWNULL     -                                id 12, NULL, NULL                        INSERT
 *   DOC_NOTE    (empty)                          note 1 -> doc 10 (BIG), note 2 -> doc 2  FKs rewritten: BIG's new id, 2
 * </pre>
 * LOB values are far larger than the 32 KB that plain JDBC binds carry, so the driver's LOB binding path is exercised.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LobMergeIT {

    private static final String USER_PW = "Sample_pw_1";

    @Container
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart").withPassword("Sys_pw_1");

    static final Map<String, List<String>> KEYS = Map.of("DOC", List.of("CODE"));

    static final byte[] SMALL = bytes(1, 64);
    static final byte[] MASTER_CONF = bytes(2, 16);
    static final byte[] SOURCE_CONF = bytes(3, 1024 * 1024);          // 1 MB
    static final byte[] BIG_BLOB = bytes(4, 2 * 1024 * 1024);         // 2 MB
    static final String BIG_CLOB = unicodeText(100_000);              // multi-byte and surrogate-pair characters
    static final String NOTE_CLOB = unicodeText(50_000);

    static ConnectionRegistry registry;

    static byte[] bytes(long seed, int n) {
        byte[] b = new byte[n];
        new Random(seed).nextBytes(b);
        return b;
    }

    static String unicodeText(int chars) {
        String unit = "Lorem ipsum ä ö ü € ₹ 漢字 𝄞 ";
        StringBuilder sb = new StringBuilder(chars + unit.length());
        while (sb.length() < chars) sb.append(unit);
        return sb.substring(0, sb.length());   // whole units, so no surrogate pair is split
    }

    static String url() {
        return "jdbc:oracle:thin:@//" + ORACLE.getHost() + ":" + ORACLE.getOraclePort() + "/" + ORACLE.getDatabaseName();
    }

    static Connection as(String user) throws SQLException {
        return DriverManager.getConnection(url(), user, USER_PW);
    }

    @BeforeAll
    static void setUp() throws Exception {
        try (Connection sys = DriverManager.getConnection(url(), "system", ORACLE.getPassword()); Statement s = sys.createStatement()) {
            for (String u : List.of("SAMPLE_A", "SAMPLE_B")) {
                s.execute("CREATE USER " + u + " IDENTIFIED BY \"" + USER_PW + "\"");
                s.execute("GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE TRIGGER, UNLIMITED TABLESPACE TO " + u);
            }
        }
        for (String u : List.of("SAMPLE_A", "SAMPLE_B")) {
            try (Connection c = as(u); Statement s = c.createStatement()) {
                s.execute("CREATE TABLE doc (doc_id NUMBER(10) PRIMARY KEY, code VARCHAR2(20) NOT NULL, body CLOB, content BLOB,"
                        + " CONSTRAINT uq_doc_code UNIQUE (code))");
                s.execute("CREATE TABLE doc_note (note_id NUMBER(10) PRIMARY KEY, doc_id NUMBER(10) NOT NULL, note CLOB,"
                        + " CONSTRAINT fk_note_doc FOREIGN KEY (doc_id) REFERENCES doc (doc_id))");
            }
        }
        try (Connection a = as("SAMPLE_A")) {
            insertDoc(a, 1, "SAME", "same text", SMALL);
            insertDoc(a, 2, "CONF", "master version", MASTER_CONF);
            insertDoc(a, 3, "NULLS", null, null);
            insertDoc(a, 10, "MASTERONLY", "only in the master", SMALL);
        }
        try (Connection b = as("SAMPLE_B")) {
            insertDoc(b, 1, "SAME", "same text", SMALL);
            insertDoc(b, 2, "CONF", "source version", SOURCE_CONF);
            insertDoc(b, 3, "NULLS", null, null);
            insertDoc(b, 10, "BIG", BIG_CLOB, BIG_BLOB);
            insertDoc(b, 12, "NEWNULL", null, null);
            insertNote(b, 1, 10, NOTE_CLOB);
            insertNote(b, 2, 2, "short note on CONF");
        }
        registry = new ConnectionRegistry();
        for (String[] db : new String[][] {{"MASTER", "SAMPLE_A"}, {"SRC_B", "SAMPLE_B"}}) {
            registry.add(new ConnectionRequest(db[0], ORACLE.getHost(), ORACLE.getOraclePort(), ORACLE.getDatabaseName(), db[1], USER_PW.toCharArray(), null));
        }
    }

    /** Test data goes in through streams, independent of how the application binds LOBs. */
    static void insertDoc(Connection c, int id, String code, String body, byte[] content) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO doc (doc_id, code, body, content) VALUES (?, ?, ?, ?)")) {
            ps.setInt(1, id);
            ps.setString(2, code);
            if (body == null) ps.setNull(3, Types.CLOB); else ps.setCharacterStream(3, new StringReader(body), body.length());
            if (content == null) ps.setNull(4, Types.BLOB); else ps.setBinaryStream(4, new ByteArrayInputStream(content), content.length);
            ps.executeUpdate();
        }
    }

    static void insertNote(Connection c, int id, int docId, String note) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO doc_note (note_id, doc_id, note) VALUES (?, ?, ?)")) {
            ps.setInt(1, id);
            ps.setInt(2, docId);
            ps.setCharacterStream(3, new StringReader(note), note.length());
            ps.executeUpdate();
        }
    }

    record Doc(BigDecimal id, String body, byte[] content, boolean bodyIsNull, boolean contentIsNull, long contentLength) { }

    static Doc doc(String user, String code) throws SQLException {
        try (Connection c = as(user);
             PreparedStatement ps = c.prepareStatement("SELECT doc_id, body, content, CASE WHEN body IS NULL THEN 1 ELSE 0 END,"
                     + " CASE WHEN content IS NULL THEN 1 ELSE 0 END, NVL(DBMS_LOB.GETLENGTH(content), -1) FROM doc WHERE code = ?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Doc(rs.getBigDecimal(1), rs.getString(2), rs.getBytes(3), rs.getInt(4) == 1, rs.getInt(5) == 1, rs.getLong(6));
            }
        }
    }

    static long count(String user, String table) throws SQLException {
        try (Connection c = as(user); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static Map<String, SchemaModel> models() throws Exception {
        SchemaExtractor ex = new SchemaExtractor(registry);
        Map<String, SchemaModel> models = new LinkedHashMap<>();
        models.put("MASTER", ex.extract("MASTER", false));
        models.put("SRC_B", ex.extract("SRC_B", false));
        return models;
    }

    static MergePlan plan(Rules rules) throws Exception {
        return new MergePlanner(new JdbcMergeData(registry, 100_000)).plan("MASTER", List.of("SRC_B"), models(), KEYS, rules, Options.defaults());
    }

    static Rules takeSource() {
        Rules r = new Rules();
        r.getTables().put("DOC", new Rules.TableRule(ConflictAction.TAKE_SOURCE, null));
        return r;
    }

    static Set<String> allAlters(MergePlan p) {
        Set<String> ids = new LinkedHashSet<>();
        p.alters().forEach(a -> ids.add(a.id()));
        return ids;
    }

    static TableStep step(MergePlan p, String table) {
        return p.steps().stream().filter(s -> s.table().equals(table)).findFirst().orElseThrow(() -> new AssertionError("no step for " + table));
    }

    static PlannedRow row(TableStep s, String code) {
        return s.rows().stream().filter(r -> ("CODE=" + code).equals(r.businessKey())).findFirst()
                .orElseThrow(() -> new AssertionError("no planned row for " + code + " in " + s.rows()));
    }

    @Test
    @Order(1)
    void dataComparisonComparesLobContent() throws Exception {
        Map<String, SchemaModel> m = models();
        DataReport report = new DataCompareService(registry, 100_000)
                .run("lob-it", List.copyOf(m.values()), KEYS, Set.of("DOC"), 10);
        TableDataResult doc = report.tables().stream().filter(t -> t.table().equals("DOC")).findFirst().orElseThrow();
        assertEquals(Status.COMPARED, doc.status(), doc.message());
        assertEquals(2, doc.identicalCount(), "SAME and NULLS are identical, including NULL = NULL");
        assertEquals(1, doc.conflictCount(), "CONF differs only in its CLOB and BLOB");
        assertEquals(Map.of("MASTER", 1L, "SRC_B", 2L), doc.onlyInCounts());
    }

    @Test
    @Order(2)
    void planDetectsLobDifferencesAndRekeysTheCollidingRow() throws Exception {
        MergePlan unresolved = plan(new Rules());
        PlannedRow conf = row(step(unresolved, "DOC"), "CONF");
        assertEquals(RowAction.UNRESOLVED_CONFLICT, conf.action());
        assertEquals(Set.of("BODY", "CONTENT"), Set.copyOf(conf.changedColumns()));
        assertTrue(unresolved.hasBlockers());

        MergePlan p = plan(takeSource());
        assertFalse(p.hasBlockers(), p.issues().toString());
        TableStep doc = step(p, "DOC");
        assertEquals(RowAction.SKIP_DUPLICATE, row(doc, "SAME").action());
        assertEquals(RowAction.SKIP_DUPLICATE, row(doc, "NULLS").action());
        assertEquals(RowAction.UPDATE, row(doc, "CONF").action());
        assertEquals(RowAction.INSERT_REMAPPED, row(doc, "BIG").action(), "doc_id 10 is taken by MASTERONLY");
        assertEquals(RowAction.INSERT, row(doc, "NEWNULL").action());
        assertEquals(2, step(p, "DOC_NOTE").count(RowAction.INSERT));

        // The review script must render without blowing up; executing large literals is a separate, known limitation.
        assertNotNull(ScriptRenderer.render(p, allAlters(p)));
    }

    @Test
    @Order(3)
    void mergeWritesLobsByteForByteAndRewritesTheChildForeignKey() throws Exception {
        MergePlan p = plan(takeSource());
        MergeResult r = new MergeExecutor(registry).execute(p, allAlters(p));
        assertEquals(RunStatus.SUCCEEDED, r.status(), r.message() + "\n" + r.validations() + "\n" + String.join("\n", r.log()));
        assertTrue(r.validations().stream().allMatch(MergeModel.ValidationResult::passed), r.validations().toString());

        assertEquals(6, count("SAMPLE_A", "doc"));
        Doc same = doc("SAMPLE_A", "SAME");
        assertArrayEquals(SMALL, same.content());
        assertEquals("same text", same.body());

        Doc conf = doc("SAMPLE_A", "CONF");
        assertEquals(new BigDecimal(2), conf.id());
        assertEquals("source version", conf.body(), "CLOB updated from the source");
        assertArrayEquals(SOURCE_CONF, conf.content(), "1 MB BLOB updated from the source");

        Doc big = doc("SAMPLE_A", "BIG");
        assertNotEquals(new BigDecimal(10), big.id(), "re-keyed away from MASTERONLY's id");
        assertEquals(BIG_CLOB, big.body(), "100k-character Unicode CLOB inserted intact");
        assertArrayEquals(BIG_BLOB, big.content(), "2 MB BLOB inserted intact");

        Doc newNull = doc("SAMPLE_A", "NEWNULL");
        assertTrue(newNull.bodyIsNull());
        assertTrue(newNull.contentIsNull());
        Doc nulls = doc("SAMPLE_A", "NULLS");
        assertTrue(nulls.bodyIsNull() && nulls.contentIsNull());

        Doc masterOnly = doc("SAMPLE_A", "MASTERONLY");
        assertEquals(new BigDecimal(10), masterOnly.id());
        assertEquals("only in the master", masterOnly.body());

        try (Connection c = as("SAMPLE_A"); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT n.note, d.code FROM doc_note n JOIN doc d ON d.doc_id = n.doc_id ORDER BY n.note_id")) {
            assertTrue(rs.next());
            assertEquals(NOTE_CLOB, rs.getString(1));
            assertEquals("BIG", rs.getString(2), "child FK follows the re-keyed parent");
            assertTrue(rs.next());
            assertEquals("CONF", rs.getString(2));
            assertFalse(rs.next());
        }

        assertEquals(5, count("SAMPLE_B", "doc"), "the source must never change");
        assertArrayEquals(BIG_BLOB, doc("SAMPLE_B", "BIG").content());
    }

    @Test
    @Order(4)
    void emptyLobsAreMergedAsEmptyNotNull() throws Exception {
        // EMPTY_BLOB()/EMPTY_CLOB() are zero-length LOBs, which Oracle distinguishes from NULL.
        try (Connection b = as("SAMPLE_B"); Statement s = b.createStatement()) {
            s.execute("INSERT INTO doc (doc_id, code, body, content) VALUES (20, 'EMPTYLOB', EMPTY_CLOB(), EMPTY_BLOB())");
        }
        Doc src = doc("SAMPLE_B", "EMPTYLOB");
        assertFalse(src.contentIsNull());
        assertEquals(0, src.contentLength());

        MergePlan p = plan(takeSource());
        assertFalse(p.hasBlockers(), p.issues().toString());
        assertEquals(RowAction.INSERT, row(step(p, "DOC"), "EMPTYLOB").action());
        assertEquals(1, step(p, "DOC").rows().stream().filter(x -> x.action() == RowAction.INSERT).count(),
                "everything else was merged by the previous test and is now a duplicate");
        String script = ScriptRenderer.render(p, allAlters(p));
        assertTrue(script.contains("EMPTY_CLOB()") && script.contains("EMPTY_BLOB()"),
                "the review script must not write '' / HEXTORAW(''), which Oracle stores as NULL");

        MergeResult r = new MergeExecutor(registry).execute(p, allAlters(p));
        assertEquals(RunStatus.SUCCEEDED, r.status(), r.message() + "\n" + r.validations() + "\n" + String.join("\n", r.log()));
        Doc merged = doc("SAMPLE_A", "EMPTYLOB");
        assertNotNull(merged);
        assertFalse(merged.contentIsNull(), "an empty BLOB must not turn into NULL");
        assertEquals(0, merged.contentLength());
        assertFalse(merged.bodyIsNull(), "an empty CLOB must not turn into NULL");
    }
}
