package com.dbmerge.merge;

import static com.dbmerge.ddl.compare.Fixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.ddl.model.TableDef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class FkGraphTest {

    private static Map<String, TableDef> tables(TableDef... ts) {
        Map<String, TableDef> m = new LinkedHashMap<>();
        for (TableDef t : ts) m.put(t.name(), t);
        return m;
    }

    private static TableDef t(String name, com.dbmerge.ddl.model.ConstraintDef... cons) {
        return table(name).col(number("ID", 1, 10, 0, false), number("REF", 2, 10, 0, true)).cons(cons).build();
    }

    @Test
    void ordersParentsBeforeChildrenForTheSampleChain() {
        var g = FkGraph.build(tables(
                t("REGION"),
                t("PRODUCT"),
                t("CUSTOMER", fk("FK_C_R", false, "REF", "REGION", "ID")),
                t("ORDERS", fk("FK_O_C", false, "REF", "CUSTOMER", "ID")),
                t("ORDER_ITEM", fk("FK_I_O", false, "REF", "ORDERS", "ID"), fk("FK_I_P", false, "ID", "PRODUCT", "ID"))));
        assertEquals(List.of("PRODUCT", "REGION", "CUSTOMER", "ORDERS", "ORDER_ITEM"), g.order());
        assertTrue(g.cycles().isEmpty());
        assertTrue(g.selfEdges().isEmpty());
    }

    @Test
    void detectsCyclesAndNamesTheForeignKeysInvolved() {
        var g = FkGraph.build(tables(
                t("A", fk("FK_A_B", false, "REF", "B", "ID")),
                t("B", fk("FK_B_A", false, "REF", "A", "ID")),
                t("C")));
        assertEquals(1, g.cycles().size());
        assertEquals(Set.of("A", "B"), g.cycles().get(0));
        assertEquals(2, g.cycleEdges().size());
        assertEquals("C", g.order().get(0));
        assertEquals(3, g.order().size());
    }

    @Test
    void selfReferencesAreReportedSeparatelyAndDoNotCountAsCycles() {
        var g = FkGraph.build(tables(t("EMP", fk("FK_MGR", false, "REF", "EMP", "ID"))));
        assertTrue(g.cycles().isEmpty());
        assertEquals(1, g.selfEdges().size());
        assertEquals(List.of("EMP"), g.order());
    }

    @Test
    void foreignKeysToTablesOutsideTheMergeSetAreIgnored() {
        var g = FkGraph.build(tables(t("X", fk("FK_X_Y", false, "REF", "NOT_MERGED", "ID"))));
        assertEquals(List.of("X"), g.order());
    }

    @Test
    void tablesDependingOnACycleStillGetOrdered() {
        var g = FkGraph.build(tables(
                t("A", fk("FK_A_B", false, "REF", "B", "ID")),
                t("B", fk("FK_B_A", false, "REF", "A", "ID")),
                t("D", fk("FK_D_A", false, "REF", "A", "ID"))));
        assertEquals(3, g.order().size());
        assertEquals(1, g.cycles().size());
        assertTrue(g.order().contains("D"));
    }
}
