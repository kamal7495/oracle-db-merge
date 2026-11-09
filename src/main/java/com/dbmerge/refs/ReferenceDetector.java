package com.dbmerge.refs;

import com.dbmerge.ddl.model.ColumnDef;
import com.dbmerge.ddl.model.ConstraintDef;
import com.dbmerge.ddl.model.IndexDef;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.TableDef;
import com.dbmerge.refs.RefModel.Candidate;
import com.dbmerge.refs.RefModel.Confidence;
import com.dbmerge.refs.RefModel.Kind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Finds columns that look like foreign keys but are not declared as FOREIGN KEY constraints. Works on the schema
 * model only (names, types, keys); {@link ReferenceVerifier} can then check the guesses against the data.
 *
 * <p>Rules, strongest first; the first one that matches a column wins:
 * <ol>
 *   <li>Same name as another table's single-column primary key ({@code TRADE_KEYWORD.TRADE_ID} -> {@code TRADE}).
 *       When that is the column's own table's primary key too, it is a shared primary key (1:1 extension table).</li>
 *   <li>{@code <table><suffix>} naming an existing table ({@code CUSTOMER_ID} -> {@code CUSTOMER}, also plural tables).</li>
 *   <li>Role-prefixed: ends in another table's primary-key name or {@code _<table><suffix>}
 *       ({@code PARENT_TRADE_ID} -> {@code TRADE}).</li>
 *   <li>An ID column next to a type/class column with the same prefix ({@code OBJECT_ID} + {@code OBJECT_CLASS}).</li>
 *   <li>Any other column named like an identifier with no matching table (low confidence).</li>
 * </ol>
 * Columns already covered by a declared foreign key are skipped, and so are types that cannot hold keys (dates, LOBs).
 */
public class ReferenceDetector {

    /** Longest first so "_NUM" is tried before "_NO" would leave "M". "ID" without underscore last. */
    static final List<String> ID_SUFFIXES = List.of("_CODE", "_NUM", "_KEY", "_REF", "_ID", "_NB", "_NO", "ID");
    /** Suffixes strong enough to flag a column on their own (rule 5). */
    static final List<String> UNRESOLVED_SUFFIXES = List.of("_ID", "_NB", "_REF", "_KEY");
    static final List<String> TYPE_SUFFIXES = List.of("_TYPE", "_CLASS", "_KIND", "_TABLE", "_ENTITY", "_OBJECT", "_CLASSNAME");
    /** Primary-key names shared by many unrelated tables; matching on them would link everything to everything. */
    static final Set<String> GENERIC_PK_NAMES = Set.of("ID", "OID", "UUID", "GUID", "KEY", "CODE", "NAME", "SEQ", "NB",
            "VERSION", "ROW_ID", "M_IDENTITY", "M_NB", "IDENTITY");

    public List<Candidate> detect(SchemaModel model) {
        Map<String, TableDef> tables = model.tables();
        Map<String, String> pkOf = new HashMap<>();                     // table -> its single-column PK
        Map<String, List<String>> tablesByPk = new TreeMap<>();         // PK column name -> tables having it as single-column PK
        Set<String> declared = new HashSet<>();                         // TABLE.COL covered by a declared FK
        Set<String> leading = new HashSet<>();                          // TABLE.COL leading an index or key

        for (TableDef t : tables.values()) {
            for (ConstraintDef c : t.constraints()) {
                if (c.type().equals("P") && c.columns().size() == 1) pkOf.put(t.name(), c.columns().get(0));
                if (c.type().equals("R")) for (String col : c.columns()) declared.add(t.name() + "." + col);
                if ((c.type().equals("P") || c.type().equals("U") || c.type().equals("R")) && !c.columns().isEmpty()) {
                    leading.add(t.name() + "." + c.columns().get(0));
                }
            }
            for (IndexDef i : t.indexes()) {
                if (!i.columns().isEmpty()) leading.add(t.name() + "." + i.columns().get(0).replace(" DESC", ""));
            }
        }
        pkOf.forEach((t, pk) -> {
            if (!GENERIC_PK_NAMES.contains(pk)) tablesByPk.computeIfAbsent(pk, k -> new ArrayList<>()).add(t);
        });
        tablesByPk.values().forEach(l -> l.sort(Comparator.naturalOrder()));

        List<Candidate> out = new ArrayList<>();
        for (TableDef t : tables.values()) {
            List<ColumnDef> cols = t.columns().values().stream().filter(c -> !c.virtual())
                    .sorted(Comparator.comparingInt(ColumnDef::position)).toList();
            for (ColumnDef col : cols) {
                if (declared.contains(t.name() + "." + col.name()) || !keyType(col)) continue;
                Candidate c = classify(t, col, tables, pkOf, tablesByPk, leading.contains(t.name() + "." + col.name()));
                if (c != null) out.add(c);
            }
        }
        out.sort(Comparator.comparing(Candidate::confidence).thenComparing(Candidate::kind).thenComparing(Candidate::table)
                .thenComparing(Candidate::column));
        return out;
    }

    private Candidate classify(TableDef t, ColumnDef col, Map<String, TableDef> tables, Map<String, String> pkOf,
                               Map<String, List<String>> tablesByPk, boolean indexed) {
        String n = col.name();
        boolean ownPk = n.equals(pkOf.get(t.name()));

        // 1. same name as another table's single-column primary key
        List<String> owners = new ArrayList<>(tablesByPk.getOrDefault(n, List.of()));
        owners.remove(t.name());
        if (!owners.isEmpty()) {
            // Only a table's own primary key can make that table the root; otherwise the parent must be another table.
            String root = root(stem(n), owners, ownPk ? t.name() : null, tables);
            if (ownPk) {
                if (root != null && root.equals(t.name())) return null;     // this table is the parent of the others
                List<String> notes = new ArrayList<>();
                if (root != null) {
                    notes.add("This table's primary key is also its parent's key: if the parent row is re-keyed, this row must be re-keyed with it.");
                    return typed(new Candidate(t.name(), n, col.displayType(), Kind.SHARED_PRIMARY_KEY, Confidence.MEDIUM, List.of(root), n,
                            "own primary key " + n + " is also the primary key of " + root, notes, indexed, List.of()), col, tables, pkOf);
                }
                notes.add("Names alone cannot tell which table is the parent. With the data check, the parent is the one where 100% of this table's keys resolve.");
                notes.add("Rows sharing a key must be re-keyed together.");
                return typed(new Candidate(t.name(), n, col.displayType(), Kind.SHARED_PRIMARY_KEY, Confidence.MEDIUM, owners, n,
                        "shares primary key " + n + " with " + String.join(", ", owners), notes, indexed, List.of()), col, tables, pkOf);
            }
            List<String> parents = root != null ? List.of(root) : owners;
            List<String> notes = new ArrayList<>();
            Confidence conf = Confidence.HIGH;
            if (parents.size() > 1) {
                conf = Confidence.MEDIUM;
                notes.add("Ambiguous: " + parents.size() + " tables have primary key " + n + "; the data check shows which one holds the values.");
            }
            return typed(new Candidate(t.name(), n, col.displayType(), Kind.LIKELY_FK, conf, parents, n,
                    "same name as the primary key of " + String.join(", ", parents), notes, indexed, List.of()), col, tables, pkOf);
        }
        if (ownPk) return null;

        // 2. <table><suffix> naming an existing table
        for (String suffix : ID_SUFFIXES) {
            if (!n.endsWith(suffix) || n.length() <= suffix.length()) continue;
            String stem = trimUnderscore(n.substring(0, n.length() - suffix.length()));
            if (stem.isEmpty()) continue;
            for (String[] form : new String[][] {{stem, "exact"}, {stem + "S", "plural"}, {stem + "ES", "plural"}}) {
                TableDef p = tables.get(form[0]);
                if (p == null) continue;
                String pk = pkOf.get(p.name());
                if (pk == null) {
                    return new Candidate(t.name(), n, col.displayType(), Kind.LIKELY_FK, Confidence.LOW, List.of(p.name()), null,
                            "name points at table " + p.name(), List.of("Table " + p.name() + " has no single-column primary key, so the referenced column is unclear."),
                            indexed, List.of());
                }
                Confidence conf = form[1].equals("exact") ? Confidence.HIGH : Confidence.MEDIUM;
                List<String> notes = new ArrayList<>();
                if (p.name().equals(t.name())) notes.add("Self-reference: rows of this table point at other rows of the same table.");
                return typed(new Candidate(t.name(), n, col.displayType(), Kind.LIKELY_FK, conf, List.of(p.name()), pk,
                        "name is " + (form[1].equals("exact") ? "" : "a plural of ") + "table " + p.name() + " + " + suffix, notes, indexed, List.of()),
                        col, tables, pkOf);
            }
            break;   // only the longest matching suffix is tried
        }

        // 3. role-prefixed: PARENT_TRADE_ID, ORIG_TRADE_ID ...
        String bestPk = null;
        for (String pk : tablesByPk.keySet()) {
            if (n.endsWith("_" + pk) && (bestPk == null || pk.length() > bestPk.length())) bestPk = pk;
        }
        if (bestPk != null) {
            List<String> parents = tablesByPk.get(bestPk);
            String root = parents.size() > 1 ? root(stem(bestPk), parents, null, tables) : null;
            List<String> chosen = root != null ? List.of(root) : parents;
            List<String> notes = new ArrayList<>();
            if (chosen.size() > 1) notes.add("Ambiguous: " + chosen.size() + " tables have primary key " + bestPk + ".");
            if (chosen.contains(t.name())) notes.add("Self-reference: rows of this table point at other rows of the same table.");
            return typed(new Candidate(t.name(), n, col.displayType(), Kind.LIKELY_FK, Confidence.MEDIUM, chosen, bestPk,
                    "ends with primary-key name " + bestPk + " (role prefix " + n.substring(0, n.length() - bestPk.length() - 1) + ")",
                    notes, indexed, List.of()), col, tables, pkOf);
        }
        for (String suffix : ID_SUFFIXES) {
            if (!n.endsWith(suffix) || suffix.equals("ID")) continue;
            String rest = n.substring(0, n.length() - suffix.length());
            String best = null;
            for (String tn : tables.keySet()) {
                if (rest.endsWith("_" + tn) && pkOf.containsKey(tn) && (best == null || tn.length() > best.length())) best = tn;
            }
            if (best != null) {
                List<String> notes = new ArrayList<>();
                if (best.equals(t.name())) notes.add("Self-reference: rows of this table point at other rows of the same table.");
                return typed(new Candidate(t.name(), n, col.displayType(), Kind.LIKELY_FK, Confidence.MEDIUM, List.of(best), pkOf.get(best),
                        "ends with table " + best + " + " + suffix + " (role prefix " + rest.substring(0, rest.length() - best.length() - 1) + ")",
                        notes, indexed, List.of()), col, tables, pkOf);
            }
            break;
        }

        // 4. type + id pair
        for (String suffix : List.of("_ID", "_NB", "_REF", "ID")) {
            if (!n.endsWith(suffix) || n.length() <= suffix.length()) continue;
            String prefix = trimUnderscore(n.substring(0, n.length() - suffix.length()));
            for (String ts : TYPE_SUFFIXES) {
                String partner = prefix + ts;
                if (t.columns().containsKey(partner)) {
                    return new Candidate(t.name(), n, col.displayType(), Kind.POLYMORPHIC, Confidence.MEDIUM, List.of(), null,
                            "ID column paired with type column " + partner,
                            List.of("Which table this points at depends on " + partner + " in each row. It cannot be a foreign key, and a merge that re-keys rows must rewrite it per type."),
                            indexed, List.of());
                }
            }
            break;
        }

        // 5. looks like an identifier, nothing matches. A column that is unique on its own (EXTERNAL_REF with a UNIQUE
        // constraint) is a natural key of its own table, not a reference to another one.
        boolean ownKey = t.constraints().stream().anyMatch(c -> ("U".equals(c.type()) || "P".equals(c.type()))
                && c.columns().size() == 1 && c.columns().get(0).equals(n));
        for (String suffix : ownKey ? List.<String>of() : UNRESOLVED_SUFFIXES) {
            if (n.endsWith(suffix) && n.length() > suffix.length()) {
                return new Candidate(t.name(), n, col.displayType(), Kind.UNRESOLVED_ID, Confidence.LOW, List.of(), null,
                        "named like an identifier (" + suffix + ") but no table matches",
                        List.of("May reference another schema, an external system, or a table whose name differs. Check before merging."),
                        indexed, List.of());
            }
        }
        return null;
    }

    /** Lowers confidence one step and says why when the child and parent key types are from different families. */
    private static Candidate typed(Candidate c, ColumnDef col, Map<String, TableDef> tables, Map<String, String> pkOf) {
        Set<String> mismatches = new LinkedHashSet<>();
        for (String p : c.parentTables()) {
            TableDef pt = tables.get(p);
            String pkName = pkOf.get(p);
            ColumnDef pk = pt == null || pkName == null ? null : pt.columns().get(pkName);
            if (pk != null && !family(pk).equals(family(col))) mismatches.add(p + "." + pk.name() + " is " + pk.displayType());
        }
        if (mismatches.isEmpty()) return c;
        List<String> notes = new ArrayList<>(c.notes());
        notes.add("Type differs: " + col.displayType() + " here, but " + String.join("; ", mismatches) + ".");
        Confidence lower = c.confidence() == Confidence.HIGH ? Confidence.MEDIUM : Confidence.LOW;
        return new Candidate(c.table(), c.column(), c.columnType(), c.kind(), lower, c.parentTables(), c.parentColumn(), c.rule(),
                notes, c.indexed(), c.dataChecks());
    }

    /** The table the stem names (exact or plural), if it is among the candidates (or is the current table). */
    private static String root(String stem, List<String> owners, String current, Map<String, TableDef> tables) {
        for (String form : List.of(stem, stem + "S", stem + "ES")) {
            if (owners.contains(form) || form.equals(current)) return form;
        }
        return null;
    }

    static String stem(String column) {
        for (String suffix : ID_SUFFIXES) {
            if (column.endsWith(suffix) && column.length() > suffix.length()) {
                return trimUnderscore(column.substring(0, column.length() - suffix.length()));
            }
        }
        return column;
    }

    private static String trimUnderscore(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '_') end--;
        return s.substring(0, end);
    }

    /** Whole numbers, short strings and short RAW (GUIDs) can hold keys; dates, LOBs and fractional numbers cannot. */
    static boolean keyType(ColumnDef c) {
        String b = c.baseType();
        if (b.equals("NUMBER")) return c.scale() == null || c.scale() == 0;
        if (b.equals("INTEGER")) return true;
        if (c.isCharType()) return c.length() == null || c.length() <= 128;
        if (b.equals("RAW")) return c.length() != null && c.length() <= 32;
        return false;
    }

    private static String family(ColumnDef c) {
        String b = c.baseType();
        if (b.equals("NUMBER") || b.equals("INTEGER") || b.equals("FLOAT")) return "number";
        if (c.isCharType()) return "text";
        return b;
    }
}
