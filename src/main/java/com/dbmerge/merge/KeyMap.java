package com.dbmerge.merge;

import com.dbmerge.ddl.model.ColumnDef;
import com.dbmerge.ddl.model.ConstraintDef;
import com.dbmerge.ddl.model.TableDef;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where every source row ended up in the master: (source, table, old key) to master primary key. Planner and executor
 * use it to rewrite child foreign keys.
 *
 * <p>Built for large tables. Most rows keep their key (inserted with it, or matched to a master row with the same
 * key), so for those only the fact "this key exists" is stored, in a compact {@link KeySet}; their primary key is
 * rebuilt from the canonical key text and the column types on lookup. Only rows whose key changes (re-keyed, matched
 * to a master row with another key, or waiting for a sequence value) keep a map entry.
 */
final class KeyMap {

    private static final String SEP = "\u0001";

    private final Map<String, TableDef> tables;
    private final Map<String, Group> groups = new HashMap<>();

    private static final class Group {
        final KeySet identity = new KeySet();
        final Map<String, Map<String, Object>> explicit = new HashMap<>();
    }

    /** @param tables table definitions (the master's, as they will look after the merge) for the primary-key types */
    KeyMap(Map<String, TableDef> tables) {
        this.tables = tables;
    }

    /** Records where (source, table, oldKey) went; {@code pk} empty means "known only once the run allocates it". */
    void put(String source, String table, String oldKey, Map<String, Object> pk) {
        Group g = groups.computeIfAbsent(source + "|" + table, k -> new Group());
        List<String> pkCols = pkColumns(table);
        boolean identity = pk != null && !pk.isEmpty() && !pkCols.isEmpty() && pk.keySet().containsAll(pkCols)
                && pk.size() == pkCols.size() && oldKey.equals(Canon.key(pk, pkCols));
        if (identity) {
            g.explicit.remove(oldKey);
            g.identity.add(oldKey);
        } else {
            g.identity.remove(oldKey);
            g.explicit.put(oldKey, pk == null ? Map.of() : new LinkedHashMap<>(pk));
        }
    }

    /** Forgets (source, table, oldKey): the row turned out not to be merged (an orphan). */
    void remove(String source, String table, String oldKey) {
        Group g = groups.get(source + "|" + table);
        if (g == null) return;
        g.identity.remove(oldKey);
        g.explicit.remove(oldKey);
    }

    /** The master primary key for (source, table, oldKey), or null when that row was not planned/applied. */
    Map<String, Object> get(String source, String table, String oldKey) {
        Group g = groups.get(source + "|" + table);
        if (g == null) return null;
        Map<String, Object> e = g.explicit.get(oldKey);
        if (e != null) return e;
        return g.identity.contains(oldKey) ? rebuild(table, oldKey) : null;
    }

    /** Whether any row of {@code table} from {@code source} was recorded. */
    boolean hasAny(String source, String table) {
        Group g = groups.get(source + "|" + table);
        return g != null && (!g.identity.isEmpty() || !g.explicit.isEmpty());
    }

    /** Entries whose key changed or is pending; the rest kept their key. */
    long changedCount() {
        long n = 0;
        for (Group g : groups.values()) n += g.explicit.size();
        return n;
    }

    private List<String> pkColumns(String table) {
        TableDef def = tables.get(table);
        if (def == null) return List.of();
        return def.constraints().stream().filter(c -> c.type().equals("P")).map(ConstraintDef::columns).findFirst().orElse(List.of());
    }

    private Map<String, Object> rebuild(String table, String oldKey) {
        List<String> cols = pkColumns(table);
        String[] parts = oldKey.split(SEP, -1);
        Map<String, Object> pk = new LinkedHashMap<>();
        TableDef def = tables.get(table);
        for (int i = 0; i < cols.size(); i++) {
            ColumnDef col = def.columns().get(cols.get(i));
            pk.put(cols.get(i), Canon.parse(i < parts.length ? parts[i] : null, col));
        }
        return pk;
    }
}
