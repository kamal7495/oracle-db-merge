package com.dbmerge.ddl.compare;

import static com.dbmerge.ddl.compare.Fixtures.fk;
import static com.dbmerge.ddl.compare.Fixtures.number;
import static com.dbmerge.ddl.compare.Fixtures.pk;
import static com.dbmerge.ddl.compare.Fixtures.sequence;
import static com.dbmerge.ddl.compare.Fixtures.table;
import static com.dbmerge.ddl.compare.Fixtures.varchar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.ddl.model.CodeObjectDef;
import com.dbmerge.ddl.model.GrantDef;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.SequenceDef;
import com.dbmerge.ddl.model.SourceNormalizer;
import com.dbmerge.ddl.model.SynonymDef;
import com.dbmerge.ddl.model.TableDef;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompareFilterTest {

    private static SchemaModel model(String nick, boolean withBackupColumn) {
        Map<String, TableDef> tables = new LinkedHashMap<>();
        tables.put("BOOK", table("BOOK").col(number("BOOK_ID", 1, 10, 0, false)).cons(pk("PK_BOOK", false, "BOOK_ID")).build());
        tables.put("TRADE", table("TRADE").col(number("TRADE_ID", 1, 10, 0, false), number("BOOK_ID", 2, 10, 0, false))
                .cons(pk("PK_TRADE", false, "TRADE_ID"), fk("FK_TRADE_BOOK", false, "BOOK_ID", "BOOK", "BOOK_ID")).build());
        var bkp = table("BKP_TRADE_2023").col(number("TRADE_ID", 1, 10, 0, false));
        if (withBackupColumn) bkp = bkp.col(varchar("NOTE", 2, 10, true));
        tables.put("BKP_TRADE_2023", bkp.build());
        tables.put("TMP_STAGE", table("TMP_STAGE").col(number("X", 1, 10, 0, true)).build());
        Map<String, SequenceDef> seqs = new LinkedHashMap<>();
        seqs.put("SEQ_TRADE", sequence("SEQ_TRADE", "1"));
        seqs.put("TMP_SEQ", sequence("TMP_SEQ", "1"));
        Map<String, CodeObjectDef> code = new LinkedHashMap<>();
        code.put("VIEW:V_TRADE", new CodeObjectDef("VIEW", "V_TRADE", "VALID", "", SourceNormalizer.normalize("select 1 from dual", nick)));
        code.put("VIEW:TMP_V", new CodeObjectDef("VIEW", "TMP_V", "VALID", "", SourceNormalizer.normalize("select 2 from dual", nick)));
        Map<String, SynonymDef> syn = Map.of("TMP_SYN", new SynonymDef("TMP_SYN", SynonymDef.OWN_SCHEMA, "TRADE", null));
        Map<String, GrantDef> grants = new LinkedHashMap<>();
        GrantDef g1 = new GrantDef("TRADE", "REPORTING", "SELECT", false);
        GrantDef g2 = new GrantDef("TMP_STAGE", "REPORTING", "SELECT", false);
        grants.put(g1.key(), g1);
        grants.put(g2.key(), g2);
        return new SchemaModel(nick, nick, tables, seqs, Map.of("TABLE:TMP_STAGE", "ddl", "TABLE:TRADE", "ddl"), List.of(),
                Instant.now(), code, syn, grants);
    }

    @Test
    void wildcardsAreCaseInsensitiveAndUnderscoreIsLiteral() {
        assertTrue(CompareFilter.matches("BKP_*", "BKP_TRADE_2023"));
        assertTrue(CompareFilter.matches("TMP%", "TMP_STAGE"));
        assertTrue(CompareFilter.matches("SEQ_?", "SEQ_A"));
        assertFalse(CompareFilter.matches("BKP_*", "BKPXTRADE"), "_ must not act as a single-character wildcard");
        CompareFilter f = CompareFilter.of(null, List.of("bkp_*"), null);
        assertFalse(f.keeps("BKP_TRADE_2023"));
        assertTrue(f.keeps("TRADE"));
    }

    @Test
    void excludeRemovesEveryKindOfObjectByName() {
        SchemaModel m = CompareFilter.of(null, List.of("BKP_*, TMP_*"), null).apply(model("A", false));
        assertEquals(List.of("BOOK", "TRADE"), List.copyOf(m.tables().keySet()));
        assertEquals(List.of("SEQ_TRADE"), List.copyOf(m.sequences().keySet()));
        assertEquals(List.of("VIEW:V_TRADE"), List.copyOf(m.codeObjects().keySet()));
        assertTrue(m.synonyms().isEmpty());
        assertEquals(1, m.grants().size(), "the grant on an excluded table goes too");
        assertEquals(List.of("TABLE:TRADE"), List.copyOf(m.ddl().keySet()));
        assertTrue(m.warnings().isEmpty());
    }

    @Test
    void includeKeepsOnlyMatchesAndExcludeStillWins() {
        SchemaModel m = CompareFilter.of(List.of("*TRADE*"), List.of("BKP_*"), null).apply(model("A", false));
        assertEquals(List.of("TRADE"), List.copyOf(m.tables().keySet()));
        assertEquals(List.of("VIEW:V_TRADE"), List.copyOf(m.codeObjects().keySet()));
    }

    @Test
    void keepingAChildButNotItsDeclaredParentWarns() {
        SchemaModel m = CompareFilter.of(List.of("TRADE"), null, null).apply(model("A", false));
        assertEquals(1, m.warnings().size(), m.warnings().toString());
        assertTrue(m.warnings().get(0).contains("excludes its parent BOOK"), m.warnings().get(0));
    }

    @Test
    void excludedTablesProduceNoFindings() {
        SchemaComparator cmp = new SchemaComparator();
        List<SchemaModel> models = List.of(model("A", false), model("B", true));
        assertTrue(cmp.compare(models).differences().stream().anyMatch(d -> "BKP_TRADE_2023".equals(d.table())));
        ComparisonResult r = cmp.compare(models, CompareFilter.of(null, List.of("BKP_*"), null));
        assertTrue(r.differences().isEmpty(), r.differences().toString());
        assertEquals("exclude BKP_*", r.filter());
        assertFalse(r.models().get(0).tables().containsKey("BKP_TRADE_2023"), "later phases get the filtered models");
    }

    @Test
    void ignoreDropsFindingsByKindOrCategory() {
        SchemaComparator cmp = new SchemaComparator();
        List<SchemaModel> models = List.of(model("A", false), model("B", true));
        assertTrue(cmp.compare(models, CompareFilter.of(null, null, List.of("missing_column"))).differences().isEmpty());
        assertTrue(cmp.compare(models, CompareFilter.of(null, null, List.of("COLUMN"))).differences().isEmpty());
        assertEquals(1, cmp.compare(models, CompareFilter.of(null, null, List.of("GRANT"))).differences().size());
    }

    @Test
    void rejectsBadPatternsAndUnknownKinds() {
        assertThrows(IllegalArgumentException.class, () -> CompareFilter.of(null, List.of("TRADE'; DROP"), null));
        assertThrows(IllegalArgumentException.class, () -> CompareFilter.of(List.of("A.B"), null, null));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CompareFilter.of(null, null, List.of("NOPE")));
        assertTrue(e.getMessage().contains("GRANT"), "the message lists the categories");
        assertTrue(CompareFilter.of(List.of(""), List.of(" ", ","), List.of("")).isEmpty(), "blank UI fields mean no filter");
    }
}
