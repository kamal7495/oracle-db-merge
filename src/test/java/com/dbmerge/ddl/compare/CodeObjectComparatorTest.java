package com.dbmerge.ddl.compare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.ddl.model.CodeObjectDef;
import com.dbmerge.ddl.model.GrantDef;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.SourceNormalizer;
import com.dbmerge.ddl.model.SynonymDef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class CodeObjectComparatorTest {

    private static CodeObjectDef code(String type, String name, String status, String source, String owner) {
        return new CodeObjectDef(type, name, status, "", SourceNormalizer.normalize(source, owner));
    }

    private static SchemaModel model(String nick, List<CodeObjectDef> code, List<SynonymDef> syns, List<GrantDef> grants,
                                     String... tables) {
        Map<String, CodeObjectDef> c = new TreeMap<>();
        code.forEach(o -> c.put(o.key(), o));
        Map<String, SynonymDef> s = new TreeMap<>();
        syns.forEach(o -> s.put(o.name(), o));
        Map<String, GrantDef> g = new TreeMap<>();
        grants.forEach(o -> g.put(o.key(), o));
        Map<String, com.dbmerge.ddl.model.TableDef> t = new TreeMap<>();
        for (String name : tables) t.put(name, Fixtures.table(name).col(Fixtures.number("ID", 1, 10, 0, false)).build());
        return new SchemaModel(nick, nick.toUpperCase(), t, Map.of(), new LinkedHashMap<>(), new ArrayList<>(), Instant.now(),
                c, s, g);
    }

    private static List<Difference> of(ComparisonResult r, DiffKind kind) {
        return r.differences().stream().filter(d -> d.kind() == kind).toList();
    }

    @Test
    void missingObjectsAreHighAndFormattingOnlyChangesAreIgnored() {
        SchemaModel a = model("A", List.of(
                code("VIEW", "V_TRADES", "VALID", "SELECT id FROM a.trade -- open", "A"),
                code("PACKAGE", "PKG", "VALID", "PACKAGE pkg AS END;", "A"),
                code("PROCEDURE", "ONLY_A", "VALID", "PROCEDURE only_a IS BEGIN NULL; END;", "A")), List.of(), List.of());
        SchemaModel b = model("B", List.of(
                code("VIEW", "V_TRADES", "VALID", "select ID\nfrom B.TRADE", "B"),
                code("PACKAGE", "PKG", "VALID", "package PKG as\nend;", "B")), List.of(), List.of());
        ComparisonResult r = new SchemaComparator().compare(List.of(a, b));
        assertEquals(1, r.differences().size(), r.differences().toString());
        Difference d = r.differences().get(0);
        assertEquals(DiffKind.MISSING_PLSQL, d.kind());
        assertEquals(Severity.HIGH, d.severity());
        assertEquals("PROCEDURE ONLY_A", d.object());
        assertEquals(SchemaComparator.MISSING, d.values().get("B"));
    }

    @Test
    void changedSourceIsMediumWithTheFirstDifferingLine() {
        String body = "PACKAGE BODY pkg AS\n  FUNCTION f RETURN NUMBER IS\n  BEGIN\n    RETURN %s;\n  END;\nEND;";
        SchemaModel a = model("A", List.of(code("PACKAGE BODY", "PKG", "VALID", body.formatted("1"), "A")), List.of(), List.of());
        SchemaModel b = model("B", List.of(code("PACKAGE BODY", "PKG", "VALID", body.formatted("2"), "B")), List.of(), List.of());
        List<Difference> d = of(new SchemaComparator().compare(List.of(a, b)), DiffKind.PLSQL_SOURCE_MISMATCH);
        assertEquals(1, d.size());
        assertEquals(Severity.MEDIUM, d.get(0).severity());
        String desc = d.get(0).description();
        assertTrue(desc.contains("A line 4 `RETURN 1;` vs B line 4 `RETURN 2;`"), desc);
        assertTrue(desc.length() < 600, "descriptions stay short: " + desc.length());
    }

    @Test
    void viewsAndMaterializedViewsUseTheirOwnKinds() {
        SchemaModel a = model("A", List.of(code("VIEW", "V", "VALID", "SELECT 1 x FROM dual", "A"),
                new CodeObjectDef("MATERIALIZED VIEW", "MV", "VALID", "REFRESH FAST ON COMMIT",
                        SourceNormalizer.normalize("SELECT id FROM t", "A"))), List.of(), List.of());
        SchemaModel b = model("B", List.of(code("VIEW", "V", "VALID", "SELECT 2 x FROM dual", "B"),
                new CodeObjectDef("MATERIALIZED VIEW", "MV", "VALID", "REFRESH COMPLETE ON DEMAND",
                        SourceNormalizer.normalize("SELECT id FROM t", "B"))), List.of(), List.of());
        ComparisonResult r = new SchemaComparator().compare(List.of(a, b));
        assertEquals(1, of(r, DiffKind.VIEW_DEFINITION_MISMATCH).size());
        List<Difference> mv = of(r, DiffKind.MVIEW_DEFINITION_MISMATCH);
        assertEquals(1, mv.size());
        assertTrue(mv.get(0).description().startsWith("Options differ"), mv.get(0).description());
    }

    @Test
    void invalidVersusValidIsMedium() {
        SchemaModel a = model("A", List.of(code("FUNCTION", "F", "VALID", "FUNCTION f RETURN NUMBER IS BEGIN RETURN 1; END;", "A")),
                List.of(), List.of());
        SchemaModel b = model("B", List.of(code("FUNCTION", "F", "INVALID", "FUNCTION f RETURN NUMBER IS BEGIN RETURN 1; END;", "B")),
                List.of(), List.of());
        ComparisonResult r = new SchemaComparator().compare(List.of(a, b));
        assertEquals(1, r.differences().size(), r.differences().toString());
        assertEquals(DiffKind.OBJECT_STATUS_MISMATCH, r.differences().get(0).kind());
        assertEquals(Severity.MEDIUM, r.differences().get(0).severity());
        assertEquals("INVALID", r.differences().get(0).values().get("B"));
    }

    @Test
    void synonymsAreComparedWithOwnSchemaTargetsTreatedAsEqual() {
        SchemaModel a = model("A", List.of(), List.of(
                new SynonymDef("S_TRADE", SynonymDef.OWN_SCHEMA, "TRADE", null),
                new SynonymDef("S_FX", "MARKET", "FX_RATES", null),
                new SynonymDef("S_ONLY_A", "X", "Y", null)), List.of());
        SchemaModel b = model("B", List.of(), List.of(
                new SynonymDef("S_TRADE", SynonymDef.OWN_SCHEMA, "TRADE", null),
                new SynonymDef("S_FX", "MARKET", "FX_RATES", "PROD_LINK")), List.of());
        ComparisonResult r = new SchemaComparator().compare(List.of(a, b));
        assertEquals(1, of(r, DiffKind.MISSING_SYNONYM).size());
        assertEquals(Severity.MEDIUM, of(r, DiffKind.MISSING_SYNONYM).get(0).severity());
        List<Difference> target = of(r, DiffKind.SYNONYM_TARGET_MISMATCH);
        assertEquals(1, target.size());
        assertEquals("MARKET.FX_RATES@PROD_LINK", target.get(0).values().get("B"));
    }

    @Test
    void missingGrantsAreMediumUnlessTheObjectItselfIsMissing() {
        GrantDef select = new GrantDef("TRADE", "REPORTING", "SELECT", false);
        GrantDef onBook = new GrantDef("BOOK", "REPORTING", "SELECT", false);
        GrantDef upd = new GrantDef("TRADE", "BATCH", "UPDATE", true);
        SchemaModel a = model("A", List.of(), List.of(), List.of(select, onBook, upd), "TRADE", "BOOK");
        SchemaModel b = model("B", List.of(), List.of(), List.of(new GrantDef("TRADE", "BATCH", "UPDATE", false)), "TRADE");
        ComparisonResult r = new SchemaComparator().compare(List.of(a, b));
        List<Difference> missing = of(r, DiffKind.MISSING_GRANT);
        assertEquals(1, missing.size(), "the grant on BOOK is covered by the missing table: " + missing);
        assertEquals("GRANT SELECT ON TRADE TO REPORTING", missing.get(0).object());
        assertEquals(Severity.MEDIUM, missing.get(0).severity());
        List<Difference> option = of(r, DiffKind.GRANT_OPTION_MISMATCH);
        assertEquals(1, option.size());
        assertEquals(Severity.LOW, option.get(0).severity());
    }

    @Test
    void modelsWithoutCodeObjectsStillCompare() {
        ComparisonResult r = new SchemaComparator().compare(List.of(Fixtures.model("A"), Fixtures.model("B")));
        assertTrue(r.differences().isEmpty());
    }
}
