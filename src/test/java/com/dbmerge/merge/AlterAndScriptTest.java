package com.dbmerge.merge;

import static com.dbmerge.ddl.compare.Fixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.merge.MergeModel.AlterKind;
import com.dbmerge.merge.MergeModel.AlterStatement;
import com.dbmerge.merge.MergeModel.MergePlan;
import com.dbmerge.merge.MergeModel.Options;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AlterAndScriptTest {

    private static SchemaModel master() {
        return model("M", table("T").col(number("ID", 1, 10, 0, false), varchar("NAME", 2, 50, true)).cons(pk("PK_T", false, "ID")).build());
    }

    @Test
    void proposesMissingColumnWideningAndNewTableButNeverDropsOrNarrows() {
        SchemaModel src = model("S",
                table("T").col(number("ID", 1, 10, 0, false), varchar("NAME", 2, 200, true), varchar("EXTRA", 3, 10, true)).cons(pk("PK_T", false, "ID")).build(),
                table("NEWT").col(number("ID", 1, 10, 0, false)).cons(pk("PK_NEWT", false, "ID")).build());
        var res = AlterScriptGenerator.generate(master(), List.of(src));
        List<AlterKind> kinds = res.alters().stream().map(AlterStatement::kind).toList();
        assertTrue(kinds.contains(AlterKind.CREATE_AUDIT_TABLE));
        assertTrue(kinds.contains(AlterKind.ADD_COLUMN));
        assertTrue(kinds.contains(AlterKind.WIDEN_COLUMN));
        assertTrue(kinds.contains(AlterKind.CREATE_TABLE));
        assertTrue(res.alters().stream().noneMatch(a -> a.sql().toUpperCase().contains("DROP")));
        assertEquals(200, res.effective().get("T").columns().get("NAME").length().intValue());
        assertTrue(res.effective().containsKey("NEWT"));
    }

    @Test
    void narrowerSourceColumnProducesNoAlter() {
        SchemaModel src = model("S", table("T").col(number("ID", 1, 10, 0, false), varchar("NAME", 2, 10, true)).cons(pk("PK_T", false, "ID")).build());
        var res = AlterScriptGenerator.generate(master(), List.of(src));
        assertTrue(res.alters().stream().noneMatch(a -> a.kind() == AlterKind.WIDEN_COLUMN));
    }

    @Test
    void scriptHasBackupReminderRollbackDefaultAndNoPasswords() throws Exception {
        Map<String, List<Map<String, Object>>> db = new LinkedHashMap<>();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ID", BigDecimal.valueOf(5));
        r.put("NAME", "O'Brien");
        db.put("S|T", List.of(r));
        SchemaModel src = model("S", table("T").col(number("ID", 1, 10, 0, false), varchar("NAME", 2, 50, true)).cons(pk("PK_T", false, "ID")).build());
        MergePlan p = new MergePlanner((d, t, cols) -> db.getOrDefault(d + "|" + t, List.of()))
                .plan("M", List.of("S"), Map.of("M", master(), "S", src), null, new Rules(), Options.defaults());
        String sql = ScriptRenderer.render(p, Set.of());
        assertTrue(sql.contains("Data Pump"));
        assertTrue(sql.contains("O''Brien"));
        assertTrue(sql.contains("INSERT INTO \"T\""));
        assertTrue(sql.contains("ROLLBACK;"));
        assertTrue(sql.contains("-- COMMIT;"), "COMMIT must be commented out in the review script");
        assertFalse(sql.toLowerCase().contains("password"));
    }

    @Test
    void literalsAreRenderedPerType() {
        assertEquals("NULL", ScriptRenderer.toVal(null));
        assertEquals("12.50", ScriptRenderer.toVal(new BigDecimal("12.50")));
        assertEquals("'a''b'", ScriptRenderer.toVal("a'b"));
        assertEquals("HEXTORAW('0aff')", ScriptRenderer.toVal(new byte[] {10, (byte) 0xff}));
        assertEquals("TIMESTAMP '2024-01-02 03:04:05'", ScriptRenderer.toVal(Timestamp.valueOf("2024-01-02 03:04:05")));
    }

    @Test
    void canonTreatsNumericScaleAsEqual() {
        assertTrue(Canon.sameValue(new BigDecimal("1.50"), new BigDecimal("1.5")));
        assertTrue(Canon.sameValue(new BigDecimal("0.00"), BigDecimal.ZERO));
        assertFalse(Canon.sameValue("a", null));
        assertTrue(Canon.sameValue(null, null));
    }
}
