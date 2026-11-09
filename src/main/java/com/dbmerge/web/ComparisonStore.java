package com.dbmerge.web;

import com.dbmerge.data.DataModel.DataReport;
import com.dbmerge.ddl.compare.ComparisonResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Keeps the last few DDL and data comparison results in memory so later phases can build on them. */
@Component
public class ComparisonStore {

    private static final int KEEP = 5;
    private final Map<String, ComparisonResult> ddl = new LinkedHashMap<>();
    private final Map<String, DataReport> data = new LinkedHashMap<>();
    private final Map<String, Set<String>> snapshots = new LinkedHashMap<>();

    public synchronized void put(ComparisonResult r) {
        put(r, Set.of());
    }

    /** @param snapshotNames models in {@code r} that came from snapshot files, not live databases */
    public synchronized void put(ComparisonResult r, Set<String> snapshotNames) {
        ddl.put(r.id(), r);
        snapshots.put(r.id(), Set.copyOf(snapshotNames));
        trim(ddl);
        trim(snapshots);
    }

    /**
     * The comparison, for a phase that reads live data: refused when it includes a snapshot, which has no rows.
     */
    public synchronized ComparisonResult requireLive(String id) {
        ComparisonResult r = get(id).orElseThrow(() -> new IllegalArgumentException("Unknown DDL comparison: " + id));
        Set<String> snaps = snapshots.getOrDefault(id, Set.of());
        if (!snaps.isEmpty()) {
            throw new IllegalArgumentException("DDL comparison " + id + " includes snapshot(s) " + String.join(", ", new java.util.TreeSet<>(snaps))
                    + ". Data comparison and merging need live databases: compare without snapshots first.");
        }
        return r;
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
