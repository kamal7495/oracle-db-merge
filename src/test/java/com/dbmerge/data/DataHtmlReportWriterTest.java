package com.dbmerge.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.data.DataModel.DataReport;
import com.dbmerge.data.DataModel.Status;
import com.dbmerge.data.DataModel.TableDataResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataHtmlReportWriterTest {

    @Test
    void rendersCountsConflictsAndEscapesRowContent() {
        Map<String, List<Map<String, String>>> rows = new LinkedHashMap<>();
        Map<String, String> a = new LinkedHashMap<>();
        a.put("ID", "1");
        a.put("NAME", "<script>alert(1)</script>");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("ID", "1");
        b.put("NAME", "Bob & \"Co\"");
        rows.put("A", List.of(a));
        rows.put("B", List.of(b));
        RowMatcher.Output o = RowMatcher.match(new RowMatcher.Input(List.of("A", "B"), List.of("ID"), List.of("ID"),
                List.of("ID", "NAME"), rows, 10));

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("A", 1L);
        counts.put("B", 1L);
        TableDataResult t = new TableDataResult("CUSTOMER", Status.COMPARED, null, List.of("ID"), true, List.of("ID"), counts,
                List.of("ID", "NAME"), Map.of(), o.onlyInCounts(), o.identicalCount(), o.conflictCount(), o.pkCollisionCount(),
                o.pkDivergenceCount(), o.onlyRows(), o.identicalRows(), o.conflicts(), o.pkCollisions(), o.pkDivergences(), o.warnings());
        TableDataResult missing = new TableDataResult("GONE", Status.SINGLE_DATABASE, "Table exists in only A; nothing to compare.",
                List.of(), false, List.of(), Map.of("A", 3L, "B", -1L), List.of(), Map.of(), Map.of(), 0, 0, 0, 0,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        String html = new DataHtmlReportWriter().render(new DataReport("abc12345", Instant.now(), "ddl1", List.of("A", "B"), 10, List.of(t, missing)));

        assertFalse(html.contains("<script>alert(1)</script>"));
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(html.contains("Bob &amp; &quot;Co&quot;"));
        assertTrue(html.contains("Same key, different data (1)"));
        assertTrue(html.contains("natural business key"));
        assertTrue(html.contains("no table"));
        assertTrue(html.contains("nothing to compare"));
    }
}
