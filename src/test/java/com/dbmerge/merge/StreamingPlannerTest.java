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
            if (i.level() == Level.INFO && i.message().startsWith("Large table")) continue;
            issues.add(i.level() + " " + i.table() + ": " + i.message());
        }
        issues.sort(null);
        out.put("~issues", issues);
        return out;
    }

    private void assertSamePlan(Map<String, List<Map<String, Object>>> db, List<String> sources, Rules rules, Options opt) throws Exception {
        MergePlan mem = plan(db, sources, rules, opt, false);
        MergePlan str = plan(db, sources, rules, opt, true);
        Map<String, List<String>> a = normal(mem), b = normal(str);
        if (!a.equals(b)) {
            StringBuilder d = new StringBuilder();
            for (String k : new java.util.TreeSet<>(java.util.Set.copyOf(new ArrayList<String>() {{ addAll(a.keySet()); addAll(b.keySet()); }}))) {
                List<String> x = new ArrayList<>(a.getOrDefault(k, List.of())), y = new ArrayList<>(b.getOrDefault(k, List.of()));
                List<String> onlyMem = new ArrayList<>(x), onlyStr = new ArrayList<>(y);
                y.forEach(onlyMem::remove);
                x.forEach(onlyStr::remove);
                for (String r : onlyMem) d.append("\n  in memory only  ").append(k).append(": ").append(r);
                for (String r : onlyStr) d.append("\n  streaming only  ").append(k).append(": ").append(r);
            }
            throw new AssertionError("plans differ:" + d);
        }
        assertEquals(mem.hasBlockers(), str.hasBlockers());
        assertTrue(str.issues().stream().anyMatch(i -> i.message().startsWith("Large table")), "streaming was really used");
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
}
