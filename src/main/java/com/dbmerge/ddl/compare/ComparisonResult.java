package com.dbmerge.ddl.compare;

import com.dbmerge.ddl.model.SchemaModel;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record ComparisonResult(
        String id,
        Instant generatedAt,
        List<String> databases,
        List<SchemaModel> models,
        List<Difference> differences,
        String filter) {             // CompareFilter#describe(), empty when nothing was filtered

    public ComparisonResult {
        filter = filter == null ? "" : filter;
    }

    public ComparisonResult(String id, Instant generatedAt, List<String> databases, List<SchemaModel> models,
                            List<Difference> differences) {
        this(id, generatedAt, databases, models, differences, "");
    }

    public Map<Severity, Integer> countsBySeverity() {
        Map<Severity, Integer> m = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) m.put(s, 0);
        for (Difference d : differences) m.merge(d.severity(), 1, Integer::sum);
        return m;
    }

    public Map<DiffKind, Integer> countsByKind() {
        Map<DiffKind, Integer> m = new EnumMap<>(DiffKind.class);
        for (Difference d : differences) m.merge(d.kind(), 1, Integer::sum);
        return m;
    }

    /** Most serious severity per table (tables without findings are absent). */
    public Map<String, Severity> worstSeverityByTable() {
        Map<String, Severity> m = new TreeMap<>();
        for (Difference d : differences) {
            if (d.table() == null) continue;
            m.merge(d.table(), d.severity(), (a, b) -> a.ordinal() <= b.ordinal() ? a : b);
        }
        return m;
    }
}
