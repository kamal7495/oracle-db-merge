package com.dbmerge.merge;

import static com.dbmerge.ddl.compare.Fixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.merge.MergeModel.ConflictAction;
import com.dbmerge.merge.MergeModel.FkRef;
import com.dbmerge.merge.MergeModel.MergePlan;
import com.dbmerge.merge.MergeModel.Options;
import com.dbmerge.merge.MergeModel.PkMode;
import com.dbmerge.merge.MergeModel.PlannedRow;
import com.dbmerge.merge.MergeModel.RowAction;
import com.dbmerge.merge.MergeModel.TableStep;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MergePlannerTest {

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static BigDecimal n(long v) { return BigDecimal.valueOf(v); }

    private static SchemaModel schema(String nick) {
        var customer = table("CUSTOMER")
                .col(number("ID", 1, 10, 0, false), varchar("EMAIL", 2, 100, false), varchar("NAME", 3, 100, true))
                .cons(pk("PK_CUSTOMER", false, "ID"), unique("UQ_CUSTOMER_EMAIL", false, "EMAIL"))
                .trg(trigger("TRG_CUSTOMER_ID", ":NEW.ID := SEQ_CUSTOMER.NEXTVAL;"))
                .build();
        var orders = table("ORDERS")
                .col(number("ID", 1, 10, 0, false), number("CUSTOMER_ID", 2, 10, 0, false), varchar("STATUS", 3, 20, true))
                .cons(pk("PK_ORDERS", false, "ID"), fk("FK_ORDERS_CUSTOMER", false, "CUSTOMER_ID", "CUSTOMER", "ID"))
                .build();
        return model(nick, List.of(sequence("SEQ_CUSTOMER", "1")), customer, orders);
    }

    /** In-memory databases keyed by "db|table". */
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

    private Map<String, List<Map<String, Object>>> sampleDb() {
        Map<String, List<Map<String, Object>>> db = new LinkedHashMap<>();
        db.put("M|CUSTOMER", List.of(row("ID", n(1), "EMAIL", "alice@x", "NAME", "Alice"), row("ID", n(2), "EMAIL", "bob@x", "NAME", "Bob")));
        db.put("M|ORDERS", List.of(row("ID", n(10), "CUSTOMER_ID", n(1), "STATUS", "NEW")));
        db.put("S1|CUSTOMER", List.of(
                row("ID", n(1), "EMAIL", "carol@x", "NAME", "Carol"),     // PK collision, different person
                row("ID", n(3), "EMAIL", "alice@x", "NAME", "Alice")));   // same person, different PK, identical data
        db.put("S1|ORDERS", List.of(
                row("ID", n(20), "CUSTOMER_ID", n(1), "STATUS", "NEW"),   // Carol's order
                row("ID", n(21), "CUSTOMER_ID", n(3), "STATUS", "NEW"),   // Alice's order
                row("ID", n(22), "CUSTOMER_ID", n(99), "STATUS", "NEW"))); // orphan
        return db;
    }

    private MergePlan plan(Map<String, List<Map<String, Object>>> db, Rules rules, Options options) throws Exception {
        Map<String, SchemaModel> models = Map.of("M", schema("M"), "S1", schema("S1"));
        return new MergePlanner(data(db)).plan("M", List.of("S1"), models, Map.of("CUSTOMER", List.of("EMAIL")), rules, options);
    }

    private static TableStep step(MergePlan p, String table) {
        return p.steps().stream().filter(s -> s.table().equals(table)).findFirst().orElseThrow();
    }

    @Test
    void parentsArePlannedBeforeChildren() throws Exception {
        MergePlan p = plan(sampleDb(), new Rules(), Options.defaults());
        List<String> order = p.steps().stream().map(TableStep::table).toList();
        assertTrue(order.indexOf("CUSTOMER") < order.indexOf("ORDERS"));
    }

    @Test
    void primaryKeyCollisionGetsSequenceKeyAndDuplicateIsSkipped() throws Exception {
        MergePlan p = plan(sampleDb(), new Rules(), Options.defaults());
        TableStep c = step(p, "CUSTOMER");
        assertEquals(PkMode.SEQUENCE, c.pkMode());
        assertEquals("SEQ_CUSTOMER", c.sequence());
        PlannedRow carol = c.rows().stream().filter(r -> r.businessKey().contains("carol")).findFirst().orElseThrow();
        assertEquals(RowAction.INSERT_REMAPPED, carol.action());
        assertNull(carol.masterPk());
        PlannedRow alice = c.rows().stream().filter(r -> r.businessKey().contains("alice")).findFirst().orElseThrow();
        assertEquals(RowAction.SKIP_DUPLICATE, alice.action());
        assertEquals(1, c.count(RowAction.INSERT_REMAPPED));
        assertEquals(1, c.count(RowAction.SKIP_DUPLICATE));
    }

    @Test
    void childForeignKeysAreRewrittenThroughTheKeyMapping() throws Exception {
        MergePlan p = plan(sampleDb(), new Rules(), Options.defaults());
        TableStep o = step(p, "ORDERS");
        PlannedRow carolsOrder = o.rows().stream().filter(r -> r.oldKey().equals("20")).findFirst().orElseThrow();
        assertEquals(RowAction.INSERT, carolsOrder.action());
        assertEquals(new FkRef("CUSTOMER", "S1", "1", "ID"), carolsOrder.values().get("CUSTOMER_ID"));
        PlannedRow alicesOrder = o.rows().stream().filter(r -> r.oldKey().equals("21")).findFirst().orElseThrow();
        assertEquals(new FkRef("CUSTOMER", "S1", "3", "ID"), alicesOrder.values().get("CUSTOMER_ID"));
    }

    @Test
    void orphanChildrenFailThePlanByDefaultAndAreSkippedWhenAsked() throws Exception {
        MergePlan strict = plan(sampleDb(), new Rules(), Options.defaults());
        assertTrue(strict.hasBlockers());
        MergePlan lenient = plan(sampleDb(), new Rules(),
                new Options(false, MergeModel.OrphanPolicy.SKIP, Map.of()));
        assertFalse(lenient.hasBlockers());
        assertEquals(1, step(lenient, "ORDERS").count(RowAction.SKIP_ORPHAN));
    }

    @Test
    void conflictsBlockUntilARuleExistsAndThenFollowIt() throws Exception {
        var db = sampleDb();
        db.put("S1|CUSTOMER", List.of(row("ID", n(3), "EMAIL", "alice@x", "NAME", "Alice Smith")));
        db.put("S1|ORDERS", List.of());
        MergePlan open = plan(db, new Rules(), Options.defaults());
        assertEquals(1, step(open, "CUSTOMER").count(RowAction.UNRESOLVED_CONFLICT));
        assertTrue(open.hasBlockers());

        Rules keep = new Rules();
        keep.getTables().put("CUSTOMER", new Rules.TableRule(ConflictAction.KEEP_MASTER, null));
        assertEquals(1, step(plan(db, keep, Options.defaults()), "CUSTOMER").count(RowAction.SKIP_KEEP_MASTER));

        Rules take = new Rules();
        take.getTables().put("CUSTOMER", new Rules.TableRule(ConflictAction.TAKE_SOURCE, null));
        MergePlan tp = plan(db, take, Options.defaults());
        PlannedRow upd = step(tp, "CUSTOMER").rows().get(0);
        assertEquals(RowAction.UPDATE, upd.action());
        assertEquals(List.of("NAME"), upd.changedColumns());
        assertEquals("Alice Smith", upd.values().get("NAME"));
        assertFalse(tp.hasBlockers());
    }

    @Test
    void manualDecisionOverridesWithTheEnteredValue() throws Exception {
        var db = sampleDb();
        db.put("S1|CUSTOMER", List.of(row("ID", n(3), "EMAIL", "alice@x", "NAME", "Alice Smith")));
        db.put("S1|ORDERS", List.of());
        Rules r = new Rules();
        r.getRows().put("CUSTOMER", Map.of("EMAIL=alice@x", new Rules.RowDecision(ConflictAction.MANUAL, Map.of("NAME", "Alice S."))));
        PlannedRow upd = step(plan(db, r, Options.defaults()), "CUSTOMER").rows().get(0);
        assertEquals(RowAction.UPDATE, upd.action());
        assertEquals("Alice S.", upd.values().get("NAME"));
    }

    @Test
    void sourceRowsThatDuplicateEachOtherAreLoadedOnce() throws Exception {
        Map<String, List<Map<String, Object>>> db = new LinkedHashMap<>();
        db.put("S1|CUSTOMER", List.of(row("ID", n(5), "EMAIL", "dave@x", "NAME", "Dave")));
        db.put("S2|CUSTOMER", List.of(row("ID", n(7), "EMAIL", "dave@x", "NAME", "Dave")));
        Map<String, SchemaModel> models = Map.of("M", schema("M"), "S1", schema("S1"), "S2", schema("S2"));
        MergePlan p = new MergePlanner(data(db)).plan("M", List.of("S1", "S2"), models,
                Map.of("CUSTOMER", List.of("EMAIL")), new Rules(), Options.defaults());
        TableStep c = step(p, "CUSTOMER");
        assertEquals(1, c.count(RowAction.INSERT));
        assertEquals(1, c.count(RowAction.SKIP_DUPLICATE));
    }

    @Test
    void identicalInputGivesIdenticalFingerprintAndChangedInputDoesNot() throws Exception {
        String a = plan(sampleDb(), new Rules(), Options.defaults()).fingerprint();
        String b = plan(sampleDb(), new Rules(), Options.defaults()).fingerprint();
        assertEquals(a, b);
        var changed = sampleDb();
        changed.put("M|CUSTOMER", List.of(row("ID", n(1), "EMAIL", "alice@x", "NAME", "Alice")));
        assertFalse(a.equals(plan(changed, new Rules(), Options.defaults()).fingerprint()));
        assertNotNull(a);
    }

    @Test
    void auditTableIsProposedWhenMasterLacksIt() throws Exception {
        MergePlan p = plan(sampleDb(), new Rules(), Options.defaults());
        assertTrue(p.alters().stream().anyMatch(x -> x.kind() == MergeModel.AlterKind.CREATE_AUDIT_TABLE));
    }
}
