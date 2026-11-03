package com.dbmerge.data;

import com.dbmerge.data.DataModel.Conflict;
import com.dbmerge.data.DataModel.IdenticalRow;
import com.dbmerge.data.DataModel.OnlyRow;
import com.dbmerge.data.DataModel.PkCollision;
import com.dbmerge.data.DataModel.PkDivergence;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure matching logic: classifies already-loaded rows from N databases. No JDBC here, so it is unit-testable.
 *
 * Classification per business key:
 *   present in one database            -> only-in-one
 *   present in several, same data      -> identical
 *   present in several, different data -> conflict (with column-level differences)
 * Independently, when the business key is not the primary key:
 *   same PK, different business key    -> PK collision (different real-world records share an id)
 *   same business key, different PK     -> PK divergence (same record, ids will need remapping)
 * When the PK is the business key, rows with the same PK and different data are conflicts, and the caller adds a warning.
 */
public final class RowMatcher {

    private static final String SEP = "\u0001";
    private static final String NULL_MARK = "\u0000NULL";

    public record Input(List<String> databases, List<String> keyColumns, List<String> pkColumns,
                        List<String> comparedColumns, Map<String, List<Map<String, String>>> rowsByDb, int sampleLimit) { }

    public record Output(Map<String, Long> onlyInCounts, long identicalCount, long conflictCount,
                         long pkCollisionCount, long pkDivergenceCount,
                         List<OnlyRow> onlyRows, List<IdenticalRow> identicalRows, List<Conflict> conflicts,
                         List<PkCollision> pkCollisions, List<PkDivergence> pkDivergences, List<String> warnings) { }

    private RowMatcher() { }

    public static Output match(Input in) {
        List<String> warnings = new ArrayList<>();
        boolean keyIsPk = new LinkedHashSet<>(in.keyColumns()).equals(new LinkedHashSet<>(in.pkColumns()));

        // key -> db -> row
        Map<String, Map<String, Map<String, String>>> byKey = new LinkedHashMap<>();
        for (String db : in.databases()) {
            Map<String, Integer> dupes = new LinkedHashMap<>();
            for (Map<String, String> row : in.rowsByDb().getOrDefault(db, List.of())) {
                String k = keyOf(row, in.keyColumns());
                Map<String, Map<String, String>> perDb = byKey.computeIfAbsent(k, x -> new LinkedHashMap<>());
                if (perDb.containsKey(db)) dupes.merge(k, 1, Integer::sum);
                else perDb.put(db, row);
            }
            if (!dupes.isEmpty()) {
                warnings.add(db + ": the chosen business key is not unique - " + dupes.size()
                        + " key value(s) occur on more than one row (only the first row of each is compared). "
                        + "Choose a column set that identifies a row uniquely.");
            }
        }

        Map<String, Long> onlyCounts = new LinkedHashMap<>();
        for (String db : in.databases()) onlyCounts.put(db, 0L);
        long identical = 0, conflicts = 0;
        List<OnlyRow> onlyRows = new ArrayList<>();
        List<IdenticalRow> identicalRows = new ArrayList<>();
        List<Conflict> conflictRows = new ArrayList<>();
        List<String> compared = in.comparedColumns();

        for (var e : byKey.entrySet()) {
            Map<String, Map<String, String>> perDb = e.getValue();
            String display = display(e.getKey(), in.keyColumns());
            if (perDb.size() == 1) {
                var only = perDb.entrySet().iterator().next();
                onlyCounts.merge(only.getKey(), 1L, Long::sum);
                if (onlyRows.size() < in.sampleLimit()) onlyRows.add(new OnlyRow(only.getKey(), display, only.getValue()));
                continue;
            }
            Set<String> differing = new LinkedHashSet<>();
            for (String col : compared) {
                Set<String> distinct = new LinkedHashSet<>();
                for (var r : perDb.values()) distinct.add(Objects.toString(r.get(col), NULL_MARK));
                if (distinct.size() > 1) differing.add(col);
            }
            if (differing.isEmpty()) {
                identical++;
                if (identicalRows.size() < in.sampleLimit()) identicalRows.add(new IdenticalRow(display, new ArrayList<>(perDb.keySet())));
            } else {
                conflicts++;
                if (conflictRows.size() < in.sampleLimit()) {
                    conflictRows.add(new Conflict(display, new ArrayList<>(perDb.keySet()), new LinkedHashMap<>(perDb),
                            new ArrayList<>(differing)));
                }
            }
        }

        long collisions = 0, divergences = 0;
        List<PkCollision> collisionRows = new ArrayList<>();
        List<PkDivergence> divergenceRows = new ArrayList<>();
        if (!keyIsPk && !in.pkColumns().isEmpty()) {
            // same PK, different business key
            Map<String, Map<String, Map<String, String>>> byPk = new LinkedHashMap<>();
            for (String db : in.databases()) {
                for (Map<String, String> row : in.rowsByDb().getOrDefault(db, List.of())) {
                    byPk.computeIfAbsent(keyOf(row, in.pkColumns()), x -> new LinkedHashMap<>()).putIfAbsent(db, row);
                }
            }
            for (var e : byPk.entrySet()) {
                if (e.getValue().size() < 2) continue;
                Map<String, String> keyByDb = new LinkedHashMap<>();
                for (var d : e.getValue().entrySet()) keyByDb.put(d.getKey(), display(keyOf(d.getValue(), in.keyColumns()), in.keyColumns()));
                if (new LinkedHashSet<>(keyByDb.values()).size() > 1) {
                    collisions++;
                    if (collisionRows.size() < in.sampleLimit()) {
                        collisionRows.add(new PkCollision(display(e.getKey(), in.pkColumns()), keyByDb, new LinkedHashMap<>(e.getValue())));
                    }
                }
            }
            // same business key, different PK
            for (var e : byKey.entrySet()) {
                if (e.getValue().size() < 2) continue;
                Map<String, String> pkByDb = new LinkedHashMap<>();
                for (var d : e.getValue().entrySet()) pkByDb.put(d.getKey(), display(keyOf(d.getValue(), in.pkColumns()), in.pkColumns()));
                if (new LinkedHashSet<>(pkByDb.values()).size() > 1) {
                    divergences++;
                    if (divergenceRows.size() < in.sampleLimit()) divergenceRows.add(new PkDivergence(display(e.getKey(), in.keyColumns()), pkByDb));
                }
            }
        } else if (keyIsPk && conflicts > 0) {
            warnings.add("The primary key is the business key and " + conflicts + " key value(s) exist in several databases with different data. "
                    + "Either these are the same record edited differently, or unrelated records that happen to share an id. "
                    + "If ids were assigned independently per database, choose a natural business key (e.g. e-mail, SKU, code) and re-run.");
        }

        return new Output(onlyCounts, identical, conflicts, collisions, divergences,
                onlyRows, identicalRows, conflictRows, collisionRows, divergenceRows, warnings);
    }

    static String keyOf(Map<String, String> row, List<String> cols) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) sb.append(SEP);
            String v = row.get(cols.get(i));
            sb.append(v == null ? NULL_MARK : v);
        }
        return sb.toString();
    }

    static String display(String key, List<String> cols) {
        String[] parts = key.split(SEP, -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size() && i < parts.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(cols.get(i)).append('=').append(parts[i].equals(NULL_MARK) ? "NULL" : parts[i]);
        }
        return sb.toString();
    }
}
