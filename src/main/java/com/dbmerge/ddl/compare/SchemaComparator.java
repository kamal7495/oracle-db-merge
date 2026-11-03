package com.dbmerge.ddl.compare;

import com.dbmerge.ddl.model.ColumnDef;
import com.dbmerge.ddl.model.ConstraintDef;
import com.dbmerge.ddl.model.IndexDef;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.SequenceDef;
import com.dbmerge.ddl.model.TableDef;
import com.dbmerge.ddl.model.TriggerDef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compares N schema models with each other (not just against a reference). For every object the finding
 * carries one value per database, so any pair can be read off the same row.
 *
 * Deliberately ignored: column order, storage/tablespace attributes, sequence LAST_NUMBER, system-generated
 * constraint/index names, and NOT NULL check constraints (covered by column nullability).
 */
public class SchemaComparator {

    public static final String MISSING = "(missing)";
    public static final String NO_TABLE = "(table missing)";

    public ComparisonResult compare(List<SchemaModel> models) {
        if (models == null || models.size() < 2) {
            throw new IllegalArgumentException("At least two databases are needed to compare.");
        }
        List<String> dbs = models.stream().map(SchemaModel::nickname).toList();
        if (new TreeSet<>(dbs).size() != dbs.size()) {
            throw new IllegalArgumentException("Database nicknames must be unique.");
        }
        List<Difference> diffs = new ArrayList<>();

        Set<String> allTables = new TreeSet<>();
        for (SchemaModel m : models) allTables.addAll(m.tables().keySet());

        for (String table : allTables) {
            Map<String, TableDef> present = new LinkedHashMap<>();
            for (SchemaModel m : models) {
                TableDef t = m.tables().get(table);
                if (t != null) present.put(m.nickname(), t);
            }
            if (present.size() < dbs.size()) {
                Map<String, String> v = valuesFor(dbs, present, t -> t.columns().size() + " columns");
                diffs.add(new Difference(DiffKind.MISSING_TABLE, Severity.HIGH, table, table, v,
                        "Table exists in " + names(present.keySet()) + " but not in "
                                + names(missingFrom(dbs, present.keySet())) + "."));
            }
            if (present.size() >= 2) {
                compareColumns(table, dbs, present, diffs);
                compareConstraints(table, dbs, present, diffs);
                compareIndexes(table, dbs, present, diffs);
                compareTriggers(table, dbs, present, diffs);
            }
        }
        compareSequences(dbs, models, diffs);

        diffs.sort(Comparator
                .comparing((Difference d) -> d.severity().ordinal())
                .thenComparing(d -> d.table() == null ? "" : d.table())
                .thenComparing(d -> d.kind().ordinal())
                .thenComparing(Difference::object));

        return new ComparisonResult(UUID.randomUUID().toString().substring(0, 8), Instant.now(),
                dbs, List.copyOf(models), List.copyOf(diffs));
    }

    // ---------------------------------------------------------------- columns

    private void compareColumns(String table, List<String> dbs, Map<String, TableDef> present, List<Difference> out) {
        Set<String> names = new LinkedHashSet<>();
        for (TableDef t : present.values()) names.addAll(t.columns().keySet());

        for (String col : names) {
            Map<String, ColumnDef> has = new LinkedHashMap<>();
            for (var e : present.entrySet()) {
                ColumnDef c = e.getValue().columns().get(col);
                if (c != null) has.put(e.getKey(), c);
            }
            String object = table + "." + col;

            if (has.size() < present.size()) {
                boolean hardToMap = has.values().stream().anyMatch(c -> !c.nullable() && c.defaultValue() == null && !c.identity());
                Severity sev = hardToMap ? Severity.HIGH : Severity.MEDIUM;
                out.add(new Difference(DiffKind.MISSING_COLUMN, sev, table, object,
                        columnValues(dbs, present, has),
                        "Column exists in " + names(has.keySet()) + " but not in "
                                + names(missingFrom(new ArrayList<>(present.keySet()), has.keySet())) + "."
                                + (hardToMap ? " It is NOT NULL without a default, so rows from the other database(s) cannot be loaded without a value." : "")));
            }
            if (has.size() < 2) continue;

            Set<String> baseTypes = has.values().stream().map(ColumnDef::baseType).collect(Collectors.toCollection(TreeSet::new));
            if (baseTypes.size() > 1) {
                out.add(new Difference(DiffKind.TYPE_MISMATCH, typeSeverity(baseTypes), table, object,
                        columnValues(dbs, present, has),
                        "Data types differ: " + String.join(" vs ", baseTypes) + "."));
                continue; // sizes are meaningless once the type itself differs
            }
            Set<String> sizes = has.values().stream().map(ColumnDef::sizeSpec).collect(Collectors.toCollection(TreeSet::new));
            if (sizes.size() > 1) {
                Severity sev = onlyCharSemanticsDiffer(has.values()) ? Severity.MEDIUM : Severity.HIGH;
                out.add(new Difference(DiffKind.LENGTH_MISMATCH, sev, table, object,
                        columnValues(dbs, present, has),
                        "Length/precision/scale differs. Loading into the narrower definition can truncate or fail."));
            }
            if (has.values().stream().map(ColumnDef::nullable).distinct().count() > 1) {
                out.add(new Difference(DiffKind.NULLABLE_MISMATCH, Severity.MEDIUM, table, object,
                        columnValues(dbs, present, has),
                        "NULL / NOT NULL differs. NULLs from a nullable source will be rejected by a NOT NULL master."));
            }
            if (has.values().stream().map(ColumnDef::defaultDisplay).distinct().count() > 1) {
                out.add(new Difference(DiffKind.DEFAULT_MISMATCH, Severity.LOW, table, object,
                        columnValues(dbs, present, has), "Default value / identity differs."));
            }
        }
    }

    private static Map<String, String> columnValues(List<String> dbs, Map<String, TableDef> present, Map<String, ColumnDef> has) {
        Map<String, String> v = new LinkedHashMap<>();
        for (String db : dbs) {
            if (!present.containsKey(db)) v.put(db, NO_TABLE);
            else v.put(db, has.containsKey(db) ? has.get(db).summary() : MISSING);
        }
        return v;
    }

    private static boolean onlyCharSemanticsDiffer(Iterable<ColumnDef> cols) {
        Set<Integer> lengths = new TreeSet<>();
        for (ColumnDef c : cols) {
            if (!c.isCharType()) return false; // precision/scale/fraction differences are never "just semantics"
            lengths.add(c.length() == null ? -1 : c.length());
        }
        return lengths.size() == 1;
    }

    private static Severity typeSeverity(Set<String> baseTypes) {
        return baseTypes.stream().map(SchemaComparator::family).distinct().count() > 1 ? Severity.CRITICAL : Severity.MEDIUM;
    }

    static String family(String base) {
        if (base.equals("DATE") || base.startsWith("TIMESTAMP")) return "DATETIME";
        return switch (base) {
            case "NUMBER", "FLOAT", "BINARY_FLOAT", "BINARY_DOUBLE", "INTEGER", "INT", "SMALLINT" -> "NUMERIC";
            case "VARCHAR2", "NVARCHAR2", "CHAR", "NCHAR", "VARCHAR", "CLOB", "NCLOB", "LONG" -> "TEXT";
            case "RAW", "BLOB", "LONG RAW" -> "BINARY";
            default -> base;
        };
    }

    // ------------------------------------------------------------ constraints

    private void compareConstraints(String table, List<String> dbs, Map<String, TableDef> present, List<Difference> out) {
        // Step 1: same name (or "the" primary key) in 2+ databases but with a different definition.
        Map<String, Map<String, ConstraintDef>> byName = new LinkedHashMap<>();
        for (var e : present.entrySet()) {
            for (ConstraintDef c : e.getValue().constraints()) {
                String key = c.type().equals("P") ? "P:PRIMARY KEY" : (c.generatedName() ? null : c.type() + ":" + c.name());
                if (key != null) byName.computeIfAbsent(key, k -> new LinkedHashMap<>()).put(e.getKey(), c);
            }
        }
        Map<ConstraintDef, Boolean> consumed = new IdentityHashMap<>();
        for (var g : byName.entrySet()) {
            Map<String, ConstraintDef> has = g.getValue();
            if (has.size() < 2) continue;
            if (has.values().stream().map(ConstraintDef::signature).distinct().count() > 1) {
                ConstraintDef any = has.values().iterator().next();
                Map<String, String> v = new LinkedHashMap<>();
                for (String db : dbs) {
                    v.put(db, !present.containsKey(db) ? NO_TABLE : has.containsKey(db) ? has.get(db).signature() : MISSING);
                }
                String obj = any.type().equals("P") ? table + " PRIMARY KEY" : table + "." + any.name();
                out.add(new Difference(DiffKind.CONSTRAINT_DEFINITION_MISMATCH, constraintSeverity(any.type()), table, obj, v,
                        any.label() + " is defined differently across databases."
                                + (any.type().equals("P") ? " The business key cannot be assumed to be the same." : "")));
                has.values().forEach(c -> consumed.put(c, Boolean.TRUE));
            }
        }

        // Step 2: everything else, matched by structural signature.
        Map<String, Map<String, ConstraintDef>> bySig = new LinkedHashMap<>();
        for (var e : present.entrySet()) {
            for (ConstraintDef c : e.getValue().constraints()) {
                if (consumed.containsKey(c) || isNotNullCheck(c)) continue;
                bySig.computeIfAbsent(c.signature(), k -> new LinkedHashMap<>()).put(e.getKey(), c);
            }
        }
        for (var g : bySig.entrySet()) {
            Map<String, ConstraintDef> has = g.getValue();
            ConstraintDef any = has.values().iterator().next();
            String sig = g.getKey();
            if (has.size() < present.size()) {
                Map<String, String> v = new LinkedHashMap<>();
                for (String db : dbs) {
                    v.put(db, !present.containsKey(db) ? NO_TABLE : has.containsKey(db) ? describe(has.get(db)) : MISSING);
                }
                out.add(new Difference(DiffKind.MISSING_CONSTRAINT, constraintSeverity(any.type()), table, table + ": " + sig, v,
                        any.label() + " exists in " + names(has.keySet()) + " but not in "
                                + names(missingFrom(new ArrayList<>(present.keySet()), has.keySet())) + "."));
                continue;
            }
            boolean allGenerated = has.values().stream().allMatch(ConstraintDef::generatedName);
            if (!allGenerated && has.values().stream().map(ConstraintDef::name).distinct().count() > 1) {
                out.add(new Difference(DiffKind.CONSTRAINT_NAME_MISMATCH, Severity.LOW, table, table + ": " + sig,
                        valuesFor(dbs, present, t -> null, has, ConstraintDef::name),
                        "Same constraint, different names."));
            }
            if (has.values().stream().map(ConstraintDef::status).distinct().count() > 1) {
                Severity sev = any.type().equals("C") ? Severity.LOW : Severity.MEDIUM;
                out.add(new Difference(DiffKind.CONSTRAINT_STATUS_MISMATCH, sev, table, table + ": " + sig,
                        valuesFor(dbs, present, t -> null, has, ConstraintDef::status),
                        "Constraint is enabled in some databases and disabled in others."));
            }
        }
    }

    private static boolean isNotNullCheck(ConstraintDef c) {
        return c.type().equals("C") && c.condition() != null
                && c.condition().trim().matches("(?i)^\"?[A-Z0-9_$#]+\"?\\s+IS\\s+NOT\\s+NULL$");
    }

    private static Severity constraintSeverity(String type) {
        return switch (type) {
            case "P" -> Severity.CRITICAL;
            case "R", "U" -> Severity.HIGH;
            default -> Severity.MEDIUM;
        };
    }

    private static String describe(ConstraintDef c) {
        return (c.generatedName() ? "(system name)" : c.name()) + " [" + c.status() + "]";
    }

    // ---------------------------------------------------------------- indexes

    private void compareIndexes(String table, List<String> dbs, Map<String, TableDef> present, List<Difference> out) {
        Map<String, Map<String, IndexDef>> bySig = new LinkedHashMap<>();
        for (var e : present.entrySet()) {
            for (IndexDef i : e.getValue().indexes()) {
                bySig.computeIfAbsent(i.signature(), k -> new LinkedHashMap<>()).put(e.getKey(), i);
            }
        }
        for (var g : bySig.entrySet()) {
            Map<String, IndexDef> has = g.getValue();
            IndexDef any = has.values().iterator().next();
            if (has.size() < present.size()) {
                Map<String, String> v = new LinkedHashMap<>();
                for (String db : dbs) {
                    v.put(db, !present.containsKey(db) ? NO_TABLE : has.containsKey(db) ? has.get(db).name() : MISSING);
                }
                out.add(new Difference(DiffKind.MISSING_INDEX, any.unique() ? Severity.MEDIUM : Severity.LOW, table,
                        table + ": " + g.getKey(), v,
                        "Index exists in " + names(has.keySet()) + " but not in "
                                + names(missingFrom(new ArrayList<>(present.keySet()), has.keySet())) + "."));
            } else if (has.values().stream().noneMatch(IndexDef::generatedName)
                    && has.values().stream().map(IndexDef::name).distinct().count() > 1) {
                Map<String, String> v = new LinkedHashMap<>();
                for (String db : dbs) v.put(db, !present.containsKey(db) ? NO_TABLE : has.get(db).name());
                out.add(new Difference(DiffKind.INDEX_NAME_MISMATCH, Severity.LOW, table, table + ": " + g.getKey(), v,
                        "Same index, different names."));
            }
        }
    }

    // --------------------------------------------------------------- triggers

    private void compareTriggers(String table, List<String> dbs, Map<String, TableDef> present, List<Difference> out) {
        Map<String, Map<String, TriggerDef>> byName = new LinkedHashMap<>();
        for (var e : present.entrySet()) {
            for (TriggerDef t : e.getValue().triggers()) {
                byName.computeIfAbsent(t.name(), k -> new LinkedHashMap<>()).put(e.getKey(), t);
            }
        }
        for (var g : byName.entrySet()) {
            Map<String, TriggerDef> has = g.getValue();
            String obj = table + ": " + g.getKey();
            if (has.size() < present.size()) {
                Map<String, String> v = new LinkedHashMap<>();
                for (String db : dbs) {
                    v.put(db, !present.containsKey(db) ? NO_TABLE : has.containsKey(db)
                            ? has.get(db).summary() + " [" + has.get(db).status() + "]" : MISSING);
                }
                out.add(new Difference(DiffKind.MISSING_TRIGGER, Severity.MEDIUM, table, obj, v,
                        "Trigger exists in " + names(has.keySet()) + " but not in "
                                + names(missingFrom(new ArrayList<>(present.keySet()), has.keySet())) + "."
                                + " Triggers can change or reject rows during the merge load."));
                continue;
            }
            if (has.values().stream().map(TriggerDef::definitionHash).distinct().count() > 1) {
                out.add(new Difference(DiffKind.TRIGGER_DEFINITION_MISMATCH, Severity.MEDIUM, table, obj,
                        valuesFor(dbs, present, t -> null, has, TriggerDef::summary), "Trigger body, timing or event differs."));
            }
            if (has.values().stream().map(TriggerDef::status).distinct().count() > 1) {
                out.add(new Difference(DiffKind.TRIGGER_STATUS_MISMATCH, Severity.LOW, table, obj,
                        valuesFor(dbs, present, t -> null, has, TriggerDef::status), "Trigger is enabled in some databases and disabled in others."));
            }
        }
    }

    // -------------------------------------------------------------- sequences

    private void compareSequences(List<String> dbs, List<SchemaModel> models, List<Difference> out) {
        Set<String> all = new TreeSet<>();
        for (SchemaModel m : models) all.addAll(m.sequences().keySet());
        for (String name : all) {
            Map<String, SequenceDef> has = new LinkedHashMap<>();
            for (SchemaModel m : models) {
                SequenceDef s = m.sequences().get(name);
                if (s != null) has.put(m.nickname(), s);
            }
            if (has.size() < dbs.size()) {
                Map<String, String> v = new LinkedHashMap<>();
                for (String db : dbs) v.put(db, has.containsKey(db) ? has.get(db).params() : MISSING);
                out.add(new Difference(DiffKind.MISSING_SEQUENCE, Severity.MEDIUM, null, name, v,
                        "Sequence exists in " + names(has.keySet()) + " but not in "
                                + names(missingFrom(dbs, has.keySet())) + "."));
            }
            if (has.size() >= 2 && has.values().stream().map(SequenceDef::params).distinct().count() > 1) {
                boolean incrementDiffers = has.values().stream().map(SequenceDef::incrementBy).distinct().count() > 1;
                Map<String, String> v = new LinkedHashMap<>();
                for (String db : dbs) v.put(db, has.containsKey(db) ? has.get(db).params() : MISSING);
                out.add(new Difference(DiffKind.SEQUENCE_PARAM_MISMATCH, incrementDiffers ? Severity.MEDIUM : Severity.LOW,
                        null, name, v, "Sequence parameters differ (LAST_NUMBER is ignored)."));
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static <T> Map<String, String> valuesFor(List<String> dbs, Map<String, TableDef> present, Function<TableDef, String> fn) {
        Map<String, String> v = new LinkedHashMap<>();
        for (String db : dbs) {
            TableDef t = present.get(db);
            v.put(db, t == null ? MISSING : fn.apply(t));
        }
        return v;
    }

    private static <T> Map<String, String> valuesFor(List<String> dbs, Map<String, TableDef> present,
                                                     Function<TableDef, String> ignored,
                                                     Map<String, T> has, Function<T, String> fn) {
        Map<String, String> v = new LinkedHashMap<>();
        for (String db : dbs) {
            if (!present.containsKey(db)) v.put(db, NO_TABLE);
            else v.put(db, has.containsKey(db) ? fn.apply(has.get(db)) : MISSING);
        }
        return v;
    }

    private static List<String> missingFrom(List<String> all, Set<String> have) {
        return all.stream().filter(d -> !have.contains(d)).toList();
    }

    private static String names(Iterable<String> it) {
        List<String> l = new ArrayList<>();
        it.forEach(l::add);
        return String.join(", ", l);
    }
}
