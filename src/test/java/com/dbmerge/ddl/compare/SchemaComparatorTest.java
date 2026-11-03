package com.dbmerge.ddl.compare;

import static com.dbmerge.ddl.compare.Fixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.ddl.model.TableDef;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaComparatorTest {

    private final SchemaComparator cmp = new SchemaComparator();

    private static TableDef customer() {
        return table("CUSTOMER")
                .col(number("ID", 1, 10, 0, false), varchar("EMAIL", 2, 120, false))
                .cons(pk("SYS_C001", true, "ID"), unique("UQ_EMAIL", false, "EMAIL"))
                .build();
    }

    private static Difference only(ComparisonResult r) {
        assertEquals(1, r.differences().size(), () -> "expected exactly one difference but got " + r.differences());
        return r.differences().get(0);
    }

    @Test
    void identicalSchemasHaveNoDifferences() {
        var r = cmp.compare(List.of(model("A", customer()), model("B", customer())));
        assertTrue(r.differences().isEmpty());
    }

    @Test
    void rejectsFewerThanTwoDatabases() {
        assertThrows(IllegalArgumentException.class, () -> cmp.compare(List.of(model("A", customer()))));
    }

    @Test
    void missingTableIsHighAndShowsWhereItIsMissing() {
        var r = cmp.compare(List.of(model("A", customer()), model("B")));
        Difference d = only(r);
        assertEquals(DiffKind.MISSING_TABLE, d.kind());
        assertEquals(Severity.HIGH, d.severity());
        assertEquals(SchemaComparator.MISSING, d.values().get("B"));
    }

    @Test
    void missingNullableColumnIsMediumButNotNullWithoutDefaultIsHigh() {
        var a = table("T").col(number("ID", 1, 10, 0, false), varchar("PHONE", 2, 20, true)).build();
        var b = table("T").col(number("ID", 1, 10, 0, false)).build();
        assertEquals(Severity.MEDIUM, only(cmp.compare(List.of(model("A", a), model("B", b)))).severity());

        var a2 = table("T").col(number("ID", 1, 10, 0, false), varchar("TAX", 2, 20, false)).build();
        Difference d = only(cmp.compare(List.of(model("A", a2), model("B", b))));
        assertEquals(DiffKind.MISSING_COLUMN, d.kind());
        assertEquals(Severity.HIGH, d.severity());
    }

    @Test
    void incompatibleTypeFamiliesAreCriticalCompatibleOnesAreMedium() {
        var a = table("T").col(number("QTY", 1, 6, 0, false)).build();
        var b = table("T").col(varchar("QTY", 1, 6, false)).build();
        Difference d = only(cmp.compare(List.of(model("A", a), model("B", b))));
        assertEquals(DiffKind.TYPE_MISMATCH, d.kind());
        assertEquals(Severity.CRITICAL, d.severity());

        var c = table("T").col(charCol("CODE", 1, 5, false)).build();
        var e = table("T").col(varchar("CODE", 1, 5, false)).build();
        Difference d2 = only(cmp.compare(List.of(model("A", c), model("B", e))));
        assertEquals(DiffKind.TYPE_MISMATCH, d2.kind());
        assertEquals(Severity.MEDIUM, d2.severity());
    }

    @Test
    void lengthAndPrecisionMismatchAreHighCharSemanticsOnlyIsMedium() {
        var a = table("T").col(varchar("E", 1, 120, false), number("P", 2, 10, 2, false)).build();
        var b = table("T").col(varchar("E", 1, 200, false), number("P", 2, 12, 2, false)).build();
        var r = cmp.compare(List.of(model("A", a), model("B", b)));
        assertEquals(2, r.differences().size());
        assertTrue(r.differences().stream().allMatch(d -> d.kind() == DiffKind.LENGTH_MISMATCH && d.severity() == Severity.HIGH));

        var c = table("T").col(varchar("E", 1, 50, false)).build();
        var e = table("T").col(varcharBytes("E", 1, 50, false)).build();
        assertEquals(Severity.MEDIUM, only(cmp.compare(List.of(model("A", c), model("B", e)))).severity());
    }

    @Test
    void nullabilityIsMediumAndDefaultIsLow() {
        var a = table("T").col(varchar("N", 1, 10, true), withDefault(varchar("D", 2, 10, false), "'X'")).build();
        var b = table("T").col(varchar("N", 1, 10, false), withDefault(varchar("D", 2, 10, false), "'Y'")).build();
        var r = cmp.compare(List.of(model("A", a), model("B", b)));
        assertEquals(2, r.differences().size());
        assertTrue(r.differences().stream().anyMatch(d -> d.kind() == DiffKind.NULLABLE_MISMATCH && d.severity() == Severity.MEDIUM));
        assertTrue(r.differences().stream().anyMatch(d -> d.kind() == DiffKind.DEFAULT_MISMATCH && d.severity() == Severity.LOW));
    }

    @Test
    void systemGeneratedConstraintNamesDoNotCauseDifferences() {
        var a = table("O").col(number("ID", 1, 10, 0, false), number("C", 2, 10, 0, false))
                .cons(pk("SYS_C0011", true, "ID"), fk("SYS_C0012", true, "C", "CUSTOMER", "ID")).build();
        var b = table("O").col(number("ID", 1, 10, 0, false), number("C", 2, 10, 0, false))
                .cons(pk("SYS_C9981", true, "ID"), fk("SYS_C9982", true, "C", "CUSTOMER", "ID")).build();
        assertTrue(cmp.compare(List.of(model("A", a), model("B", b))).differences().isEmpty());
    }

    @Test
    void differentPrimaryKeyColumnsAreCritical() {
        var a = table("T").col(number("ID", 1, 10, 0, false), varchar("CODE", 2, 5, false)).cons(pk("PK_T", false, "ID")).build();
        var b = table("T").col(number("ID", 1, 10, 0, false), varchar("CODE", 2, 5, false)).cons(pk("PK_T", false, "CODE")).build();
        Difference d = only(cmp.compare(List.of(model("A", a), model("B", b))));
        assertEquals(DiffKind.CONSTRAINT_DEFINITION_MISMATCH, d.kind());
        assertEquals(Severity.CRITICAL, d.severity());
    }

    @Test
    void missingPrimaryKeyIsCriticalAndMissingForeignKeyIsHigh() {
        var a = table("T").col(number("ID", 1, 10, 0, false), number("R", 2, 10, 0, false))
                .cons(pk("SYS_C1", true, "ID"), fk("FK_R", false, "R", "REF", "ID")).build();
        var b = table("T").col(number("ID", 1, 10, 0, false), number("R", 2, 10, 0, false)).build();
        var r = cmp.compare(List.of(model("A", a), model("B", b)));
        assertEquals(2, r.differences().size());
        assertEquals(Severity.CRITICAL, r.differences().get(0).severity());
        assertEquals(Severity.HIGH, r.differences().get(1).severity());
        assertTrue(r.differences().stream().allMatch(d -> d.kind() == DiffKind.MISSING_CONSTRAINT));
    }

    @Test
    void namedCheckWithDifferentConditionDiffersButWhitespaceAndCaseDoNot() {
        var a = table("O").col(varchar("S", 1, 10, false)).cons(check("CK_S", false, "status IN ('NEW','PAID')")).build();
        var same = table("O").col(varchar("S", 1, 10, false)).cons(check("CK_S", false, "STATUS  IN ('NEW','PAID')")).build();
        var other = table("O").col(varchar("S", 1, 10, false)).cons(check("CK_S", false, "status IN ('NEW','PAID','RETURNED')")).build();
        assertTrue(cmp.compare(List.of(model("A", a), model("B", same))).differences().isEmpty());
        Difference d = only(cmp.compare(List.of(model("A", a), model("B", other))));
        assertEquals(DiffKind.CONSTRAINT_DEFINITION_MISMATCH, d.kind());
        assertEquals(Severity.MEDIUM, d.severity());
    }

    @Test
    void notNullCheckConstraintsAreIgnored() {
        var a = table("T").col(varchar("X", 1, 10, false)).cons(check("SYS_C5", true, "\"X\" IS NOT NULL")).build();
        var b = table("T").col(varchar("X", 1, 10, false)).build();
        assertTrue(cmp.compare(List.of(model("A", a), model("B", b))).differences().isEmpty());
    }

    @Test
    void disabledConstraintIsReported() {
        var a = table("T").col(number("R", 1, 10, 0, false)).cons(fk("FK_R", false, "R", "REF", "ID")).build();
        var b = table("T").col(number("R", 1, 10, 0, false)).cons(disabled(fk("FK_R", false, "R", "REF", "ID"))).build();
        Difference d = only(cmp.compare(List.of(model("A", a), model("B", b))));
        assertEquals(DiffKind.CONSTRAINT_STATUS_MISMATCH, d.kind());
    }

    @Test
    void indexesMissingUniqueIsMediumPlainIsLow() {
        var a = table("T").col(varchar("X", 1, 10, true), varchar("Y", 2, 10, true))
                .idx(index("IX_X", false, "X"), index("UX_Y", true, "Y")).build();
        var b = table("T").col(varchar("X", 1, 10, true), varchar("Y", 2, 10, true)).build();
        var r = cmp.compare(List.of(model("A", a), model("B", b)));
        assertEquals(2, r.differences().size());
        assertEquals(Severity.MEDIUM, r.differences().get(0).severity());
        assertEquals(Severity.LOW, r.differences().get(1).severity());
    }

    @Test
    void triggersMissingAndChangedBody() {
        var a = table("T").col(varchar("X", 1, 10, true)).trg(trigger("TRG_1", "BEGIN NULL; END;"), trigger("TRG_2", "BEGIN a; END;")).build();
        var b = table("T").col(varchar("X", 1, 10, true)).trg(trigger("TRG_2", "BEGIN b; END;")).build();
        var r = cmp.compare(List.of(model("A", a), model("B", b)));
        assertEquals(2, r.differences().size());
        assertTrue(r.differences().stream().anyMatch(d -> d.kind() == DiffKind.MISSING_TRIGGER));
        assertTrue(r.differences().stream().anyMatch(d -> d.kind() == DiffKind.TRIGGER_DEFINITION_MISMATCH));
    }

    @Test
    void sequencesMissingAndParameterMismatch() {
        var a = model("A", List.of(sequence("S1", "1"), sequence("S2", "1")));
        var b = model("B", List.of(sequence("S1", "5")));
        var r = cmp.compare(List.of(a, b));
        assertEquals(2, r.differences().size());
        assertTrue(r.differences().stream().anyMatch(d -> d.kind() == DiffKind.MISSING_SEQUENCE && d.object().equals("S2")));
        assertTrue(r.differences().stream().anyMatch(d -> d.kind() == DiffKind.SEQUENCE_PARAM_MISMATCH
                && d.severity() == Severity.MEDIUM && d.object().equals("S1")));
    }

    @Test
    void threeDatabasesCompareAllAgainstAll() {
        var base = table("T").col(number("ID", 1, 10, 0, false), varchar("EMAIL", 2, 120, false)).build();
        var longer = table("T").col(number("ID", 1, 10, 0, false), varchar("EMAIL", 2, 200, false)).build();
        var noEmail = table("T").col(number("ID", 1, 10, 0, false)).build();
        var r = cmp.compare(List.of(model("A", base), model("B", longer), model("C", noEmail)));
        assertEquals(2, r.differences().size());
        Difference len = r.differences().stream().filter(d -> d.kind() == DiffKind.LENGTH_MISMATCH).findFirst().orElseThrow();
        assertEquals(List.of("A", "B", "C"), List.copyOf(len.values().keySet()));
        assertEquals(SchemaComparator.MISSING, len.values().get("C"));
        assertEquals(Severity.HIGH, r.worstSeverityByTable().get("T"));
    }
}
