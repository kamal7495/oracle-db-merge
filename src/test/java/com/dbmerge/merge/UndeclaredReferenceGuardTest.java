package com.dbmerge.merge;

import static com.dbmerge.ddl.compare.Fixtures.fk;
import static com.dbmerge.ddl.compare.Fixtures.model;
import static com.dbmerge.ddl.compare.Fixtures.number;
import static com.dbmerge.ddl.compare.Fixtures.pk;
import static com.dbmerge.ddl.compare.Fixtures.table;
import static com.dbmerge.ddl.compare.Fixtures.varchar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.TableDef;
import com.dbmerge.merge.MergeModel.FkRef;
import com.dbmerge.merge.MergeModel.Issue;
import com.dbmerge.merge.MergeModel.Level;
import com.dbmerge.merge.MergeModel.MergePlan;
import com.dbmerge.merge.MergeModel.Options;
import com.dbmerge.merge.MergeModel.OrphanPolicy;
import com.dbmerge.merge.MergeModel.PlannedRow;
import com.dbmerge.merge.MergeModel.RowAction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * TRADE is matched on EXTERNAL_REF; TRADE_KEYWORD and BO_MESSAGE refer to trades without declared foreign keys.
 * Master: trade 1 = EXT-A. Source: trade 1 = EXT-B (collides, re-keyed), trade 5 = EXT-C (keeps 5),
 * trade 7 = EXT-A (same trade as master 1, so its key becomes 1).
 */
class UndeclaredReferenceGuardTest {

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static BigDecimal n(long v) { return BigDecimal.valueOf(v); }

    private static SchemaModel schema(String nick, boolean declareKeywordFk) {
        TableDef trade = table("TRADE").col(number("TRADE_ID", 1, 10, 0, false), varchar("EXTERNAL_REF", 2, 30, false))
                .cons(pk("PK_TRADE", false, "TRADE_ID")).build();
        var kw = table("TRADE_KEYWORD").col(number("TRADE_ID", 1, 10, 0, false), varchar("KEYWORD_NAME", 2, 30, false),
                varchar("KEYWORD_VALUE", 3, 100, true));
        kw = declareKeywordFk ? kw.cons(pk("PK_KW", false, "TRADE_ID", "KEYWORD_NAME"), fk("FK_KW_TRADE", false, "TRADE_ID", "TRADE", "TRADE_ID"))
                : kw.cons(pk("PK_KW", false, "TRADE_ID", "KEYWORD_NAME"));
        TableDef msg = table("BO_MESSAGE").col(number("MESSAGE_ID", 1, 10, 0, false), number("OBJECT_ID", 2, 10, 0, true),
                varchar("OBJECT_CLASS", 3, 30, true)).cons(pk("PK_MSG", false, "MESSAGE_ID")).build();
        return model(nick, trade, kw.build(), msg);
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

    private static Map<String, List<Map<String, Object>>> db(List<Map<String, Object>> keywords, List<Map<String, Object>> messages) {
        Map<String, List<Map<String, Object>>> db = new LinkedHashMap<>();
        db.put("M|TRADE", List.of(row("TRADE_ID", n(1), "EXTERNAL_REF", "EXT-A")));
        db.put("S|TRADE", List.of(row("TRADE_ID", n(1), "EXTERNAL_REF", "EXT-B"), row("TRADE_ID", n(5), "EXTERNAL_REF", "EXT-C"),
                row("TRADE_ID", n(7), "EXTERNAL_REF", "EXT-A")));
        db.put("S|TRADE_KEYWORD", keywords);
        db.put("S|BO_MESSAGE", messages);
        return db;
    }

    private static MergePlan plan(Map<String, List<Map<String, Object>>> db, boolean declared, Set<String> ack) throws Exception {
        Map<String, SchemaModel> models = Map.of("M", schema("M", declared), "S", schema("S", declared));
        return new MergePlanner(data(db)).plan("M", List.of("S"), models, Map.of("TRADE", List.of("EXTERNAL_REF")), new Rules(),
                new Options(false, OrphanPolicy.FAIL, Map.of(), ack));
    }

    private static List<Issue> about(MergePlan p, String column) {
        return p.issues().stream().filter(i -> i.message().contains(column)).toList();
    }

    @Test
    void blocksWhenACopiedRowStillHoldsTheOldKeyOfAReKeyedParent() throws Exception {
        MergePlan p = plan(db(List.of(row("TRADE_ID", n(1), "KEYWORD_NAME", "Desk", "KEYWORD_VALUE", "Rates")), List.of()), false, Set.of());
        PlannedRow ext = p.steps().stream().filter(s -> s.table().equals("TRADE")).findFirst().orElseThrow().rows().stream()
                .filter(r -> r.businessKey().contains("EXT-B")).findFirst().orElseThrow();
        assertEquals(RowAction.INSERT_REMAPPED, ext.action(), "precondition: trade 1 from S collides and is re-keyed");

        List<Issue> issues = about(p, "TRADE_KEYWORD.TRADE_ID");
        assertEquals(1, issues.size(), p.issues().toString());
        assertEquals(Level.BLOCKER, issues.get(0).level());
        assertTrue(issues.get(0).message().contains("TRADE_ID=1"), issues.get(0).message());
        assertTrue(issues.get(0).message().contains("declare the foreign key"));
        assertTrue(p.hasBlockers());
    }

    @Test
    void doesNotBlockWhenNoCopiedRowPointsAtAChangedKey() throws Exception {
        MergePlan p = plan(db(List.of(row("TRADE_ID", n(5), "KEYWORD_NAME", "Desk", "KEYWORD_VALUE", "Credit")), List.of()), false, Set.of());
        assertTrue(about(p, "TRADE_KEYWORD.TRADE_ID").isEmpty(), "trade 5 keeps its key: " + p.issues());
        assertFalse(p.hasBlockers(), p.issues().toString());
    }

    @Test
    void aMatchedParentWithADifferentMasterKeyAlsoCounts() throws Exception {
        // Source trade 7 is master trade 1 (same EXTERNAL_REF): a keyword on trade 7 must become trade 1.
        MergePlan p = plan(db(List.of(row("TRADE_ID", n(7), "KEYWORD_NAME", "Desk", "KEYWORD_VALUE", "Rates")), List.of()), false, Set.of());
        List<Issue> issues = about(p, "TRADE_KEYWORD.TRADE_ID");
        assertEquals(1, issues.size(), p.issues().toString());
        assertEquals(Level.BLOCKER, issues.get(0).level());
        assertTrue(issues.get(0).message().contains("TRADE_ID=7"));
    }

    @Test
    void acknowledgingTheColumnTurnsTheBlockerIntoAWarning() throws Exception {
        MergePlan p = plan(db(List.of(row("TRADE_ID", n(1), "KEYWORD_NAME", "Desk", "KEYWORD_VALUE", "Rates")), List.of()), false,
                Set.of(" trade_keyword.trade_id "));
        List<Issue> issues = about(p, "TRADE_KEYWORD.TRADE_ID");
        assertEquals(1, issues.size());
        assertEquals(Level.WARNING, issues.get(0).level());
        assertTrue(issues.get(0).message().contains("Acknowledged"));
        assertFalse(p.hasBlockers(), p.issues().toString());
    }

    @Test
    void aDeclaredForeignKeyIsRewrittenAndNotFlagged() throws Exception {
        MergePlan p = plan(db(List.of(row("TRADE_ID", n(1), "KEYWORD_NAME", "Desk", "KEYWORD_VALUE", "Rates")), List.of()), true, Set.of());
        assertTrue(about(p, "TRADE_KEYWORD.TRADE_ID").isEmpty(), p.issues().toString());
        PlannedRow kw = p.steps().stream().filter(s -> s.table().equals("TRADE_KEYWORD")).findFirst().orElseThrow().rows().get(0);
        assertTrue(kw.values().get("TRADE_ID") instanceof FkRef, "the planner rewrites declared references");
    }

    @Test
    void typePlusIdReferencesWarnWhenKeysChange() throws Exception {
        MergePlan p = plan(db(List.of(), List.of(row("MESSAGE_ID", n(1), "OBJECT_ID", n(1), "OBJECT_CLASS", "Trade"))), false, Set.of());
        List<Issue> issues = about(p, "BO_MESSAGE.OBJECT_ID");
        assertEquals(1, issues.size(), p.issues().toString());
        assertEquals(Level.WARNING, issues.get(0).level(), "cannot be checked, so it warns instead of blocking");
    }

    @Test
    void acknowledgementsArePartOfThePlanFingerprint() throws Exception {
        var data = db(List.of(row("TRADE_ID", n(5), "KEYWORD_NAME", "Desk", "KEYWORD_VALUE", "Credit")), List.of());
        assertFalse(plan(data, false, Set.of()).fingerprint().equals(plan(data, false, Set.of("X.Y")).fingerprint()));
        assertEquals(plan(data, false, Set.of("A.B", "C.D")).fingerprint(), plan(data, false, Set.of("C.D", "A.B")).fingerprint());
    }
}
