package com.dbmerge.merge;

import static com.dbmerge.ddl.compare.Fixtures.fk;
import static com.dbmerge.ddl.compare.Fixtures.model;
import static com.dbmerge.ddl.compare.Fixtures.number;
import static com.dbmerge.ddl.compare.Fixtures.pk;
import static com.dbmerge.ddl.compare.Fixtures.sequence;
import static com.dbmerge.ddl.compare.Fixtures.table;
import static com.dbmerge.ddl.compare.Fixtures.trigger;
import static com.dbmerge.ddl.compare.Fixtures.unique;
import static com.dbmerge.ddl.compare.Fixtures.varchar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.TableDef;
import com.dbmerge.merge.MergeModel.ConflictAction;
import com.dbmerge.merge.MergeModel.FkRef;
import com.dbmerge.merge.MergeModel.Issue;
import com.dbmerge.merge.MergeModel.Level;
import com.dbmerge.merge.MergeModel.MergePlan;
import com.dbmerge.merge.MergeModel.Options;
import com.dbmerge.merge.MergeModel.OrphanPolicy;
import com.dbmerge.merge.MergeModel.PlannedRow;
import com.dbmerge.merge.MergeModel.TableStep;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The streaming planner (large tables) must plan exactly what the in-memory planner plans. Every scenario is planned
 * both ways, with streaming forced for every table, and the plans are compared row for row (as multisets: the
 * streaming path visits rows in business-key order) and issue for issue.
 */
class StreamingPlannerTest {

    @TempDir Path spool;

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static BigDecimal n(long v) { return BigDecimal.valueOf(v); }

    /** CUSTOMER (sequence-backed PK, matched on EMAIL), ORDERS (MAX+1 PK) referencing it, NOTE without a sequence. */
    private static SchemaModel schema(String nick) {
        TableDef customer = table("CUSTOMER")
                .col(number("ID", 1, 10, 0, false), varchar("EMAIL", 2, 100, false), varchar("NAME", 3, 100, true))
                .cons(pk("PK_CUSTOMER", false, "ID"), unique("UQ_CUSTOMER_EMAIL", false, "EMAIL"))
                .trg(trigger("TRG_CUSTOMER_ID", ":NEW.ID := SEQ_CUSTOMER.NEXTVAL;")).build();
        TableDef orders = table("ORDERS")
                .col(number("ID", 1, 10, 0, false), number("CUSTOMER_ID", 2, 10, 0, false), varchar("STATUS", 3, 20, true))
                .cons(pk("PK_ORDERS", false, "ID"), fk("FK_ORDERS_CUSTOMER", false, "CUSTOMER_ID", "CUSTOMER", "ID")).build();
        TableDef note = table("NOTE")
                .col(number("NOTE_ID", 1, 10, 0, false), varchar("CODE", 2, 20, false), varchar("TEXT", 3, 50, true))
                .cons(pk("PK_NOTE", false, "NOTE_ID")).build();
        return model(nick, List.of(sequence("SEQ_CUSTOMER", "1")), customer, orders, note);
    }

    private static MergePlanner.MergeData data(Map<String, List<Map<String, Object>>> db) {
        return (d, t, cols) -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : db.getOrDefault(d + "|" + t, List.of())) {
                Map<String, Object> c = new LinkedHashMap<>();
                for (String col : cols) c.put(col, r.get(col));
                out.add(c);
            }
            return out;
        };
    }

    private static final Map<String, List<String>> KEYS = Map.of("CUSTOMER", List.of("EMAIL"), "NOTE", List.of("CODE"));

    private MergePlan plan(Map<String, List<Map<String, Object>>> db, List<String> sources, Rules rules, Options opt, boolean stream)
            throws Exception {
        Map<String, SchemaModel> models = new LinkedHashMap<>();
        models.put("M", schema("M"));
        for (String s : sources) models.put(s, schema(s));
        MergePlanner p = stream ? new MergePlanner(data(db), 0, spool) : new MergePlanner(data(db));
        return p.plan("M", sources, models, KEYS, rules, opt);
    }

    /** A plan reduced to what matters, independent of row order. */
    private static Map<String, List<String>> normal(MergePlan p) {
        Map<String, List<String>> out = new TreeMap<>();
        for (TableStep s : p.steps()) {
            List<String> rows = new ArrayList<>();
            for (PlannedRow r : s.rows()) {
                Map<String, String> vals = new TreeMap<>();
                r.values().forEach((k, v) -> vals.put(k, v instanceof FkRef f ? f.toString() : String.valueOf(Canon.of(v))));
                rows.add(r.sourceDb() + " " + r.action() + " old=" + r.oldKey() + " key=" + r.businessKey() + " masterPk=" + r.masterPk()
                        + " " + vals + " changed=" + r.changedColumns() + " note=" + r.note());
            }
            rows.sort(null);
            out.put(s.table() + " (" + s.pkMode() + ", master rows " + s.masterRowsAtPlan() + ")", rows);
        }
        List<String> issues = new ArrayList<>();
        for (Issue i : p.issues()) {
            if (i.level() == Level.INFO && (i.message().startsWith("Large table") || i.message().startsWith("Self-referencing"))) continue;
            issues.add(i.level() + " " + i.table() + ": " + i.message());
        }
        issues.sort(null);
        out.put("~issues", issues);
        return out;
    }

    private void assertSamePlan(Map<String, List<Map<String, Object>>> db, List<String> sources, Rules rules, Options opt) throws Exception {
        MergePlan mem = plan(db, sources, rules, opt, false);
        MergePlan str = plan(db, sources, rules, opt, true);
        assertSameNormal(mem, str);
        assertEquals(mem.hasBlockers(), str.hasBlockers());
        assertTrue(str.issues().stream().anyMatch(i -> i.message().startsWith("Large table")), "streaming was really used");
    }

    /** A plan with INSERT and INSERT_REMAPPED rows reduced to "an insert of this source row", without the key it got. */
    private static Map<String, List<String>> insertsAsOne(Map<String, List<String>> plan) {
        Map<String, List<String>> out = new TreeMap<>();
        plan.forEach((table, rows) -> {
            List<String> r = new ArrayList<>();
            for (String row : rows) {
                row = row.replaceAll("masterPk=\\[[^]]*]", "masterPk=?"); // the key a row ended up with or matched to
                if (row.contains(" INSERT old=") || row.contains(" INSERT_REMAPPED old=")) {
                    row = row.replace(" INSERT_REMAPPED old=", " INSERT old=").replaceAll("masterPk=\\[[^]]*]", "masterPk=?")
                            .replaceAll("BOOK_ID=\\d+", "BOOK_ID=?").replaceAll(" note=.*$", "");
                }
                r.add(row);
            }
            r.sort(null);
            out.put(table.startsWith("~") ? table : table, r);
        });
        out.computeIfPresent("~issues", (k, v) -> v.stream().filter(i -> !i.contains("may later hand out a key")).toList());
        return out;
    }

    /** Fails with only the rows that are in one plan and not the other. */
    private static void assertSameNormal(MergePlan mem, MergePlan str) {
        Map<String, List<String>> a = normal(mem), b = normal(str);
        if (a.equals(b)) return;
        StringBuilder d = new StringBuilder();
        java.util.Set<String> keys = new java.util.TreeSet<>(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            List<String> onlyMem = new ArrayList<>(a.getOrDefault(k, List.of())), onlyStr = new ArrayList<>(b.getOrDefault(k, List.of()));
            b.getOrDefault(k, List.of()).forEach(onlyMem::remove);
            a.getOrDefault(k, List.of()).forEach(onlyStr::remove);
            for (String r : onlyMem) d.append("\n  in memory only  ").append(k).append(": ").append(r);
            for (String r : onlyStr) d.append("\n  streaming only  ").append(k).append(": ").append(r);
        }
        throw new AssertionError("plans differ:" + d);
    }

    private static Map<String, List<Map<String, Object>>> sampleDb() {
        Map<String, List<Map<String, Object>>> db = new LinkedHashMap<>();
        db.put("M|CUSTOMER", List.of(row("ID", n(1), "EMAIL", "alice@x", "NAME", "Alice"), row("ID", n(2), "EMAIL", "bob@x", "NAME", "Bob")));
        db.put("M|ORDERS", List.of(row("ID", n(10), "CUSTOMER_ID", n(1), "STATUS", "NEW")));
        db.put("M|NOTE", List.of(row("NOTE_ID", n(1), "CODE", "A", "TEXT", "a"), row("NOTE_ID", n(2), "CODE", "B", "TEXT", "b")));
        db.put("S1|CUSTOMER", List.of(
                row("ID", n(1), "EMAIL", "carol@x", "NAME", "Carol"),      // PK collision: re-keyed by sequence
                row("ID", n(3), "EMAIL", "alice@x", "NAME", "Alice"),      // same person, other PK
                row("ID", n(2), "EMAIL", "bob@x", "NAME", "Robert")));     // conflict
        db.put("S1|ORDERS", List.of(
                row("ID", n(20), "CUSTOMER_ID", n(1), "STATUS", "NEW"),    // Carol's order: parent re-keyed
                row("ID", n(21), "CUSTOMER_ID", n(3), "STATUS", "NEW"),    // Alice's order: parent matched to 1
                row("ID", n(10), "CUSTOMER_ID", n(3), "STATUS", "PAID"),   // same order id as master, data differs
                row("ID", n(22), "CUSTOMER_ID", n(99), "STATUS", "NEW"))); // orphan
        db.put("S1|NOTE", List.of(row("NOTE_ID", n(1), "CODE", "C", "TEXT", "c"),  // collision, no sequence: MAX+1
                row("NOTE_ID", n(5), "CODE", "B", "TEXT", "b")));                    // duplicate of master B
        return db;
    }

    private static Rules rule(ConflictAction a) {
        Rules r = new Rules();
        for (String t : List.of("CUSTOMER", "ORDERS", "NOTE")) r.getTables().put(t, new Rules.TableRule(a, null));
        return r;
    }

    @Test
    void sampleScenariosPlanTheSameBothWays() throws Exception {
        for (OrphanPolicy op : OrphanPolicy.values()) {
            Options opt = new Options(false, op, Map.of());
            assertSamePlan(sampleDb(), List.of("S1"), new Rules(), opt);
            assertSamePlan(sampleDb(), List.of("S1"), rule(ConflictAction.KEEP_MASTER), opt);
            assertSamePlan(sampleDb(), List.of("S1"), rule(ConflictAction.TAKE_SOURCE), opt);
        }
        Rules manual = rule(ConflictAction.KEEP_MASTER);
        manual.getRows().computeIfAbsent("CUSTOMER", k -> new LinkedHashMap<>())
                .put("EMAIL=bob@x", new Rules.RowDecision(ConflictAction.MANUAL, Map.of("NAME", "Bobby")));
        assertSamePlan(sampleDb(), List.of("S1"), manual, Options.defaults());
    }

    @Test
    void severalSourcesAndRowsThatAnEarlierSourceInserted() throws Exception {
        Map<String, List<Map<String, Object>>> db = sampleDb();
        db.put("S2|CUSTOMER", List.of(
                row("ID", n(40), "EMAIL", "carol@x", "NAME", "Caroline"),  // S1 inserts carol; S2 conflicts with that new row
                row("ID", n(41), "EMAIL", "dave@x", "NAME", "Dave")));
        db.put("S2|ORDERS", List.of(row("ID", n(30), "CUSTOMER_ID", n(40), "STATUS", "NEW")));
        db.put("S2|NOTE", List.of(row("NOTE_ID", n(9), "CODE", "C", "TEXT", "c")));       // duplicate of what S1 inserts
        assertSamePlan(db, List.of("S1", "S2"), new Rules(), Options.defaults());
        assertSamePlan(db, List.of("S1", "S2"), rule(ConflictAction.TAKE_SOURCE), Options.defaults());
    }

    @Test
    void randomDatabasesPlanTheSameBothWays() throws Exception {
        Random rnd = new Random(7);
        for (int round = 0; round < 300; round++) {
            Map<String, List<Map<String, Object>>> db = new LinkedHashMap<>();
            int people = 3 + rnd.nextInt(8);
            for (String d : List.of("M", "S1")) {
                List<Map<String, Object>> cs = new ArrayList<>(), os = new ArrayList<>(), ns = new ArrayList<>();
                List<Integer> ids = new ArrayList<>();
                for (int i = 0; i < people; i++) {
                    if (rnd.nextInt(3) == 0) continue;                         // not every person in every database
                    int id = rnd.nextInt(people * 2) + 1;                    // ids collide across databases by chance
                    if (ids.contains(id)) continue;
                    ids.add(id);
                    cs.add(row("ID", n(id), "EMAIL", "p" + i + "@x", "NAME", rnd.nextInt(4) == 0 ? "renamed" : "P" + i));
                }
                for (int o = 0; o < 6; o++) {
                    int oid = 100 + rnd.nextInt(10);
                    if (os.stream().anyMatch(r -> r.get("ID").equals(n(oid)))) continue;
                    int cid = ids.isEmpty() || rnd.nextInt(6) == 0 ? 999 : ids.get(rnd.nextInt(ids.size()));
                    os.add(row("ID", n(oid), "CUSTOMER_ID", n(cid), "STATUS", rnd.nextBoolean() ? "NEW" : "PAID"));
                }
                for (int k = 0; k < 4; k++) {
                    int nid = 1 + rnd.nextInt(6);
                    if (ns.stream().anyMatch(r -> r.get("NOTE_ID").equals(n(nid)))) continue;
                    String code = String.valueOf((char) ('A' + rnd.nextInt(5)));
                    if (ns.stream().anyMatch(r -> r.get("CODE").equals(code))) continue;
                    ns.add(row("NOTE_ID", n(nid), "CODE", code, "TEXT", rnd.nextInt(3) == 0 ? "x" : code.toLowerCase()));
                }
                db.put(d + "|CUSTOMER", cs);
                db.put(d + "|ORDERS", os);
                db.put(d + "|NOTE", ns);
            }
            ConflictAction[] actions = {null, ConflictAction.KEEP_MASTER, ConflictAction.TAKE_SOURCE};
            ConflictAction a = actions[rnd.nextInt(actions.length)];
            Rules rules = a == null ? new Rules() : rule(a);
            Options opt = new Options(false, rnd.nextBoolean() ? OrphanPolicy.FAIL : OrphanPolicy.SKIP, Map.of());
            try {
                assertSamePlan(db, List.of("S1"), rules, opt);
            } catch (AssertionError e) {
                throw new AssertionError("round " + round + " with " + db + ": " + e.getMessage(), e);
            }
        }
    }

    // ------------------------------------------------------------------ self-referencing tables

    /** REGION, and BOOK with two self references (PARENT_ID, ALT_PARENT_ID), matched on NAME; no sequence (MAX+1). */
    private static SchemaModel books(String nick) {
        TableDef region = table("REGION").col(number("REGION_ID", 1, 10, 0, false)).cons(pk("PK_REGION", false, "REGION_ID")).build();
        TableDef book = table("BOOK")
                .col(number("BOOK_ID", 1, 10, 0, false), varchar("NAME", 2, 30, false), number("REGION_ID", 3, 10, 0, true),
                        number("PARENT_ID", 4, 10, 0, true), number("ALT_PARENT_ID", 5, 10, 0, true))
                .cons(pk("PK_BOOK", false, "BOOK_ID"), unique("UQ_BOOK_NAME", false, "NAME"),
                        fk("FK_BOOK_REGION", false, "REGION_ID", "REGION", "REGION_ID"),
                        fk("FK_BOOK_PARENT", false, "PARENT_ID", "BOOK", "BOOK_ID"),
                        fk("FK_BOOK_ALT", false, "ALT_PARENT_ID", "BOOK", "BOOK_ID")).build();
        return model(nick, region, book);
    }

    private MergePlan planBooks(Map<String, List<Map<String, Object>>> db, List<String> sources, Rules rules, Options opt, boolean stream)
            throws Exception {
        Map<String, SchemaModel> models = new LinkedHashMap<>();
        models.put("M", books("M"));
        for (String s : sources) models.put(s, books(s));
        MergePlanner p = stream ? new MergePlanner(data(db), 0, spool) : new MergePlanner(data(db));
        return p.plan("M", sources, models, Map.of("BOOK", List.of("NAME")), rules, opt);
    }

    /** Every row comes after the rows of its own table and source that it references: what the executor relies on. */
    private static void assertParentsFirst(MergePlan p) {
        for (TableStep s : p.steps()) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (PlannedRow r : s.rows()) {
                for (Object v : r.values().values()) {
                    boolean itself = v instanceof FkRef f0 && f0.sourceDb().equals(r.sourceDb()) && f0.oldKey().equals(r.oldKey());
                    if (v instanceof FkRef f && f.parentTable().equals(s.table()) && !itself) {
                        assertTrue(seen.contains(f.sourceDb() + "|" + f.oldKey()), (p.issues().stream().anyMatch(i -> i.message()
                                .startsWith("Large table")) ? "streaming: " : "in memory: ") + s.table() + " " + r.action() + " row "
                                + r.sourceDb() + "|" + r.oldKey() + " comes before its parent " + f.oldKey());
                    }
                }
                seen.add(r.sourceDb() + "|" + r.oldKey());
            }
        }
    }

    private void assertSameBooks(Map<String, List<Map<String, Object>>> db, List<String> sources, Rules rules, Options opt) throws Exception {
        MergePlan mem = planBooks(db, sources, rules, opt, false);
        MergePlan str = planBooks(db, sources, rules, opt, true);
        boolean memCycle = mem.issues().stream().anyMatch(i -> i.level() == Level.BLOCKER && i.message().contains("cycle"));
        boolean strCycle = str.issues().stream().anyMatch(i -> i.level() == Level.BLOCKER && i.message().contains("cycle"));
        assertEquals(memCycle, strCycle, "a cycle must block both ways");
        if (memCycle) return; // the plan cannot run; the paths report it differently (rows planned around it vs none)
        if (sources.size() == 1) {
            assertSameNormal(mem, str);
        } else {
            // With several sources, two different new rows may bring the same id; which one keeps it follows source
            // order in memory and key order when streamed (both valid). Compare everything else exactly.
            Map<String, List<String>> a = insertsAsOne(normal(mem)), b = insertsAsOne(normal(str));
            if (!a.equals(b)) {
                StringBuilder d = new StringBuilder();
                for (String k : new java.util.TreeSet<>(a.keySet())) {
                    List<String> x = new ArrayList<>(a.get(k)), y = new ArrayList<>(b.getOrDefault(k, List.of()));
                    List<String> ox = new ArrayList<>(x), oy = new ArrayList<>(y);
                    y.forEach(ox::remove);
                    x.forEach(oy::remove);
                    ox.forEach(r -> d.append("\n  in memory only  ").append(r));
                    oy.forEach(r -> d.append("\n  streaming only  ").append(r));
                }
                throw new AssertionError("plans differ beyond who kept a colliding id:" + d);
            }
        }
        assertParentsFirst(mem);
        assertParentsFirst(str);
    }

    @Test
    void selfReferencesAreRewrittenOrphansCascadeAndParentsComeFirst() throws Exception {
        Map<String, List<Map<String, Object>>> db = new LinkedHashMap<>();
        db.put("M|REGION", List.of(row("REGION_ID", n(1))));
        db.put("S1|REGION", List.of(row("REGION_ID", n(1))));
        db.put("M|BOOK", List.of(row("BOOK_ID", n(1), "NAME", "Rates", "REGION_ID", n(1), "PARENT_ID", null, "ALT_PARENT_ID", null),
                row("BOOK_ID", n(2), "NAME", "Credit", "REGION_ID", n(1), "PARENT_ID", n(1), "ALT_PARENT_ID", null)));
        db.put("S1|BOOK", List.of(
                row("BOOK_ID", n(9), "NAME", "FX Options", "REGION_ID", n(1), "PARENT_ID", n(1), "ALT_PARENT_ID", null), // child before parent in key order
                row("BOOK_ID", n(1), "NAME", "FX", "REGION_ID", n(1), "PARENT_ID", null, "ALT_PARENT_ID", null),         // collides: re-keyed (MAX+1)
                row("BOOK_ID", n(3), "NAME", "Swaps", "REGION_ID", n(1), "PARENT_ID", n(2), "ALT_PARENT_ID", n(9)),     // parent only in the master
                row("BOOK_ID", n(4), "NAME", "Bad region", "REGION_ID", n(77), "PARENT_ID", null, "ALT_PARENT_ID", null), // orphan (region)
                row("BOOK_ID", n(5), "NAME", "Under bad", "REGION_ID", n(1), "PARENT_ID", n(4), "ALT_PARENT_ID", null),  // child of an orphan
                row("BOOK_ID", n(6), "NAME", "Grandchild", "REGION_ID", n(1), "PARENT_ID", n(5), "ALT_PARENT_ID", null), // and its child
                row("BOOK_ID", n(7), "NAME", "Lost", "REGION_ID", n(1), "PARENT_ID", n(555), "ALT_PARENT_ID", null)));   // parent nowhere
        for (OrphanPolicy op : OrphanPolicy.values()) {
            Options opt = new Options(false, op, Map.of());
            assertSameBooks(db, List.of("S1"), new Rules(), opt);
            MergePlan p = planBooks(db, List.of("S1"), new Rules(), opt, true);
            TableStep book = p.steps().stream().filter(t -> t.table().equals("BOOK")).findFirst().orElseThrow();
            assertEquals(4, book.count(MergeModel.RowAction.SKIP_ORPHAN), "bad region, its child and grandchild, and the lost one");
            PlannedRow fxOptions = book.rows().stream().filter(r -> r.businessKey().contains("FX Options")).findFirst().orElseThrow();
            assertEquals(new FkRef("BOOK", "S1", "1", "BOOK_ID"), fxOptions.values().get("PARENT_ID"), "follows its re-keyed parent");
            PlannedRow swaps = book.rows().stream().filter(r -> r.businessKey().contains("Swaps")).findFirst().orElseThrow();
            assertEquals(n(2), swaps.values().get("PARENT_ID"), "a parent that exists only in the master stays as it is");
        }
    }

    @Test
    void aCycleThroughTheSelfReferenceIsABlockerBothWays() throws Exception {
        Map<String, List<Map<String, Object>>> db = new LinkedHashMap<>();
        db.put("M|REGION", List.of(row("REGION_ID", n(1))));
        db.put("S1|BOOK", List.of(row("BOOK_ID", n(1), "NAME", "A", "REGION_ID", null, "PARENT_ID", n(2), "ALT_PARENT_ID", null),
                row("BOOK_ID", n(2), "NAME", "B", "REGION_ID", null, "PARENT_ID", n(1), "ALT_PARENT_ID", null)));
        for (boolean stream : new boolean[] {false, true}) {
            MergePlan p = planBooks(db, List.of("S1"), new Rules(), Options.defaults(), stream);
            assertTrue(p.issues().stream().anyMatch(i -> i.level() == Level.BLOCKER && i.message().contains("cycle")), "stream=" + stream);
        }
    }

    @Test
    void randomHierarchiesPlanTheSameBothWays() throws Exception {
        Random rnd = new Random(11);
        for (int round = 0; round < 300; round++) {
            Map<String, List<Map<String, Object>>> db = new LinkedHashMap<>();
            List<String> sources = rnd.nextInt(3) == 0 ? List.of("S1", "S2") : List.of("S1");
            List<Integer> masterIds = new ArrayList<>();
            for (String d : new ArrayList<String>() {{ add("M"); addAll(sources); }}) {
                db.put(d + "|REGION", List.of(row("REGION_ID", n(1)), row("REGION_ID", n(2))));
                List<Map<String, Object>> books = new ArrayList<>();
                List<Integer> ids = new ArrayList<>();
                int count = 2 + rnd.nextInt(9);
                for (int i = 0; i < count; i++) {
                    int id = 1 + rnd.nextInt(14);
                    if (ids.contains(id)) continue;
                    String name = "B" + rnd.nextInt(12);
                    if (books.stream().anyMatch(b -> b.get("NAME").equals(name))) continue;
                    Integer parent = pickParent(rnd, ids, masterIds, d.equals("M"));
                    Integer alt = rnd.nextInt(4) == 0 ? pickParent(rnd, ids, masterIds, d.equals("M")) : null;
                    int region = rnd.nextInt(10) == 0 ? 99 : 1 + rnd.nextInt(2);           // 99: orphan by region
                    books.add(row("BOOK_ID", n(id), "NAME", name, "REGION_ID", n(region),
                            "PARENT_ID", parent == null ? null : n(parent), "ALT_PARENT_ID", alt == null ? null : n(alt)));
                    ids.add(id);
                }
                if (d.equals("M")) {
                    books.removeIf(b -> b.get("REGION_ID").equals(n(99)) || b.get("PARENT_ID") != null);  // a clean master
                    masterIds.clear();
                    books.forEach(b -> masterIds.add(((BigDecimal) b.get("BOOK_ID")).intValue()));
                }
                db.put(d + "|BOOK", books);
            }
            ConflictAction[] actions = {null, ConflictAction.KEEP_MASTER, ConflictAction.TAKE_SOURCE};
            ConflictAction a = actions[rnd.nextInt(actions.length)];
            Rules rules = new Rules();
            if (a != null) for (String t : List.of("REGION", "BOOK")) rules.getTables().put(t, new Rules.TableRule(a, null));
            Options opt = new Options(false, rnd.nextBoolean() ? OrphanPolicy.FAIL : OrphanPolicy.SKIP, Map.of());
            try {
                assertSameBooks(db, sources, rules, opt);
            } catch (AssertionError e) {
                throw new AssertionError("round " + round + ": " + e.getMessage(), e);
            }
        }
    }

    /** A parent that does not create a cycle: an earlier row, a master row, a missing key (500+), or none. */
    private static Integer pickParent(Random rnd, List<Integer> earlier, List<Integer> master, boolean isMaster) {
        int r = rnd.nextInt(5);
        if (r == 0 || (r == 1 && earlier.isEmpty())) return null;
        if (r <= 2 && !earlier.isEmpty()) return earlier.get(rnd.nextInt(earlier.size()));
        if (r == 3 && !master.isEmpty() && !isMaster) return master.get(rnd.nextInt(master.size()));
        return 500 + rnd.nextInt(5);
    }
}
