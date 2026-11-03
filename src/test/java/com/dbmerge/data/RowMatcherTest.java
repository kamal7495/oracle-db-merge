package com.dbmerge.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RowMatcherTest {

    private static Map<String, String> row(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static Map<String, String> cust(String id, String email, String name, String limit) {
        return row("CUSTOMER_ID", id, "EMAIL", email, "NAME", name, "CREDIT_LIMIT", limit);
    }

    private static RowMatcher.Output customers(int sampleLimit) {
        Map<String, List<Map<String, String>>> rows = new LinkedHashMap<>();
        rows.put("A", List.of(cust("1", "alice@x.com", "Alice A", "1000"), cust("2", "bob@x.com", "Bob", "500")));
        rows.put("B", List.of(cust("1", "carol@x.com", "Carol", "750"), cust("2", "bob@x.com", "Bob", "500"),
                cust("3", "alice@x.com", "Alice Anderson", "2000")));
        return RowMatcher.match(new RowMatcher.Input(List.of("A", "B"), List.of("EMAIL"), List.of("CUSTOMER_ID"),
                List.of("EMAIL", "NAME", "CREDIT_LIMIT"), rows, sampleLimit));
    }

    @Test
    void classifiesOnlyIdenticalAndConflictingRowsOnABusinessKey() {
        RowMatcher.Output o = customers(100);
        assertEquals(0L, o.onlyInCounts().get("A"));
        assertEquals(1L, o.onlyInCounts().get("B"));
        assertEquals("EMAIL=carol@x.com", o.onlyRows().get(0).key());
        assertEquals(1L, o.identicalCount());
        assertEquals(1L, o.conflictCount());
        var conflict = o.conflicts().get(0);
        assertEquals("EMAIL=alice@x.com", conflict.key());
        assertEquals(List.of("NAME", "CREDIT_LIMIT"), conflict.differingColumns());
        assertEquals("Alice A", conflict.rowsByDb().get("A").get("NAME"));
        assertEquals("Alice Anderson", conflict.rowsByDb().get("B").get("NAME"));
    }

    @Test
    void detectsPkCollisionsAndPkDivergence() {
        RowMatcher.Output o = customers(100);
        assertEquals(1L, o.pkCollisionCount());
        var c = o.pkCollisions().get(0);
        assertEquals("CUSTOMER_ID=1", c.pk());
        assertEquals("EMAIL=alice@x.com", c.businessKeyByDb().get("A"));
        assertEquals("EMAIL=carol@x.com", c.businessKeyByDb().get("B"));
        assertEquals(1L, o.pkDivergenceCount());
        assertEquals("CUSTOMER_ID=1", o.pkDivergences().get(0).pkByDb().get("A"));
        assertEquals("CUSTOMER_ID=3", o.pkDivergences().get(0).pkByDb().get("B"));
    }

    @Test
    void primaryKeyAsBusinessKeyWarnsWhenOverlappingRowsDiffer() {
        Map<String, List<Map<String, String>>> rows = new LinkedHashMap<>();
        rows.put("A", List.of(row("ID", "2", "NAME", "South")));
        rows.put("B", List.of(row("ID", "2", "NAME", "East")));
        var o = RowMatcher.match(new RowMatcher.Input(List.of("A", "B"), List.of("ID"), List.of("ID"),
                List.of("ID", "NAME"), rows, 100));
        assertEquals(1L, o.conflictCount());
        assertEquals(0L, o.pkCollisionCount());
        assertEquals(1, o.warnings().size());
        assertTrue(o.warnings().get(0).contains("natural business key"));
    }

    @Test
    void nullsMatchNullsAndDifferFromValues() {
        Map<String, List<Map<String, String>>> rows = new LinkedHashMap<>();
        rows.put("A", List.of(row("ID", "1", "X", null), row("ID", "2", "X", null)));
        rows.put("B", List.of(row("ID", "1", "X", null), row("ID", "2", "X", "v")));
        var o = RowMatcher.match(new RowMatcher.Input(List.of("A", "B"), List.of("ID"), List.of("ID"),
                List.of("X"), rows, 100));
        assertEquals(1L, o.identicalCount());
        assertEquals(1L, o.conflictCount());
    }

    @Test
    void warnsWhenBusinessKeyIsNotUniqueWithinADatabase() {
        Map<String, List<Map<String, String>>> rows = new LinkedHashMap<>();
        rows.put("A", List.of(row("ID", "1", "CODE", "X"), row("ID", "2", "CODE", "X")));
        rows.put("B", List.of(row("ID", "1", "CODE", "X")));
        var o = RowMatcher.match(new RowMatcher.Input(List.of("A", "B"), List.of("CODE"), List.of("ID"),
                List.of("CODE"), rows, 100));
        assertTrue(o.warnings().stream().anyMatch(w -> w.contains("not unique")));
    }

    @Test
    void sampleLimitCapsDetailListsButNotCounts() {
        List<Map<String, String>> a = new ArrayList<>();
        List<Map<String, String>> b = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            a.add(row("ID", String.valueOf(i), "V", "a"));
            b.add(row("ID", String.valueOf(i), "V", "b"));
        }
        Map<String, List<Map<String, String>>> rows = new LinkedHashMap<>();
        rows.put("A", a);
        rows.put("B", b);
        var o = RowMatcher.match(new RowMatcher.Input(List.of("A", "B"), List.of("ID"), List.of("ID"), List.of("V"), rows, 10));
        assertEquals(50L, o.conflictCount());
        assertEquals(10, o.conflicts().size());
        assertFalse(o.warnings().isEmpty());
    }

    @Test
    void threeWayMatchingReportsWhichDatabasesAgree() {
        Map<String, List<Map<String, String>>> rows = new LinkedHashMap<>();
        rows.put("A", List.of(row("ID", "1", "V", "x")));
        rows.put("B", List.of(row("ID", "1", "V", "x")));
        rows.put("C", List.of(row("ID", "1", "V", "y")));
        var o = RowMatcher.match(new RowMatcher.Input(List.of("A", "B", "C"), List.of("ID"), List.of("ID"), List.of("V"), rows, 10));
        assertEquals(1L, o.conflictCount());
        assertEquals(List.of("A", "B", "C"), o.conflicts().get(0).databases());
    }
}
