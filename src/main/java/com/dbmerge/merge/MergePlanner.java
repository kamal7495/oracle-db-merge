package com.dbmerge.merge;

import com.dbmerge.ddl.model.ColumnDef;
import com.dbmerge.ddl.model.ConstraintDef;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.TableDef;
import com.dbmerge.ddl.model.TriggerDef;
import com.dbmerge.merge.MergeModel.AlterStatement;
import com.dbmerge.merge.MergeModel.ConflictAction;
import com.dbmerge.merge.MergeModel.FkRef;
import com.dbmerge.merge.MergeModel.Issue;
import com.dbmerge.merge.MergeModel.Level;
import com.dbmerge.merge.MergeModel.MergePlan;
import com.dbmerge.merge.MergeModel.OrphanPolicy;
import com.dbmerge.merge.MergeModel.Options;
import com.dbmerge.merge.MergeModel.PkMode;
import com.dbmerge.merge.MergeModel.PlannedRow;
import com.dbmerge.merge.MergeModel.RowAction;
import com.dbmerge.merge.MergeModel.TableStep;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the merge plan entirely from read-only data: nothing here can write to any database. Tables are planned in
 * foreign-key order (parents first). Source primary keys that collide with existing master keys are re-keyed, every
 * source key is recorded in a mapping, and child foreign keys are rewritten through that mapping. Rows that match an
 * existing master row on the business key are skipped if identical or resolved through the remembered conflict rules.
 */
public class MergePlanner {

    /** Typed row access. Implemented over JDBC in production and over in-memory lists in tests. */
    public interface MergeData {
        List<Map<String, Object>> rows(String db, String table, List<String> columns) throws SQLException;
    }

    private static final Pattern NEXTVAL = Pattern.compile("(?i)(?:\"?[A-Z0-9_$#]+\"?\\.)?\"?([A-Z0-9_$#]+)\"?\\s*\\.\\s*NEXTVAL");
    private static final int EXAMPLES = 5;

    private final MergeData data;

    public MergePlanner(MergeData data) {
        this.data = data;
    }

    private static final class VRow {
        final Map<String, Object> values;     // comparison values in master space
        final Map<String, Object> pk;         // pk column -> value; empty = not known until run time
        final int stepRowIndex;               // index into the step's rows when inserted by this run, else -1
        VRow(Map<String, Object> values, Map<String, Object> pk, int stepRowIndex) {
            this.values = values;
            this.pk = pk;
            this.stepRowIndex = stepRowIndex;
        }
    }

    public MergePlan plan(String master, List<String> sources, Map<String, SchemaModel> models,
                          Map<String, List<String>> keyOverrides, Rules rules, Options options) throws SQLException {
        if (sources.contains(master)) throw new IllegalArgumentException("The master cannot also be a source.");
        if (sources.isEmpty()) throw new IllegalArgumentException("Select at least one source database.");
        SchemaModel mm = models.get(master);
        if (mm == null) throw new IllegalArgumentException("Unknown master: " + master);
        List<SchemaModel> sms = new ArrayList<>();
        for (String s : sources) {
            SchemaModel m = models.get(s);
            if (m == null) throw new IllegalArgumentException("Unknown source: " + s);
            sms.add(m);
        }
        if (rules == null) rules = new Rules();
        if (options == null) options = Options.defaults();
        if (keyOverrides == null) keyOverrides = Map.of();

        List<Issue> issues = new ArrayList<>();
        AlterScriptGenerator.Result alterRes = AlterScriptGenerator.generate(mm, sms);
        Map<String, TableDef> eff = alterRes.effective();

        // Tables to merge: present in at least one source, plus their parents (needed to validate foreign keys).
        Set<String> mergeSet = new LinkedHashSet<>();
        for (SchemaModel s : sms) {
            for (String t : s.tables().keySet()) if (eff.containsKey(t) && !t.equals(AlterScriptGenerator.AUDIT_TABLE)) mergeSet.add(t);
        }
        List<String> frontier = new ArrayList<>(mergeSet);
        while (!frontier.isEmpty()) {
            String t = frontier.remove(frontier.size() - 1);
            for (ConstraintDef c : eff.get(t).constraints()) {
                if (c.type().equals("R") && eff.containsKey(c.refTable()) && mergeSet.add(c.refTable())) frontier.add(c.refTable());
            }
        }
        Map<String, TableDef> mergeTables = new TreeMap<>();
        for (String t : mergeSet) mergeTables.put(t, eff.get(t));

        FkGraph graph = FkGraph.build(mergeTables);
        Set<String> skipped = new LinkedHashSet<>();
        for (Set<String> cycle : graph.cycles()) {
            skipped.addAll(cycle);
            issues.add(new Issue(Level.BLOCKER, String.join(", ", cycle), "Foreign-key cycle between these tables. They cannot be merged automatically "
                    + "(parents must exist before children). Merge them manually or break the cycle first."));
        }

        Map<String, Map<String, Object>> idMap = new LinkedHashMap<>();
        Map<String, Set<String>> pkSets = new LinkedHashMap<>();
        Set<String> tablesWithSourceRows = new HashSet<>();
        List<TableStep> steps = new ArrayList<>();
        for (String table : graph.order()) {
            if (skipped.contains(table)) continue;
            TableStep step = planTable(table, eff, mergeTables, mm, sms, master, keyOverrides.get(table), rules, options,
                    idMap, pkSets, tablesWithSourceRows, issues);
            steps.add(step);
        }

        Map<String, List<String>> triggers = new TreeMap<>();
        if (options.disableTriggers()) {
            for (TableStep s : steps) {
                TableDef mt = mm.tables().get(s.table());
                if (mt == null) continue;
                List<String> names = mt.triggers().stream().filter(t -> "ENABLED".equalsIgnoreCase(t.status())).map(TriggerDef::name).toList();
                if (!names.isEmpty()) triggers.put(s.table(), names);
            }
        }
        List<AlterStatement> alters = alterRes.alters();
        String fingerprint = fingerprint(master, sources, options, steps, alters);
        return new MergePlan(UUID.randomUUID().toString().substring(0, 8), Instant.now(), master, List.copyOf(sources), options,
                steps, alters, eff, triggers, issues, fingerprint);
    }

    // ------------------------------------------------------------------ table

    private TableStep planTable(String table, Map<String, TableDef> eff, Map<String, TableDef> mergeTables, SchemaModel mm,
                                List<SchemaModel> sms, String master, List<String> keyOverride, Rules rules, Options options,
                                Map<String, Map<String, Object>> idMap, Map<String, Set<String>> pkSets,
                                Set<String> tablesWithSourceRows, List<Issue> issues) throws SQLException {
        TableDef def = eff.get(table);
        TableDef actualMaster = mm.tables().get(table);
        List<String> allCols = def.columns().values().stream().filter(c -> !c.virtual()).map(ColumnDef::name).toList();
        List<String> masterCols = actualMaster == null ? List.of()
                : allCols.stream().filter(c -> actualMaster.columns().containsKey(c)).toList();
        Set<String> newCols = new LinkedHashSet<>(allCols);
        newCols.removeAll(masterCols);
        if (actualMaster == null) newCols.clear();   // a new table has no existing rows to compare with

        List<String> pkCols = def.constraints().stream().filter(c -> c.type().equals("P")).map(ConstraintDef::columns).findFirst().orElse(List.of());
        List<String> key = keyOverride != null && !keyOverride.isEmpty() ? keyOverride : pkCols;

        List<SchemaModel> srcWith = new ArrayList<>();
        for (SchemaModel s : sms) if (s.tables().containsKey(table)) srcWith.add(s);

        List<PlannedRow> rows = new ArrayList<>();
        if (key.isEmpty()) {
            if (!srcWith.isEmpty()) issues.add(new Issue(Level.BLOCKER, table, "No primary key and no business key chosen: rows cannot be matched. Choose a business key."));
            return new TableStep(table, key, pkCols, allCols, PkMode.NONE, null, 0, rows);
        }

        // Existing master rows
        List<Map<String, Object>> masterRows = actualMaster == null ? List.of() : data.rows(master, table, masterCols);
        Set<String> used = new HashSet<>();
        Map<String, VRow> vm = new LinkedHashMap<>();
        BigDecimal maxPk = BigDecimal.ZERO;
        for (Map<String, Object> r : masterRows) {
            Map<String, Object> pk = pkMap(r, pkCols);
            if (!pkCols.isEmpty()) used.add(Canon.key(r, pkCols));
            maxPk = maxPk.max(numericPk(r, pkCols));
            vm.put(Canon.key(r, key), new VRow(new LinkedHashMap<>(r), pk, -1));
        }
        pkSets.put(table, new HashSet<>(used));

        // Primary-key strategy
        String sequence = null;
        PkMode pkMode;
        if (pkCols.isEmpty()) pkMode = PkMode.NONE;
        else if (pkCols.size() == 1) {
            sequence = findSequence(table, def, mm, options);
            ColumnDef pc = def.columns().get(pkCols.get(0));
            boolean numeric = pc != null && Set.of("NUMBER", "INTEGER", "FLOAT").contains(pc.baseType());
            pkMode = sequence != null ? PkMode.SEQUENCE : numeric ? PkMode.MAX_PLUS_ONE : PkMode.KEEP_ONLY;
        } else pkMode = PkMode.KEEP_ONLY;

        // Load every source up front so MAX+1 keys can be chosen above all existing keys.
        Map<String, List<Map<String, Object>>> srcRows = new LinkedHashMap<>();
        Map<String, List<String>> srcCols = new LinkedHashMap<>();
        for (SchemaModel s : srcWith) {
            TableDef st = s.tables().get(table);
            List<String> cols = allCols.stream().filter(c -> st.columns().containsKey(c)).toList();
            if (!cols.containsAll(key) || !cols.containsAll(pkCols)) {
                issues.add(new Issue(Level.BLOCKER, table, s.nickname() + " lacks key column(s) " + key + "; cannot match rows."));
                continue;
            }
            for (String c : allCols) {
                ColumnDef mc = def.columns().get(c);
                if (!cols.contains(c) && !mc.nullable() && mc.defaultValue() == null && !mc.identity()) {
                    issues.add(new Issue(Level.BLOCKER, table, "Column " + c + " is NOT NULL without default in the master but missing in " + s.nickname() + "."));
                }
            }
            List<Map<String, Object>> r = data.rows(s.nickname(), table, cols);
            srcRows.put(s.nickname(), r);
            srcCols.put(s.nickname(), cols);
            if (!r.isEmpty()) tablesWithSourceRows.add(table);
            for (Map<String, Object> row : r) maxPk = maxPk.max(numericPk(row, pkCols));
        }
        if (!newCols.isEmpty() && !srcRows.isEmpty()) {
            issues.add(new Issue(Level.INFO, table, "Columns " + newCols + " do not exist in the master yet; they are filled only for newly inserted rows."));
        }

        Map<String, Integer> violations = new LinkedHashMap<>();
        Map<String, String> violationExample = new LinkedHashMap<>();
        int orphanCount = 0;
        List<String> orphanExamples = new ArrayList<>();
        BigDecimal nextPk = maxPk;

        List<FkInfo> fks = foreignKeys(def, mergeTables);
        List<ConstraintDef> selfFks = def.constraints().stream().filter(c -> c.type().equals("R") && table.equals(c.refTable())).toList();

        for (var entry : srcRows.entrySet()) {
            String src = entry.getKey();
            List<String> cols = srcCols.get(src);
            List<Map<String, Object>> ordered = orderBySelfReference(entry.getValue(), selfFks, pkCols, table, issues);
            Set<String> sourcePkSet = new HashSet<>();
            for (Map<String, Object> r : entry.getValue()) sourcePkSet.add(Canon.key(r, pkCols));

            for (Map<String, Object> row : ordered) {
                String oldKey = Canon.key(row, pkCols.isEmpty() ? key : pkCols);
                String bkey = Canon.key(row, key);
                String bdisplay = Canon.display(bkey, key);
                Map<String, Object> vals = new LinkedHashMap<>();
                Map<String, Object> cmp = new LinkedHashMap<>();
                for (String c : cols) {
                    Object v = coerce(row.get(c), def.columns().get(c), table, issues);
                    vals.put(c, v);
                    cmp.put(c, v);
                }

                // Rewrite foreign keys through the key mapping of the (already planned) parents.
                boolean orphan = false;
                for (FkInfo fk : fks) {
                    if (!cols.containsAll(fk.columns)) continue;
                    List<Object> raw = new ArrayList<>();
                    for (String c : fk.columns) raw.add(row.get(c));
                    if (raw.stream().anyMatch(java.util.Objects::isNull)) continue;
                    if (!fk.pkReference) continue;                      // references non-PK columns: copied as is
                    Map<String, Object> asParentRow = new LinkedHashMap<>();
                    for (int i = 0; i < fk.columns.size(); i++) asParentRow.put(fk.refColumns.get(i), raw.get(i));
                    String oldParent = Canon.key(asParentRow, fk.parentPk);
                    String mk = src + "|" + fk.parent + "|" + oldParent;
                    Map<String, Object> target = idMap.get(mk);
                    if (target != null) {
                        for (int i = 0; i < fk.columns.size(); i++) {
                            vals.put(fk.columns.get(i), new FkRef(fk.parent, src, oldParent, fk.refColumns.get(i)));
                            Object concrete = target.get(fk.refColumns.get(i));
                            cmp.put(fk.columns.get(i), concrete != null ? concrete : "\u0000SYMBOLIC:" + mk);
                        }
                    } else if (!tablesWithSourceRowsFrom(idMap, src, fk.parent) && pkSets.getOrDefault(fk.parent, Set.of()).contains(oldParent)) {
                        // parent lives only in the master and the key exists there: reference stays valid
                    } else if (table.equals(fk.parent) && sourcePkSet.contains(oldParent)) {
                        // self reference to a row that could not be ordered earlier (reported by orderBySelfReference)
                    } else {
                        orphan = true;
                    }
                }
                if (orphan) {
                    orphanCount++;
                    if (orphanExamples.size() < EXAMPLES) orphanExamples.add(src + " " + bdisplay);
                    rows.add(new PlannedRow(src, RowAction.SKIP_ORPHAN, oldKey, bdisplay, vals, null, List.of(), "parent row not found"));
                    continue;
                }

                VRow ex = vm.get(bkey);
                if (ex == null) {
                    // ---- new row
                    boolean collision = !pkCols.isEmpty() && used.contains(Canon.key(row, pkCols));
                    RowAction action = RowAction.INSERT;
                    Map<String, Object> pk = pkMap(vals, pkCols);
                    String note = null;
                    if (collision) {
                        switch (pkMode) {
                            case SEQUENCE -> {
                                action = RowAction.INSERT_REMAPPED;
                                pk = Map.of();
                                vals.put(pkCols.get(0), null);
                                note = "primary key " + oldKey + " already used; new key from sequence " + sequence;
                            }
                            case MAX_PLUS_ONE -> {
                                nextPk = nextPk.add(BigDecimal.ONE);
                                action = RowAction.INSERT_REMAPPED;
                                Object nv = nextPk;
                                vals.put(pkCols.get(0), nv);
                                pk = new LinkedHashMap<>();
                                pk.put(pkCols.get(0), nv);
                                note = "primary key " + oldKey + " already used; new key " + nextPk.toPlainString() + " (no sequence found, max+1)";
                                used.add(Canon.key(vals, pkCols));
                            }
                            default -> {
                                issues.add(new Issue(Level.BLOCKER, table, "Primary key collision for " + bdisplay + " from " + src
                                        + " cannot be re-keyed automatically (composite or non-numeric key without a sequence)."));
                                rows.add(new PlannedRow(src, RowAction.UNRESOLVED_CONFLICT, oldKey, bdisplay, vals, null, List.of(), "key collision"));
                                continue;
                            }
                        }
                    } else if (!pkCols.isEmpty()) {
                        used.add(Canon.key(row, pkCols));
                    }
                    List<Object> masterPk = pk.isEmpty() ? null : new ArrayList<>(pk.values());
                    collectViolations(def, vals, table, violations, violationExample, src + " " + bdisplay);
                    rows.add(new PlannedRow(src, action, oldKey, bdisplay, vals, masterPk, List.of(), note));
                    idMap.put(src + "|" + table + "|" + oldKey, pk);
                    Map<String, Object> cmpWithPk = new LinkedHashMap<>(cmp);
                    cmpWithPk.putAll(pk);
                    vm.put(bkey, new VRow(cmpWithPk, pk, rows.size() - 1));
                    continue;
                }

                // ---- existing row (master or inserted earlier in this run)
                List<String> differing = new ArrayList<>();
                for (String c : cols) {
                    if (pkCols.contains(c) || newCols.contains(c)) continue;
                    if (!Canon.sameValue(cmp.get(c), ex.values.get(c))) differing.add(c);
                }
                String mapKey = src + "|" + table + "|" + oldKey;
                if (differing.isEmpty()) {
                    idMap.put(mapKey, ex.pk);
                    rows.add(new PlannedRow(src, RowAction.SKIP_DUPLICATE, oldKey, bdisplay, Map.of(), listOf(ex.pk), List.of(), "identical to existing row"));
                    continue;
                }
                Rules.RowDecision rd = rules.rowDecision(table, bdisplay);
                Rules.TableRule tr = rules.tableRule(table);
                ConflictAction action = rd != null ? rd.getAction() : tr != null ? tr.getAction() : null;
                if (action == null) {
                    rows.add(new PlannedRow(src, RowAction.UNRESOLVED_CONFLICT, oldKey, bdisplay, subset(vals, differing), listOf(ex.pk), differing,
                            "differs from existing row in " + differing + "; choose keep master / take source / newest / manual"));
                    idMap.put(mapKey, ex.pk);
                    continue;
                }
                Map<String, Object> change = new LinkedHashMap<>();
                String why = null;
                if (action == ConflictAction.NEWEST) {
                    String dc = tr == null ? null : tr.getDateColumn();
                    if (dc == null || !cols.contains(dc)) {
                        rows.add(new PlannedRow(src, RowAction.UNRESOLVED_CONFLICT, oldKey, bdisplay, subset(vals, differing), listOf(ex.pk), differing,
                                "'newest' needs a date column that exists in the table"));
                        idMap.put(mapKey, ex.pk);
                        continue;
                    }
                    action = newer(cmp.get(dc), ex.values.get(dc)) ? ConflictAction.TAKE_SOURCE : ConflictAction.KEEP_MASTER;
                    why = "newest by " + dc + ": " + (action == ConflictAction.TAKE_SOURCE ? src : "existing row") + " wins";
                }
                switch (action) {
                    case KEEP_MASTER -> {
                        rows.add(new PlannedRow(src, RowAction.SKIP_KEEP_MASTER, oldKey, bdisplay, Map.of(), listOf(ex.pk), differing,
                                why != null ? why : "conflict resolved: keep master"));
                        idMap.put(mapKey, ex.pk);
                        continue;
                    }
                    case TAKE_SOURCE -> { for (String c : differing) change.put(c, vals.get(c)); }
                    case MANUAL -> {
                        if (rd == null || rd.getManualValues().isEmpty()) {
                            rows.add(new PlannedRow(src, RowAction.UNRESOLVED_CONFLICT, oldKey, bdisplay, subset(vals, differing), listOf(ex.pk), differing,
                                    "manual decision without values"));
                            idMap.put(mapKey, ex.pk);
                            continue;
                        }
                        for (var m : rd.getManualValues().entrySet()) {
                            ColumnDef cd = def.columns().get(m.getKey());
                            if (cd == null) throw new IllegalArgumentException("Manual value for unknown column " + m.getKey() + " in " + table);
                            change.put(m.getKey(), Canon.convert(m.getValue(), cd));
                        }
                    }
                    default -> throw new IllegalStateException("unreachable");
                }
                idMap.put(mapKey, ex.pk);
                if (ex.stepRowIndex < 0 && pkCols.isEmpty()) {
                    rows.add(new PlannedRow(src, RowAction.UNRESOLVED_CONFLICT, oldKey, bdisplay, subset(vals, differing), null, differing,
                            "table has no primary key, so an existing row cannot be updated; choose keep master"));
                    continue;
                }
                if (ex.stepRowIndex >= 0) {
                    // The target row is being inserted by this run: patch that planned insert instead of updating.
                    PlannedRow planned = rows.get(ex.stepRowIndex);
                    Map<String, Object> patched = new LinkedHashMap<>(planned.values());
                    patched.putAll(change);
                    rows.set(ex.stepRowIndex, new PlannedRow(planned.sourceDb(), planned.action(), planned.oldKey(), planned.businessKey(),
                            patched, planned.masterPk(), planned.changedColumns(), (planned.note() == null ? "" : planned.note() + "; ")
                            + "values from " + src + " applied (" + (why != null ? why : "conflict rule") + ")"));
                    rows.add(new PlannedRow(src, RowAction.SKIP_DUPLICATE, oldKey, bdisplay, Map.of(), listOf(ex.pk), differing,
                            "merged into row inserted from " + planned.sourceDb()));
                } else {
                    collectViolations(def, change, table, violations, violationExample, src + " " + bdisplay);
                    rows.add(new PlannedRow(src, RowAction.UPDATE, oldKey, bdisplay, change, listOf(ex.pk), new ArrayList<>(change.keySet()),
                            why != null ? why : "conflict resolved by rule"));
                }
                for (var c : change.entrySet()) ex.values.put(c.getKey(), c.getValue() instanceof FkRef ? "\u0000SYMBOLIC" : c.getValue());
            }
        }

        if (orphanCount > 0) {
            boolean skip = options.orphanPolicy() == OrphanPolicy.SKIP;
            issues.add(new Issue(skip ? Level.WARNING : Level.BLOCKER, table, orphanCount + " row(s) reference a parent row that does not exist"
                    + " (e.g. " + String.join("; ", orphanExamples) + ")." + (skip ? " They will be skipped." : " Fix the source data or set the orphan policy to SKIP.")));
        }
        for (var v : violations.entrySet()) {
            issues.add(new Issue(Level.BLOCKER, table, v.getValue() + " row(s) " + v.getKey() + " (e.g. " + violationExample.get(v.getKey()) + ")."));
        }
        if (sequence != null && rows.stream().anyMatch(r -> r.action() == RowAction.INSERT)) {
            issues.add(new Issue(Level.WARNING, table, "Rows keep their source primary keys, so sequence " + sequence
                    + " may later hand out a key that is already used. Advance it beyond MAX of the key after the merge (sequences are not altered automatically)."));
        }
        return new TableStep(table, key, pkCols, allCols, pkMode, sequence, masterRows.size(), rows);
    }

    // ---------------------------------------------------------------- helpers

    private record FkInfo(String parent, List<String> columns, List<String> refColumns, List<String> parentPk, boolean pkReference) { }

    private static List<FkInfo> foreignKeys(TableDef def, Map<String, TableDef> mergeTables) {
        List<FkInfo> out = new ArrayList<>();
        for (ConstraintDef c : def.constraints()) {
            if (!c.type().equals("R") || c.refTable() == null) continue;
            TableDef parent = mergeTables.get(c.refTable());
            if (parent == null) continue;
            List<String> ppk = parent.constraints().stream().filter(x -> x.type().equals("P")).map(ConstraintDef::columns).findFirst().orElse(List.of());
            boolean pkRef = !ppk.isEmpty() && new HashSet<>(ppk).equals(new HashSet<>(c.refColumns()));
            out.add(new FkInfo(c.refTable(), c.columns(), c.refColumns(), ppk, pkRef));
        }
        return out;
    }

    private static boolean tablesWithSourceRowsFrom(Map<String, Map<String, Object>> idMap, String src, String parent) {
        String prefix = src + "|" + parent + "|";
        for (String k : idMap.keySet()) if (k.startsWith(prefix)) return true;
        return false;
    }

    private static List<Map<String, Object>> orderBySelfReference(List<Map<String, Object>> rows, List<ConstraintDef> selfFks,
                                                                   List<String> pkCols, String table, List<Issue> issues) {
        if (selfFks.isEmpty() || pkCols.isEmpty()) return rows;
        Set<String> allPk = new HashSet<>();
        for (Map<String, Object> r : rows) allPk.add(Canon.key(r, pkCols));
        List<Map<String, Object>> pending = new ArrayList<>(rows);
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        while (!pending.isEmpty()) {
            List<Map<String, Object>> next = new ArrayList<>();
            for (Map<String, Object> r : pending) {
                boolean ready = true;
                for (ConstraintDef c : selfFks) {
                    List<Object> raw = new ArrayList<>();
                    for (String col : c.columns()) raw.add(r.get(col));
                    if (raw.stream().anyMatch(java.util.Objects::isNull)) continue;
                    Map<String, Object> asParent = new LinkedHashMap<>();
                    for (int i = 0; i < c.columns().size(); i++) asParent.put(c.refColumns().get(i), raw.get(i));
                    String p = Canon.key(asParent, pkCols);
                    if (allPk.contains(p) && !done.contains(p) && !p.equals(Canon.key(r, pkCols))) ready = false;
                }
                if (ready) { out.add(r); done.add(Canon.key(r, pkCols)); } else next.add(r);
            }
            if (next.size() == pending.size()) {
                issues.add(new Issue(Level.BLOCKER, table, next.size() + " row(s) reference each other in a cycle through a self-referencing foreign key; they cannot be ordered."));
                out.addAll(next);
                break;
            }
            pending = next;
        }
        return out;
    }

    private static String findSequence(String table, TableDef def, SchemaModel mm, Options options) {
        String configured = options.pkSequences().get(table);
        if (configured != null) return configured;
        TableDef mt = mm.tables().get(table);
        if (mt == null) return null;
        for (TriggerDef t : mt.triggers()) {
            Matcher m = NEXTVAL.matcher(t.body() == null ? "" : t.body());
            while (m.find()) if (mm.sequences().containsKey(m.group(1).toUpperCase())) return m.group(1).toUpperCase();
        }
        for (ColumnDef c : mt.columns().values()) {
            if (c.defaultValue() == null) continue;
            Matcher m = NEXTVAL.matcher(c.defaultValue());
            if (m.find() && mm.sequences().containsKey(m.group(1).toUpperCase())) return m.group(1).toUpperCase();
        }
        return null;
    }

    private static Object coerce(Object v, ColumnDef col, String table, List<Issue> issues) {
        if (v == null || col == null) return v;
        String base = col.baseType();
        try {
            if ((base.equals("NUMBER") || base.equals("FLOAT")) && v instanceof String s) return new BigDecimal(s.trim());
            if (col.isCharType() && !(v instanceof String)) return Canon.of(v);
            if ((base.equals("DATE") || base.startsWith("TIMESTAMP")) && v instanceof String s) return Canon.convert(s, col);
        } catch (RuntimeException e) {
            issues.add(new Issue(Level.BLOCKER, table, "Value '" + v + "' cannot be converted to " + col.name() + " (" + col.displayType() + ") in the master."));
        }
        return v;
    }

    private static void collectViolations(TableDef def, Map<String, Object> vals, String table, Map<String, Integer> counts,
                                          Map<String, String> examples, String example) {
        for (var e : vals.entrySet()) {
            ColumnDef c = def.columns().get(e.getKey());
            if (c == null) continue;
            Object v = e.getValue();
            if (v == null && !c.nullable() && c.defaultValue() == null && !c.identity() && !isPkColumn(def, c.name())) {
                String k = "have NULL in NOT NULL column " + c.name();
                counts.merge(k, 1, Integer::sum);
                examples.putIfAbsent(k, example);
            } else if (v instanceof String s && c.isCharType() && c.length() != null && s.length() > c.length()) {
                String k = "have a value longer than " + c.length() + " characters for column " + c.name();
                counts.merge(k, 1, Integer::sum);
                examples.putIfAbsent(k, example);
            }
        }
    }

    private static boolean isPkColumn(TableDef def, String col) {
        return def.constraints().stream().anyMatch(c -> c.type().equals("P") && c.columns().contains(col));
    }

    private static boolean newer(Object source, Object existing) {
        if (source == null) return false;
        if (existing == null) return true;
        if (source instanceof Timestamp a && existing instanceof Timestamp b) return a.after(b);
        return Canon.of(source).compareTo(Canon.of(existing)) > 0;
    }

    private static Map<String, Object> pkMap(Map<String, Object> row, List<String> pkCols) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String c : pkCols) m.put(c, row.get(c));
        return m;
    }

    private static BigDecimal numericPk(Map<String, Object> row, List<String> pkCols) {
        if (pkCols.size() != 1) return BigDecimal.ZERO;
        Object v = row.get(pkCols.get(0));
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return BigDecimal.ZERO;
    }

    private static List<Object> listOf(Map<String, Object> pk) {
        return pk.isEmpty() ? null : new ArrayList<>(pk.values());
    }

    private static Map<String, Object> subset(Map<String, Object> vals, List<String> cols) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String c : cols) m.put(c, vals.get(c));
        return m;
    }

    static String fingerprint(String master, List<String> sources, Options options, List<TableStep> steps, List<AlterStatement> alters) {
        StringBuilder sb = new StringBuilder();
        sb.append(master).append('|').append(sources).append('|').append(options).append('\n');
        for (AlterStatement a : alters) sb.append(a.id()).append('|').append(a.sql()).append('\n');
        for (TableStep s : steps) {
            sb.append(s.table()).append('|').append(s.pkMode()).append('|').append(s.sequence()).append('|').append(s.masterRowsAtPlan()).append('\n');
            for (PlannedRow r : s.rows()) {
                sb.append(r.action()).append('|').append(r.sourceDb()).append('|').append(r.oldKey()).append('|').append(r.masterPk()).append('|');
                for (var e : r.values().entrySet()) {
                    sb.append(e.getKey()).append('=').append(e.getValue() instanceof FkRef f ? f.toString() : Canon.of(e.getValue())).append(';');
                }
                sb.append('\n');
            }
        }
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
