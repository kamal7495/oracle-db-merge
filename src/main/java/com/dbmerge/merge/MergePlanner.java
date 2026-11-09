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

        /** Number of rows; decides whether a table is planned in memory or streamed. */
        default long count(String db, String table) throws SQLException {
            return rows(db, table, List.of()).size();
        }

        /** Rows ordered by {@code key} as {@link KeyOrder} orders them (NULLs last), read one at a time. */
        default RowCursor sorted(String db, String table, List<String> columns, List<String> key, KeyOrder order) throws SQLException {
            List<Map<String, Object>> all = new ArrayList<>(rows(db, table, columns));
            all.sort((a, b) -> order.compare(order.keyOf(a), order.keyOf(b)));
            java.util.Iterator<Map<String, Object>> it = all.iterator();
            return new RowCursor() {
                @Override public Map<String, Object> next() { return it.hasNext() ? it.next() : null; }
                @Override public void close() { }
            };
        }
    }

    /** A forward-only stream of rows; {@link #next()} returns null at the end. */
    public interface RowCursor extends AutoCloseable {
        Map<String, Object> next() throws SQLException;

        @Override
        void close() throws SQLException;
    }

    private static final Pattern NEXTVAL = Pattern.compile("(?i)(?:\"?[A-Z0-9_$#]+\"?\\.)?\"?([A-Z0-9_$#]+)\"?\\s*\\.\\s*NEXTVAL");
    private static final int EXAMPLES = 5;

    private final MergeData data;
    private final long streamAbove;
    private final java.nio.file.Path spoolDir;

    /** Plans every table in memory. */
    public MergePlanner(MergeData data) {
        this(data, Long.MAX_VALUE, null);
    }

    /**
     * @param streamAbove tables with more rows than this (in the master or any source) are streamed in key order and
     *                    their plan kept in a file under {@code spoolDir}; smaller ones are planned in memory
     */
    public MergePlanner(MergeData data, long streamAbove, java.nio.file.Path spoolDir) {
        this.data = data;
        this.streamAbove = streamAbove;
        this.spoolDir = spoolDir != null ? spoolDir : java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "dbmerge-plans");
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

        KeyMap idMap = new KeyMap(eff);
        Map<String, KeySet> pkSets = new LinkedHashMap<>();
        Set<String> referenced = referencedTables(mergeTables);
        Set<String> tablesWithSourceRows = new HashSet<>();
        List<TableStep> steps = new ArrayList<>();
        for (String table : graph.order()) {
            if (skipped.contains(table)) continue;
            TableStep step = planTable(table, eff, mergeTables, mm, sms, master, keyOverrides.get(table), rules, options,
                    idMap, pkSets, tablesWithSourceRows, issues, referenced.contains(table));
            steps.add(step);
        }
        // Needs the finished plan: it compares copied child values with the parents' changed keys.
        UndeclaredReferenceGuard.check(eff, steps, options.acknowledgedReferences(), issues);

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
                                KeyMap idMap, Map<String, KeySet> pkSets,
                                Set<String> tablesWithSourceRows, List<Issue> issues, boolean referenced) throws SQLException {
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

        if (streamAbove != Long.MAX_VALUE) {
            long biggest = actualMaster == null ? 0 : data.count(master, table);
            for (SchemaModel s : srcWith) biggest = Math.max(biggest, data.count(s.nickname(), table));
            if (biggest > streamAbove) {
                return planLargeTable(table, def, actualMaster, allCols, masterCols, newCols, pkCols, key, srcWith, mm, master,
                        rules, options, idMap, pkSets, tablesWithSourceRows, mergeTables, issues, biggest, referenced);
            }
        }

        // Existing master rows
        List<Map<String, Object>> masterRows = actualMaster == null ? List.of() : data.rows(master, table, masterCols);
        KeySet used = new KeySet();
        Map<String, VRow> vm = new LinkedHashMap<>();
        BigDecimal maxPk = BigDecimal.ZERO;
        for (Map<String, Object> r : masterRows) {
            Map<String, Object> pk = pkMap(r, pkCols);
            if (!pkCols.isEmpty()) used.add(Canon.key(r, pkCols));
            maxPk = maxPk.max(numericPk(r, pkCols));
            vm.put(Canon.key(r, key), new VRow(new LinkedHashMap<>(r), pk, -1));
        }
        pkSets.put(table, used.copy());

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
        // Business-key order, as the streaming path reads them: re-keyed rows get MAX+1 numbers in a fixed order
        // (databases return rows in no particular order), and both paths number them alike.
        KeyOrder order = new KeyOrder(key, key.stream().map(c -> def.columns().get(c)).toList());
        for (var e : srcRows.entrySet()) {
            List<Map<String, Object>> sorted = new ArrayList<>(e.getValue());
            sorted.sort((a, b) -> order.compare(order.keyOf(a), order.keyOf(b)));
            e.setValue(sorted);
        }

        Ctx x = new Ctx(table, def, key, pkCols, newCols, pkMode, sequence, rules, options, idMap, pkSets, issues);
        x.referenced = referenced;
        x.used = keysOf(used);
        x.nextPk = maxPk;
        x.fks = foreignKeys(def, mergeTables);
        List<ConstraintDef> selfFks = def.constraints().stream().filter(c -> c.type().equals("R") && table.equals(c.refTable())).toList();
        x.rows = new ListSink(rows);
        x.vmGet = vm::get;
        x.vmPut = vm::put;

        for (var entry : srcRows.entrySet()) {
            String src = entry.getKey();
            List<String> cols = srcCols.get(src);
            List<Map<String, Object>> ordered = orderBySelfReference(entry.getValue(), selfFks, pkCols, table, issues);
            Set<String> sourcePkSet = new HashSet<>();
            for (Map<String, Object> r : entry.getValue()) sourcePkSet.add(Canon.key(r, pkCols));
            x.sourcePkSet = (s, k) -> sourcePkSet.contains(k);
            Set<String> decided = new HashSet<>();
            x.decided = (s, k) -> decided.contains(k);
            for (Map<String, Object> row : ordered) {
                processRow(x, src, cols, row);
                decided.add(Canon.key(row, pkCols.isEmpty() ? key : pkCols));
            }
        }

        finishTable(x);
        return new TableStep(table, key, pkCols, allCols, pkMode, sequence, masterRows.size(), rows);
    }

    // ------------------------------------------------------------ large tables

    /** Thrown when a stream is not in the order {@link KeyOrder} expects: the plan stops rather than pair wrong rows. */
    private static final class OutOfOrder extends RuntimeException {
        OutOfOrder(String message) { super(message, null, false, false); }
    }

    /** One table's rows from one database, read in key order, with the order checked as it goes. */
    private static final class Stream {
        final String db;
        final RowCursor cursor;
        final KeyOrder order;
        final String table;
        Map<String, Object> row;
        List<Object> key;

        Stream(String db, RowCursor cursor, KeyOrder order, String table) throws SQLException {
            this.db = db;
            this.cursor = cursor;
            this.order = order;
            this.table = table;
            advance();
        }

        void advance() throws SQLException {
            List<Object> previous = key;
            row = cursor.next();
            key = row == null ? null : order.keyOf(row);
            if (key != null && previous != null && order.compare(previous, key) > 0) {
                throw new OutOfOrder(db + " returned " + table + " rows out of business-key order (" + previous + " before " + key
                        + "): Oracle's ordering of the key columns does not match the planner's.");
            }
        }
    }

    /** Buffers one key group in front of the plan file, so a later source can still patch a row an earlier one inserted. */
    private static final class GroupSink implements Sink {
        final java.util.function.Consumer<PlannedRow> out;
        final List<PlannedRow> buffer = new ArrayList<>();
        int base;
        RowAction last; // action of the row added last: what became of the source row just decided

        GroupSink(java.util.function.Consumer<PlannedRow> out) { this.out = out; }

        @Override public void add(PlannedRow r) { buffer.add(r); last = r.action(); }
        @Override public PlannedRow get(int i) { return buffer.get(local(i)); }
        @Override public void set(int i, PlannedRow r) { buffer.set(local(i), r); }
        @Override public int size() { return base + buffer.size(); }

        private int local(int i) {
            if (i < base) throw new IllegalStateException("row " + i + " was already written to the plan file");
            return i - base;
        }

        void flush() {
            for (PlannedRow r : buffer) out.accept(r);
            base += buffer.size();
            buffer.clear();
        }
    }

    /**
     * Plans a table too large for memory. The master and every source are read in business-key order at the same time
     * and walked side by side; each key's rows (master row, then each source's rows in source order) go through the same
     * {@link #processRow} as the in-memory path. Memory holds one key group, the primary keys in use ({@link KeySet})
     * and the keys that change ({@link KeyMap}); the planned rows go to a {@link SpooledRows} file.
     *
     * <p>Differences from the in-memory path: tables with a self-referencing foreign key are not supported (they need
     * parents ordered before children within the table), the key columns must have the same type in every database
     * (so all of them sort alike), and when two sources bring different new rows with the same primary key, which one
     * keeps it follows business-key order rather than source order.
     */
    private TableStep planLargeTable(String table, TableDef def, TableDef actualMaster, List<String> allCols, List<String> masterCols,
                                     Set<String> newCols, List<String> pkCols, List<String> key, List<SchemaModel> srcWith, SchemaModel mm,
                                     String master, Rules rules, Options options, KeyMap idMap, Map<String, KeySet> pkSets,
                                     Set<String> tablesWithSourceRows, Map<String, TableDef> mergeTables, List<Issue> issues,
                                     long biggest, boolean referenced) throws SQLException {
        TableStep none = new TableStep(table, key, pkCols, allCols, PkMode.NONE, null, 0, List.of());
        List<ColumnDef> keyTypes = new ArrayList<>();
        for (String c : key) keyTypes.add(def.columns().get(c));
        List<SchemaModel> withTable = new ArrayList<>(srcWith);
        if (actualMaster != null) withTable.add(0, mm);
        for (SchemaModel m : withTable) {
            for (int i = 0; i < key.size(); i++) {
                ColumnDef c = m.tables().get(table).columns().get(key.get(i));
                if (c != null && keyTypes.get(i) != null && !c.baseType().equals(keyTypes.get(i).baseType())) {
                    issues.add(new Issue(Level.BLOCKER, table, "Key column " + key.get(i) + " is " + c.displayType() + " in " + m.nickname()
                            + " but " + keyTypes.get(i).displayType() + " in the merged table. A large table is streamed in key order, "
                            + "which needs the same type everywhere; align the column types first."));
                    return none;
                }
            }
        }
        KeyOrder order = new KeyOrder(key, keyTypes);

        String sequence = null;
        PkMode pkMode;
        if (pkCols.isEmpty()) pkMode = PkMode.NONE;
        else if (pkCols.size() == 1) {
            sequence = findSequence(table, def, mm, options);
            ColumnDef pc = def.columns().get(pkCols.get(0));
            boolean numeric = pc != null && Set.of("NUMBER", "INTEGER", "FLOAT").contains(pc.baseType());
            pkMode = sequence != null ? PkMode.SEQUENCE : numeric ? PkMode.MAX_PLUS_ONE : PkMode.KEEP_ONLY;
        } else pkMode = PkMode.KEEP_ONLY;

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
            srcCols.put(s.nickname(), cols);
        }
        if (!newCols.isEmpty() && !srcCols.isEmpty()) {
            issues.add(new Issue(Level.INFO, table, "Columns " + newCols + " do not exist in the master yet; they are filled only for newly inserted rows."));
        }

        // Primary keys already used in the master (and the highest numeric key anywhere, for MAX+1).
        KeySet masterKeys = new KeySet();
        BigDecimal maxPk = BigDecimal.ZERO;
        // Master keys are needed to spot a new row whose primary key is taken (impossible when the business key is the
        // primary key: such a row matches by key) and, for a table others point at, to accept references to master rows.
        boolean needKeys = referenced || !new LinkedHashSet<>(key).equals(new LinkedHashSet<>(pkCols));
        if (!pkCols.isEmpty() && (needKeys || pkMode == PkMode.MAX_PLUS_ONE)) {
            List<ColumnDef> pkTypes = pkCols.stream().map(c -> def.columns().get(c)).toList();
            KeyOrder pkOrder = new KeyOrder(pkCols, pkTypes);
            if (actualMaster != null) {
                try (RowCursor c = data.sorted(master, table, pkCols, pkCols, pkOrder)) {
                    for (Map<String, Object> r = c.next(); r != null; r = c.next()) {
                        if (needKeys) masterKeys.add(Canon.key(r, pkCols));
                        maxPk = maxPk.max(numericPk(r, pkCols));
                    }
                }
            }
            if (pkMode == PkMode.MAX_PLUS_ONE) {
                for (String src : srcCols.keySet()) {
                    try (RowCursor c = data.sorted(src, table, pkCols, pkCols, pkOrder)) {
                        for (Map<String, Object> r = c.next(); r != null; r = c.next()) maxPk = maxPk.max(numericPk(r, pkCols));
                    }
                }
            }
        }
        pkSets.put(table, masterKeys);

        String seq = sequence;
        PkMode mode = pkMode;
        BigDecimal startPk = maxPk;
        List<FkInfo> fks = foreignKeys(def, mergeTables);
        // A fresh planning state: keys in use start from the master's again, MAX+1 from the same maximum.
        java.util.function.Function<List<Issue>, Ctx> fresh = into -> {
            Ctx c = new Ctx(table, def, key, pkCols, newCols, mode, seq, rules, options, idMap, pkSets, into);
            KeySet inserted = new KeySet();
            c.referenced = referenced;
            c.used = new KeyUse() {
                @Override public boolean contains(String k) { return masterKeys.contains(k) || inserted.contains(k); }
                @Override public void add(String k) { if (needKeys && !masterKeys.contains(k)) inserted.add(k); }
            };
            c.nextPk = startPk;
            c.fks = fks;
            return c;
        };
        List<FkInfo> selfFks = fks.stream().filter(f -> f.parent.equals(table) && f.pkReference).toList();
        List<String> rowKey = pkCols.isEmpty() ? key : pkCols;

        Ctx x = fresh.apply(issues);
        long masterCount;
        PlanRows planned;
        try {
            if (selfFks.isEmpty()) {
                SpooledRows spool = new SpooledRows(spoolDir, table);
                GroupSink sink = new GroupSink(spool::append);
                x.rows = sink;
                try {
                    masterCount = walk(master, table, masterCols, srcCols, key, pkCols, order, x, sink, null, tablesWithSourceRows);
                } catch (OutOfOrder e) {
                    spool.finish().delete();
                    throw e;
                }
                planned = spool.finish();
            } else {
                // Pass 1: where every row goes, ignoring the self references; collect the hierarchy.
                SelfRefGraph g = new SelfRefGraph();
                Ctx p1 = fresh.apply(new ArrayList<>());
                p1.skipSelf = true;
                GroupSink s1 = new GroupSink(r -> { });
                p1.rows = s1;
                int[] previous = {-1};
                walk(master, table, masterCols, srcCols, key, pkCols, order, p1, s1, new RowHook() {
                    @Override public void group() { previous[0] = -1; }
                    @Override public void row(String src, Map<String, Object> row, RowAction outcome) {
                        List<String> parentKeys = new ArrayList<>();
                        for (FkInfo f : selfFks) {
                            Map<String, Object> asParent = new LinkedHashMap<>();
                            boolean nulls = false;
                            for (int i = 0; i < f.columns.size(); i++) {
                                Object v = row.get(f.columns.get(i));
                                if (v == null) nulls = true;
                                asParent.put(f.refColumns.get(i), v);
                            }
                            String parentKey = nulls ? null : Canon.key(asParent, f.parentPk);
                            if (parentKey != null && !parentKey.equals(Canon.key(row, rowKey))) parentKeys.add(parentKey); // not itself
                        }
                        previous[0] = g.add(src, Canon.key(row, rowKey), parentKeys, previous[0], outcome == RowAction.SKIP_ORPHAN);
                    }
                }, tablesWithSourceRows);
                List<Integer> cycle = g.resolve(masterKeys::contains);
                if (!cycle.isEmpty()) {
                    issues.add(new Issue(Level.BLOCKER, table, cycle.size() + " row(s) reference each other in a cycle through a "
                            + "self-referencing foreign key; they cannot be ordered."));
                    return none;
                }
                java.util.function.BiPredicate<String, String> orphanChain = (src, k) -> {
                    int n = g.node(src, k);
                    return n >= 0 && g.orphan(n);
                };
                // Pass 2: where rows go once the orphans are known (an orphan uses no key, so later ones can change).
                Ctx p2 = fresh.apply(new ArrayList<>());
                p2.skipSelf = true;
                p2.forcedOrphans = orphanChain;
                GroupSink s2 = new GroupSink(r -> { });
                p2.rows = s2;
                walk(master, table, masterCols, srcCols, key, pkCols, order, p2, s2, new RowHook() {
                    @Override public void row(String src, Map<String, Object> row, RowAction outcome) {
                        if (outcome == RowAction.SKIP_ORPHAN) idMap.remove(src, table, Canon.key(row, rowKey));
                    }
                }, tablesWithSourceRows);
                // Pass 3: the plan, with self references rewritten, written to one file per depth (parents first).
                x.forcedOrphans = orphanChain;
                x.sourcePkSet = (src, k) -> g.node(src, k) >= 0;
                x.decided = (src, k) -> true;
                Map<Integer, SpooledRows> byDepth = new TreeMap<>();
                GroupSink sink = new GroupSink(r -> {
                    int n = g.node(r.sourceDb(), r.oldKey());
                    int d = n >= 0 ? g.depth(n) : 0;
                    byDepth.computeIfAbsent(d, k -> new SpooledRows(spoolDir, table + "-d" + k)).append(r);
                });
                x.rows = sink;
                try {
                    masterCount = walk(master, table, masterCols, srcCols, key, pkCols, order, x, sink, null, tablesWithSourceRows);
                } catch (OutOfOrder e) {
                    byDepth.values().forEach(sp -> sp.finish().delete());
                    throw e;
                }
                List<SpooledRows> parts = new ArrayList<>();
                for (SpooledRows sp : byDepth.values()) parts.add(sp.finish());
                planned = new PlanRows.Chained(parts);
                issues.add(new Issue(Level.INFO, table, "Self-referencing: planned in three passes and written parents first, "
                        + parts.size() + " level(s) deep."));
            }
        } catch (OutOfOrder e) {
            issues.add(new Issue(Level.BLOCKER, table, e.getMessage() + " The plan was stopped so no rows are paired wrongly."));
            return none;
        }
        finishTable(x);
        issues.add(new Issue(Level.INFO, table, "Large table (" + biggest + " rows in the largest copy): streamed in business-key order, "
                + planned.size() + " planned row(s) kept in a plan file instead of memory."));
        return new TableStep(table, key, pkCols, allCols, pkMode, sequence, masterCount, planned);
    }

    /** Called for every source row of a walk, after it was decided. */
    private interface RowHook {
        default void group() { }

        void row(String src, Map<String, Object> row, RowAction outcome);
    }

    /**
     * One walk over the master and every source in key order: for each key, the master row(s) set the existing row
     * and each source's rows are decided in source order. Returns the number of master rows read.
     */
    private long walk(String master, String table, List<String> masterCols, Map<String, List<String>> srcCols, List<String> key,
                      List<String> pkCols, KeyOrder order, Ctx x, GroupSink sink, RowHook hook, Set<String> tablesWithSourceRows)
            throws SQLException {
        Map<String, VRow> vm = new LinkedHashMap<>();
        x.vmGet = vm::get;
        x.vmPut = vm::put;
        long masterCount = 0;
        Stream masterStream = null;
        List<Stream> sources = new ArrayList<>();
        try {
            if (!masterCols.isEmpty()) {
                masterStream = new Stream(master, data.sorted(master, table, masterCols, key, order), order, table);
            }
            for (var e : srcCols.entrySet()) sources.add(new Stream(e.getKey(), data.sorted(e.getKey(), table, e.getValue(), key, order), order, table));
            while (true) {
                List<Object> min = null;
                if (masterStream != null && masterStream.key != null) min = masterStream.key;
                for (Stream st : sources) if (st.key != null && (min == null || order.compare(st.key, min) < 0)) min = st.key;
                if (min == null) break;
                vm.clear();
                if (hook != null) hook.group();
                while (masterStream != null && masterStream.key != null && order.compare(masterStream.key, min) == 0) {
                    Map<String, Object> r = masterStream.row;
                    masterCount++;
                    vm.put(Canon.key(r, key), new VRow(new LinkedHashMap<>(r), pkMap(r, pkCols), -1));
                    masterStream.advance();
                }
                for (Stream st : sources) {
                    while (st.key != null && order.compare(st.key, min) == 0) {
                        processRow(x, st.db, srcCols.get(st.db), st.row);
                        if (hook != null) hook.row(st.db, st.row, sink.last);
                        tablesWithSourceRows.add(table);
                        st.advance();
                    }
                }
                sink.flush();
            }
        } finally {
            SQLException first = null;
            for (Stream st : sources) { try { st.cursor.close(); } catch (SQLException ex) { if (first == null) first = ex; } }
            if (masterStream != null) { try { masterStream.cursor.close(); } catch (SQLException ex) { if (first == null) first = ex; } }
            if (first != null) throw first;
        }
        return masterCount;
    }

    /** Issues collected while planning a table's rows: orphans, constraint violations, sequence advice. */
    private static void finishTable(Ctx x) {
        if (x.orphanCount > 0) {
            boolean skip = x.options.orphanPolicy() == OrphanPolicy.SKIP;
            x.issues.add(new Issue(skip ? Level.WARNING : Level.BLOCKER, x.table, x.orphanCount + " row(s) reference a parent row that does not exist"
                    + " (e.g. " + String.join("; ", x.orphanExamples) + ")." + (skip ? " They will be skipped." : " Fix the source data or set the orphan policy to SKIP.")));
        }
        for (var v : x.violations.entrySet()) {
            x.issues.add(new Issue(Level.BLOCKER, x.table, v.getValue() + " row(s) " + v.getKey() + " (e.g. " + x.violationExample.get(v.getKey()) + ")."));
        }
        if (x.sequence != null && x.keptSourceKey) {
            x.issues.add(new Issue(Level.WARNING, x.table, "Rows keep their source primary keys, so sequence " + x.sequence
                    + " may later hand out a key that is already used. Advance it beyond MAX of the key after the merge (sequences are not altered automatically)."));
        }
    }

    /**
     * Decides one source row: the shared core of both planning paths (in memory, and streamed for large tables).
     * {@code x} carries the table's state: which keys are used, where parents went, and how rows are looked up.
     */
    private static void processRow(Ctx x, String src, List<String> cols, Map<String, Object> row) {
        String oldKey = Canon.key(row, x.pkCols.isEmpty() ? x.key : x.pkCols);
        String bkey = Canon.key(row, x.key);
        String bdisplay = Canon.display(bkey, x.key);
        Map<String, Object> vals = new LinkedHashMap<>();
        Map<String, Object> cmp = new LinkedHashMap<>();
        for (String c : cols) {
            Object v = coerce(row.get(c), x.def.columns().get(c), x.table, x.issues);
            vals.put(c, v);
            cmp.put(c, v);
        }

        // Rewrite foreign keys through the key mapping of the (already planned) parents.
        List<FkInfo> loops = new ArrayList<>(1);
        boolean orphan = x.forcedOrphans.test(src, oldKey);
        for (FkInfo fk : x.fks) {
            boolean self = x.table.equals(fk.parent);
            if (self && x.skipSelf) continue;
            if (!cols.containsAll(fk.columns)) continue;
            if (self && fk.pkReference && pointsAtItself(fk, row, x.pkCols, oldKey)) {
                loops.add(fk); // the row is its own parent: it will point at its own new key (set below)
                continue;
            }
            List<Object> raw = new ArrayList<>();
            for (String c : fk.columns) raw.add(row.get(c));
            if (raw.stream().anyMatch(java.util.Objects::isNull)) continue;
            if (!fk.pkReference) continue;                      // references non-PK columns: copied as is
            Map<String, Object> asParentRow = new LinkedHashMap<>();
            for (int i = 0; i < fk.columns.size(); i++) asParentRow.put(fk.refColumns.get(i), raw.get(i));
            String oldParent = Canon.key(asParentRow, fk.parentPk);
            String mk = src + "|" + fk.parent + "|" + oldParent;
            Map<String, Object> target = x.idMap.get(src, fk.parent, oldParent);
            if (target != null) {
                for (int i = 0; i < fk.columns.size(); i++) {
                    vals.put(fk.columns.get(i), new FkRef(fk.parent, src, oldParent, fk.refColumns.get(i)));
                    Object concrete = target.get(fk.refColumns.get(i));
                    cmp.put(fk.columns.get(i), concrete != null ? concrete : "\u0000SYMBOLIC:" + mk);
                }
            } else if (self) {
                // A parent in the same table: planned (handled above), skipped as an orphan itself (so this row is one
                // too), not in this source but in the master (a valid reference), or nowhere (orphan).
                if (x.sourcePkSet.test(src, oldParent)) {
                    if (x.decided.test(src, oldParent)) orphan = true;
                    // else: not decided yet because of a cycle (reported as a blocker); the value is kept
                } else if (!(x.pkSets.containsKey(x.table) && x.pkSets.get(x.table).contains(oldParent))) {
                    orphan = true;
                }
            } else if (!x.idMap.hasAny(src, fk.parent) && x.pkSets.containsKey(fk.parent) && x.pkSets.get(fk.parent).contains(oldParent)) {
                // parent lives only in the master and the key exists there: reference stays valid
            } else {
                orphan = true;
            }
        }
        if (orphan) {
            x.orphanCount++;
            x.orphanExamples.add(src + " " + bdisplay);
            if (x.orphanExamples.size() > EXAMPLES) x.orphanExamples.pollLast();
            x.rows.add(new PlannedRow(src, RowAction.SKIP_ORPHAN, oldKey, bdisplay, vals, null, List.of(), "parent row not found"));
            return;
        }

        VRow ex = x.vmGet.apply(bkey);
        if (ex != null) pointAtItself(loops, src, x.table, oldKey, vals, cmp, ex.pk);
        if (ex == null) {
            // ---- new row
            boolean collision = !x.pkCols.isEmpty() && x.used.contains(Canon.key(row, x.pkCols));
            RowAction action = RowAction.INSERT;
            Map<String, Object> pk = pkMap(vals, x.pkCols);
            String note = null;
            if (collision) {
                switch (x.pkMode) {
                    case SEQUENCE -> {
                        action = RowAction.INSERT_REMAPPED;
                        pk = Map.of();
                        vals.put(x.pkCols.get(0), null);
                        note = "primary key " + oldKey + " already used; new key from sequence " + x.sequence;
                    }
                    case MAX_PLUS_ONE -> {
                        x.nextPk = x.nextPk.add(BigDecimal.ONE);
                        action = RowAction.INSERT_REMAPPED;
                        Object nv = x.nextPk;
                        vals.put(x.pkCols.get(0), nv);
                        pk = new LinkedHashMap<>();
                        pk.put(x.pkCols.get(0), nv);
                        note = "primary key " + oldKey + " already used; new key " + x.nextPk.toPlainString() + " (no sequence found, max+1)";
                        x.used.add(Canon.key(vals, x.pkCols));
                    }
                    default -> {
                        x.issues.add(new Issue(Level.BLOCKER, x.table, "Primary key collision for " + bdisplay + " from " + src
                                + " cannot be re-keyed automatically (composite or non-numeric key without a sequence)."));
                        x.rows.add(new PlannedRow(src, RowAction.UNRESOLVED_CONFLICT, oldKey, bdisplay, vals, null, List.of(), "key collision"));
                        return;
                    }
                }
            } else if (!x.pkCols.isEmpty()) {
                x.used.add(Canon.key(row, x.pkCols));
            }
            List<Object> masterPk = pk.isEmpty() ? null : new ArrayList<>(pk.values());
            pointAtItself(loops, src, x.table, oldKey, vals, cmp, pk);
            collectViolations(x.def, vals, x.table, x.violations, x.violationExample, src + " " + bdisplay);
            x.rows.add(new PlannedRow(src, action, oldKey, bdisplay, vals, masterPk, List.of(), note));
        if (action == RowAction.INSERT) x.keptSourceKey = true;
            x.record(src, x.table, oldKey, pk);
            Map<String, Object> cmpWithPk = new LinkedHashMap<>(cmp);
            cmpWithPk.putAll(pk);
            x.vmPut.accept(bkey, new VRow(cmpWithPk, pk, x.rows.size() - 1));
            return;
        }

        // ---- existing row (master or inserted earlier in this run)
        List<String> differing = new ArrayList<>();
        for (String c : cols) {
            if (x.pkCols.contains(c) || x.newCols.contains(c)) continue;
            if (!Canon.sameValue(cmp.get(c), ex.values.get(c))) differing.add(c);
        }
        if (differing.isEmpty()) {
            x.record(src, x.table, oldKey, ex.pk);
            x.rows.add(new PlannedRow(src, RowAction.SKIP_DUPLICATE, oldKey, bdisplay, Map.of(), listOf(ex.pk), List.of(), "identical to existing row"));
            return;
        }
        Rules.RowDecision rd = x.rules.rowDecision(x.table, bdisplay);
        Rules.TableRule tr = x.rules.tableRule(x.table);
        ConflictAction action = rd != null ? rd.getAction() : tr != null ? tr.getAction() : null;
        if (action == null) {
            x.rows.add(new PlannedRow(src, RowAction.UNRESOLVED_CONFLICT, oldKey, bdisplay, subset(vals, differing), listOf(ex.pk), differing,
                    "differs from existing row in " + differing + "; choose keep master / take source / newest / manual"));
            x.record(src, x.table, oldKey, ex.pk);
            return;
        }
        Map<String, Object> change = new LinkedHashMap<>();
        String why = null;
        if (action == ConflictAction.NEWEST) {
            String dc = tr == null ? null : tr.getDateColumn();
            if (dc == null || !cols.contains(dc)) {
                x.rows.add(new PlannedRow(src, RowAction.UNRESOLVED_CONFLICT, oldKey, bdisplay, subset(vals, differing), listOf(ex.pk), differing,
                        "'newest' needs a date column that exists in the table"));
                x.record(src, x.table, oldKey, ex.pk);
                return;
            }
            action = newer(cmp.get(dc), ex.values.get(dc)) ? ConflictAction.TAKE_SOURCE : ConflictAction.KEEP_MASTER;
            why = "newest by " + dc + ": " + (action == ConflictAction.TAKE_SOURCE ? src : "existing row") + " wins";
        }
        switch (action) {
            case KEEP_MASTER -> {
                x.rows.add(new PlannedRow(src, RowAction.SKIP_KEEP_MASTER, oldKey, bdisplay, Map.of(), listOf(ex.pk), differing,
                        why != null ? why : "conflict resolved: keep master"));
                x.record(src, x.table, oldKey, ex.pk);
                return;
            }
            case TAKE_SOURCE -> { for (String c : differing) change.put(c, vals.get(c)); }
            case MANUAL -> {
                if (rd == null || rd.getManualValues().isEmpty()) {
                    x.rows.add(new PlannedRow(src, RowAction.UNRESOLVED_CONFLICT, oldKey, bdisplay, subset(vals, differing), listOf(ex.pk), differing,
                            "manual decision without values"));
                    x.record(src, x.table, oldKey, ex.pk);
                    return;
                }
                for (var m : rd.getManualValues().entrySet()) {
                    ColumnDef cd = x.def.columns().get(m.getKey());
                    if (cd == null) throw new IllegalArgumentException("Manual value for unknown column " + m.getKey() + " in " + x.table);
                    change.put(m.getKey(), Canon.convert(m.getValue(), cd));
                }
            }
            default -> throw new IllegalStateException("unreachable");
        }
        x.record(src, x.table, oldKey, ex.pk);
        if (ex.stepRowIndex < 0 && x.pkCols.isEmpty()) {
            x.rows.add(new PlannedRow(src, RowAction.UNRESOLVED_CONFLICT, oldKey, bdisplay, subset(vals, differing), null, differing,
                    "table has no primary key, so an existing row cannot be updated; choose keep master"));
            return;
        }
        if (ex.stepRowIndex >= 0) {
            // The target row is being inserted by this run: patch that planned insert instead of updating.
            PlannedRow planned = x.rows.get(ex.stepRowIndex);
            // Unless the new values point at a row of this table from another source: the patched insert is written
            // where the earlier source's rows go, before the rows of this source, so its parent would not exist yet.
            for (var c : change.entrySet()) {
                if (c.getValue() instanceof FkRef f && f.parentTable().equals(x.table) && !f.sourceDb().equals(planned.sourceDb())) {
                    x.issues.add(new Issue(Level.BLOCKER, x.table, bdisplay + ": the values taken from " + src + " set " + c.getKey()
                            + " to a row of " + x.table + " from " + src + ", inside the row inserted from " + planned.sourceDb()
                            + ", which is written before " + src + "'s rows. Merge " + planned.sourceDb() + " and " + src
                            + " in separate runs, or keep the values of " + planned.sourceDb() + " for this row."));
                    x.rows.add(new PlannedRow(src, RowAction.UNRESOLVED_CONFLICT, oldKey, bdisplay, subset(vals, differing), null, differing,
                            "references a row of " + src + " from inside a row inserted from " + planned.sourceDb()));
                    return;
                }
            }
            Map<String, Object> patched = new LinkedHashMap<>(planned.values());
            patched.putAll(change);
            x.rows.set(ex.stepRowIndex, new PlannedRow(planned.sourceDb(), planned.action(), planned.oldKey(), planned.businessKey(),
                    patched, planned.masterPk(), planned.changedColumns(), (planned.note() == null ? "" : planned.note() + "; ")
                    + "values from " + src + " applied (" + (why != null ? why : "conflict rule") + ")"));
            x.rows.add(new PlannedRow(src, RowAction.SKIP_DUPLICATE, oldKey, bdisplay, Map.of(), listOf(ex.pk), differing,
                    "merged into row inserted from " + planned.sourceDb()));
        } else {
            collectViolations(x.def, change, x.table, x.violations, x.violationExample, src + " " + bdisplay);
            x.rows.add(new PlannedRow(src, RowAction.UPDATE, oldKey, bdisplay, change, listOf(ex.pk), new ArrayList<>(change.keySet()),
                    why != null ? why : "conflict resolved by rule"));
        }
        for (var c : change.entrySet()) ex.values.put(c.getKey(), c.getValue() instanceof FkRef ? "\u0000SYMBOLIC" : c.getValue());

    }

    /** Whether a self-referencing foreign key of {@code row} holds the row's own key (a root marked as its own parent). */
    private static boolean pointsAtItself(FkInfo fk, Map<String, Object> row, List<String> pkCols, String oldKey) {
        Map<String, Object> asParent = new LinkedHashMap<>();
        for (int i = 0; i < fk.columns.size(); i++) {
            Object v = row.get(fk.columns.get(i));
            if (v == null) return false;
            asParent.put(fk.refColumns.get(i), v);
        }
        return !pkCols.isEmpty() && Canon.key(asParent, fk.parentPk).equals(oldKey);
    }

    /**
     * A row that is its own parent keeps pointing at itself, at whatever key it ends up with: a reference to itself,
     * resolved by the executor once the row's key is known (also when a sequence allocates it).
     */
    private static void pointAtItself(List<FkInfo> loops, String src, String table, String oldKey, Map<String, Object> vals,
                                      Map<String, Object> cmp, Map<String, Object> ownPk) {
        for (FkInfo fk : loops) {
            for (int i = 0; i < fk.columns.size(); i++) {
                vals.put(fk.columns.get(i), new FkRef(table, src, oldKey, fk.refColumns.get(i)));
                Object own = ownPk == null ? null : ownPk.get(fk.refColumns.get(i));
                cmp.put(fk.columns.get(i), own != null ? own : "\u0000SYMBOLIC:" + src + "|" + table + "|" + oldKey);
            }
        }
    }

    /** Keys in use for a table's primary key: the master's plus those this plan inserts. */
    interface KeyUse {
        boolean contains(String key);
        void add(String key);
    }

    private static KeyUse keysOf(KeySet set) {
        return new KeyUse() {
            @Override public boolean contains(String key) { return set.contains(key); }
            @Override public void add(String key) { set.add(key); }
        };
    }

    /** Where planned rows go: a list in memory, or a group buffer in front of a plan file. Indices are absolute. */
    interface Sink {
        void add(PlannedRow r);
        PlannedRow get(int index);
        void set(int index, PlannedRow r);
        int size();
    }

    private record ListSink(List<PlannedRow> rows) implements Sink {
        @Override public void add(PlannedRow r) { rows.add(r); }
        @Override public PlannedRow get(int i) { return rows.get(i); }
        @Override public void set(int i, PlannedRow r) { rows.set(i, r); }
        @Override public int size() { return rows.size(); }
    }

    /** One table's planning state, shared by the in-memory and the streaming path. */
    private static final class Ctx {
        final String table;
        final TableDef def;
        final List<String> key, pkCols;
        final Set<String> newCols;
        final PkMode pkMode;
        final String sequence;
        final Rules rules;
        final Options options;
        final KeyMap idMap;
        final Map<String, KeySet> pkSets;
        final List<Issue> issues;
        final Map<String, Integer> violations = new LinkedHashMap<>();
        final Map<String, String> violationExample = new LinkedHashMap<>();
        final java.util.TreeSet<String> orphanExamples = new java.util.TreeSet<>(); // the first few in sort order
        int orphanCount;
        boolean keptSourceKey;
        KeyUse used;
        BigDecimal nextPk;
        List<FkInfo> fks;
        Sink rows;
        java.util.function.Function<String, VRow> vmGet;
        java.util.function.BiConsumer<String, VRow> vmPut;
        /** For self references: whether (source, key) is a row of this table in that source. */
        java.util.function.BiPredicate<String, String> sourcePkSet = (s, k) -> false;
        /** For self references: whether that source row has been decided already (in-memory order). */
        java.util.function.BiPredicate<String, String> decided = (s, k) -> true;
        /** Streaming passes that only work out where rows go leave self references out. */
        boolean skipSelf;
        /** (source, oldKey) of rows known to be orphans (their parent chain is broken), from an earlier pass. */
        java.util.function.BiPredicate<String, String> forcedOrphans = (s, k) -> false;
        /** Whether another merged table points at this one: only then are source keys recorded for rewriting children. */
        boolean referenced = true;

        void record(String src, String table, String oldKey, Map<String, Object> pk) {
            if (referenced) idMap.put(src, table, oldKey, pk);
        }

        Ctx(String table, TableDef def, List<String> key, List<String> pkCols, Set<String> newCols, PkMode pkMode, String sequence,
            Rules rules, Options options, KeyMap idMap, Map<String, KeySet> pkSets, List<Issue> issues) {
            this.table = table;
            this.def = def;
            this.key = key;
            this.pkCols = pkCols;
            this.newCols = newCols;
            this.pkMode = pkMode;
            this.sequence = sequence;
            this.rules = rules;
            this.options = options;
            this.idMap = idMap;
            this.pkSets = pkSets;
            this.issues = issues;
        }
    }

    /** Tables that a declared foreign key of another merged table (or of themselves) points at. */
    static Set<String> referencedTables(Map<String, TableDef> tables) {
        Set<String> out = new HashSet<>();
        for (TableDef t : tables.values()) {
            for (ConstraintDef c : t.constraints()) if (c.type().equals("R") && c.refTable() != null) out.add(c.refTable());
        }
        return out;
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

    /** SHA-256 over the plan, fed line by line so a plan with millions of rows never becomes one string. */
    static String fingerprint(String master, List<String> sources, Options options, List<TableStep> steps, List<AlterStatement> alters) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(master).append('|').append(sources).append('|').append(options).append('\n');
        for (AlterStatement a : alters) sb.append(a.id()).append('|').append(a.sql()).append('\n');
        feed(md, sb);
        for (TableStep s : steps) {
            sb.append(s.table()).append('|').append(s.pkMode()).append('|').append(s.sequence()).append('|').append(s.masterRowsAtPlan()).append('\n');
            for (PlannedRow r : s.rows()) {
                sb.append(r.action()).append('|').append(r.sourceDb()).append('|').append(r.oldKey()).append('|').append(r.masterPk()).append('|');
                for (var e : r.values().entrySet()) {
                    sb.append(e.getKey()).append('=').append(e.getValue() instanceof FkRef f ? f.toString() : Canon.of(e.getValue())).append(';');
                }
                sb.append('\n');
                if (sb.length() > 64 * 1024) feed(md, sb);
            }
        }
        feed(md, sb);
        return HexFormat.of().formatHex(md.digest(), 0, 12);
    }

    private static void feed(MessageDigest md, StringBuilder sb) {
        md.update(sb.toString().getBytes(StandardCharsets.UTF_8));
        sb.setLength(0);
    }
}
