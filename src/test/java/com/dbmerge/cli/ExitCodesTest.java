package com.dbmerge.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.dbmerge.cli.ExitCodes.Threshold;
import com.dbmerge.data.DataModel.Status;
import com.dbmerge.data.DataModel.TableDataResult;
import com.dbmerge.ddl.compare.Severity;
import com.dbmerge.merge.MergeModel.Issue;
import com.dbmerge.merge.MergeModel.Level;
import com.dbmerge.merge.MergeModel.MergePlan;
import com.dbmerge.merge.MergeModel.Options;
import com.dbmerge.merge.MergeModel.OrphanPolicy;
import com.dbmerge.refs.RefModel.Confidence;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExitCodesTest {

    private static Map<Severity, Integer> sev(Severity s, int n) {
        Map<Severity, Integer> m = new EnumMap<>(Severity.class);
        for (Severity x : Severity.values()) m.put(x, 0);
        m.put(s, n);
        return m;
    }

    @Test
    void ddlFailsAtOrAboveTheThreshold() {
        assertEquals(ExitCodes.OK, ExitCodes.ddl(sev(Severity.LOW, 0), Threshold.LOW));
        assertEquals(ExitCodes.FINDINGS, ExitCodes.ddl(sev(Severity.HIGH, 1), Threshold.HIGH));
        assertEquals(ExitCodes.FINDINGS, ExitCodes.ddl(sev(Severity.CRITICAL, 1), Threshold.HIGH));
        assertEquals(ExitCodes.OK, ExitCodes.ddl(sev(Severity.MEDIUM, 3), Threshold.HIGH));
        assertEquals(ExitCodes.FINDINGS, ExitCodes.ddl(sev(Severity.MEDIUM, 3), Threshold.MEDIUM));
        assertEquals(ExitCodes.OK, ExitCodes.ddl(sev(Severity.HIGH, 1), Threshold.CRITICAL));
        assertEquals(ExitCodes.OK, ExitCodes.ddl(sev(Severity.CRITICAL, 9), Threshold.NEVER));
    }

    @Test
    void refsFailsAtOrAboveTheConfidence() {
        Map<Confidence, Long> m = new EnumMap<>(Confidence.class);
        m.put(Confidence.HIGH, 0L);
        m.put(Confidence.MEDIUM, 2L);
        m.put(Confidence.LOW, 5L);
        assertEquals(ExitCodes.OK, ExitCodes.refs(m, Threshold.HIGH));
        assertEquals(ExitCodes.FINDINGS, ExitCodes.refs(m, Threshold.MEDIUM));
        assertEquals(ExitCodes.FINDINGS, ExitCodes.refs(m, Threshold.LOW));
        m.put(Confidence.HIGH, 1L);
        assertEquals(ExitCodes.FINDINGS, ExitCodes.refs(m, Threshold.HIGH));
        assertEquals(ExitCodes.OK, ExitCodes.refs(m, Threshold.NEVER));
    }

    private static TableDataResult table(Status status, Map<String, Long> rows, long onlyA, long conflicts, long collisions,
                                         long divergence) {
        return new TableDataResult("T", status, null, List.of("ID"), true, List.of("ID"), rows, List.of("ID"), Map.of(),
                Map.of("A", onlyA), 10, conflicts, collisions, divergence, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of());
    }

    @Test
    void dataFailsOnAnyDifference() {
        Map<String, Long> same = Map.of("A", 10L, "B", 10L);
        assertEquals(ExitCodes.OK, ExitCodes.data(List.of(table(Status.COMPARED, same, 0, 0, 0, 0)), false));
        assertEquals(ExitCodes.FINDINGS, ExitCodes.data(List.of(table(Status.COMPARED, same, 1, 0, 0, 0)), false));
        assertEquals(ExitCodes.FINDINGS, ExitCodes.data(List.of(table(Status.COMPARED, same, 0, 1, 0, 0)), false));
        assertEquals(ExitCodes.FINDINGS, ExitCodes.data(List.of(table(Status.COMPARED, same, 0, 0, 1, 0)), false));
        assertEquals(ExitCodes.FINDINGS, ExitCodes.data(List.of(table(Status.COMPARED, same, 0, 0, 0, 1)), false));
        assertEquals(ExitCodes.FINDINGS,
                ExitCodes.data(List.of(table(Status.COMPARED, Map.of("A", 10L, "B", 11L), 0, 0, 0, 0)), false));
        assertEquals(ExitCodes.FINDINGS,
                ExitCodes.data(List.of(table(Status.SINGLE_DATABASE, Map.of("A", 3L, "B", -1L), 0, 0, 0, 0)), true));
    }

    @Test
    void uncomparedTablesFailUnlessAllowed() {
        Map<String, Long> same = Map.of("A", 10L, "B", 10L);
        for (Status s : List.of(Status.NO_KEY, Status.TOO_LARGE, Status.ERROR)) {
            assertEquals(ExitCodes.FINDINGS, ExitCodes.data(List.of(table(s, same, 0, 0, 0, 0)), false), s.name());
            assertEquals(ExitCodes.OK, ExitCodes.data(List.of(table(s, same, 0, 0, 0, 0)), true), s.name());
        }
    }

    private static MergePlan plan(List<Issue> issues) {
        return new MergePlan("p1", Instant.now(), "M", List.of("S"), new Options(false, OrphanPolicy.FAIL, Map.of()),
                List.of(), List.of(), Map.of(), Map.of(), issues, "fp");
    }

    @Test
    void planFailsOnBlockersAndOptionallyOnWarnings() {
        assertEquals(ExitCodes.OK, ExitCodes.plan(plan(List.of(new Issue(Level.INFO, null, "i"))), true));
        assertEquals(ExitCodes.FINDINGS, ExitCodes.plan(plan(List.of(new Issue(Level.BLOCKER, "T", "b"))), false));
        List<Issue> warn = List.of(new Issue(Level.WARNING, "T", "w"));
        assertEquals(ExitCodes.OK, ExitCodes.plan(plan(warn), false));
        assertEquals(ExitCodes.FINDINGS, ExitCodes.plan(plan(warn), true));
    }
}
