package com.dbmerge.ddl.model;

import static com.dbmerge.ddl.compare.Fixtures.fk;
import static com.dbmerge.ddl.compare.Fixtures.index;
import static com.dbmerge.ddl.compare.Fixtures.number;
import static com.dbmerge.ddl.compare.Fixtures.pk;
import static com.dbmerge.ddl.compare.Fixtures.sequence;
import static com.dbmerge.ddl.compare.Fixtures.table;
import static com.dbmerge.ddl.compare.Fixtures.trigger;
import static com.dbmerge.ddl.compare.Fixtures.varchar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.ddl.compare.ComparisonResult;
import com.dbmerge.ddl.compare.SchemaComparator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SchemaSnapshotTest {

    @TempDir Path dir;

    private static SchemaModel model() {
        Map<String, TableDef> tables = new LinkedHashMap<>();
        tables.put("BOOK", table("BOOK").col(number("BOOK_ID", 1, 10, 0, false)).cons(pk("PK_BOOK", false, "BOOK_ID")).build());
        tables.put("TRADE", table("TRADE").col(number("TRADE_ID", 1, 12, 0, false), number("BOOK_ID", 2, 10, 0, false),
                        varchar("EXTERNAL_REF", 3, 40, true))
                .cons(pk("PK_TRADE", false, "TRADE_ID"), fk("FK_TRADE_BOOK", false, "BOOK_ID", "BOOK", "BOOK_ID"))
                .idx(index("IX_TRADE_BOOK", false, "BOOK_ID")).trg(trigger("TRG_TRADE", "BEGIN NULL; END;")).build());
        Map<String, CodeObjectDef> code = new LinkedHashMap<>();
        code.put("PACKAGE BODY:PKG", new CodeObjectDef("PACKAGE BODY", "PKG", "VALID", "",
                SourceNormalizer.normalize("package body pkg as\n  procedure p is begin null; end;\nend;", "A")));
        GrantDef g = new GrantDef("TRADE", "REPORTING", "SELECT", true);
        return new SchemaModel("DB_A", "SAMPLE_A", tables, Map.of("SEQ_TRADE", sequence("SEQ_TRADE", "1")),
                Map.of("TABLE:TRADE", "CREATE TABLE ... (long DBMS_METADATA text)"), List.of(), Instant.parse("2026-11-01T10:00:00Z"),
                code, Map.of("TRADES", new SynonymDef("TRADES", SynonymDef.OWN_SCHEMA, "TRADE", null)), Map.of(g.key(), g));
    }

    @Test
    void roundTripCompareShowsNoDifferences() throws IOException {
        Path f = dir.resolve("release-1.4.json");
        SchemaSnapshot.write(model(), "", f);
        String json = Files.readString(f);
        assertFalse(json.contains("DBMS_METADATA"), "DDL text is not stored");
        assertTrue(json.contains("\"format\" : \"dbmerge-schema-snapshot\""));

        SchemaModel back = SchemaSnapshot.read(f, null);
        assertEquals("release-1_4", back.nickname(), "named after the file");
        assertEquals("SAMPLE_A", back.owner());
        assertTrue(back.warnings().get(0).startsWith("Snapshot of DB_A (SAMPLE_A) extracted 2026-11-01T10:00:00Z"));

        ComparisonResult r = new SchemaComparator().compare(List.of(model(), back));
        assertTrue(r.differences().isEmpty(), "a snapshot must compare equal to its source: " + r.differences());
    }

    @Test
    void driftAgainstTheBaselineIsReported() throws IOException {
        Path f = dir.resolve("baseline.json");
        SchemaSnapshot.write(model(), "exclude BKP_*", f);
        SchemaModel live = model();
        Map<String, TableDef> tables = new LinkedHashMap<>(live.tables());
        tables.remove("BOOK");
        live = new SchemaModel("DB_A", "SAMPLE_A", tables, live.sequences(), live.ddl(), List.of(), Instant.now(),
                live.codeObjects(), live.synonyms(), live.grants());
        SchemaModel base = SchemaSnapshot.read(f, null);
        assertTrue(base.warnings().get(0).contains("filtered: exclude BKP_*"));
        ComparisonResult r = new SchemaComparator().compare(List.of(base, live));
        assertEquals(1, r.differences().size(), r.differences().toString());
        assertEquals("BOOK", r.differences().get(0).table());
    }

    @Test
    void rejectsFilesThatAreNotSnapshots() throws IOException {
        Path f = dir.resolve("x.json");
        Files.writeString(f, "{\"hello\": 1}");
        assertTrue(assertThrows(IOException.class, () -> SchemaSnapshot.read(f, null)).getMessage().contains("Not a schema snapshot"));
        Files.writeString(f, "not json");
        assertThrows(IOException.class, () -> SchemaSnapshot.read(f, null));
        Files.writeString(f, SchemaSnapshot.toJson(model(), "").replace("\"version\" : 1", "\"version\" : 9"));
        assertTrue(assertThrows(IOException.class, () -> SchemaSnapshot.read(f, null)).getMessage().contains("version 9"));
        assertThrows(IOException.class, () -> SchemaSnapshot.fromJson(SchemaSnapshot.toJson(model(), ""), "bad name"));
        assertThrows(IOException.class, () -> SchemaSnapshot.read(dir.resolve("missing.json"), null));
    }

    @Test
    void nicknamesFromFileNames() {
        assertEquals("baseline", SchemaSnapshot.nicknameFor("baseline.json"));
        assertEquals("S_2026-11-09", SchemaSnapshot.nicknameFor("2026-11-09.json"));
        assertEquals("prod_snapshot_v2", SchemaSnapshot.nicknameFor("prod snapshot v2.JSON"));
        assertEquals(30, SchemaSnapshot.nicknameFor("a".repeat(50) + ".json").length());
    }
}
