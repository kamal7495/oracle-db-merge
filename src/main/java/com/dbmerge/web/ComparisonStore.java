package com.dbmerge.web;

import com.dbmerge.data.DataModel.DataReport;
import com.dbmerge.ddl.compare.ComparisonResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Keeps the last few DDL and data comparison results in memory so later phases can build on them. */
@Component
public class ComparisonStore {

    private static final int KEEP = 5;
    private final Map<String, ComparisonResult> ddl = new LinkedHashMap<>();
    private final Map<String, DataReport> data = new LinkedHashMap<>();

    public synchronized void put(ComparisonResult r) {
        ddl.put(r.id(), r);
        trim(ddl);
    }

    public synchronized Optional<ComparisonResult> get(String id) {
        return Optional.ofNullable(ddl.get(id));
    }

    public synchronized void put(DataReport r) {
        data.put(r.id(), r);
        trim(data);
    }

    public synchronized Optional<DataReport> getData(String id) {
        return Optional.ofNullable(data.get(id));
    }

    private static <T> void trim(Map<String, T> m) {
        while (m.size() > KEEP) m.remove(m.keySet().iterator().next());
    }
}
