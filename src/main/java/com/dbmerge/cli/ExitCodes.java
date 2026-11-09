package com.dbmerge.cli;

import com.dbmerge.data.DataModel.Status;
import com.dbmerge.data.DataModel.TableDataResult;
import com.dbmerge.ddl.compare.Severity;
import com.dbmerge.merge.MergeModel.Level;
import com.dbmerge.merge.MergeModel.MergePlan;
import com.dbmerge.refs.RefModel.Confidence;
import java.util.List;
import java.util.Map;

/**
 * Exit codes of the command line and how each command's result maps to them.
 *
 * <ul>
 *   <li>{@value #OK}: nothing at or above the threshold</li>
 *   <li>{@value #FINDINGS}: differences / findings / blockers at or above the threshold</li>
 *   <li>{@value #USAGE}: bad arguments, unknown or locked connection, missing environment variable</li>
 *   <li>{@value #RUNTIME}: connection or runtime error (database unreachable, query failed, report not written)</li>
 * </ul>
 */
public final class ExitCodes {

    public static final int OK = 0;
    public static final int FINDINGS = 1;
    public static final int USAGE = 2;
    public static final int RUNTIME = 3;

    /** Lowest severity or confidence that fails the run; NEVER only reports. */
    public enum Threshold { CRITICAL, HIGH, MEDIUM, LOW, NEVER }

    private ExitCodes() { }

    /** ddl: fails when any difference is at or above the threshold severity. */
    public static int ddl(Map<Severity, Integer> counts, Threshold threshold) {
        if (threshold == Threshold.NEVER) return OK;
        Severity limit = Severity.valueOf(threshold.name());
        for (Map.Entry<Severity, Integer> e : counts.entrySet()) {
            if (e.getKey().ordinal() <= limit.ordinal() && e.getValue() != null && e.getValue() > 0) return FINDINGS;
        }
        return OK;
    }

    /**
     * data: fails on any difference: rows only in some databases, conflicts, primary-key collisions or divergence,
     * different row counts, or a table present in only one database. Tables that could not be compared (no key,
     * too large, error) also fail unless {@code allowUncompared}, so a CI run never passes on data it never read.
     */
    public static int data(List<TableDataResult> tables, boolean allowUncompared) {
        for (TableDataResult t : tables) {
            if (differs(t)) return FINDINGS;
            if (!allowUncompared && uncompared(t)) return FINDINGS;
        }
        return OK;
    }

    static boolean differs(TableDataResult t) {
        if (t.status() == Status.SINGLE_DATABASE) return true;
        if (t.rowCounts() != null && t.rowCounts().values().stream().distinct().count() > 1) return true;
        return t.onlyInTotal() > 0 || t.conflictCount() > 0 || t.pkCollisionCount() > 0 || t.pkDivergenceCount() > 0;
    }

    static boolean uncompared(TableDataResult t) {
        return t.status() == Status.NO_KEY || t.status() == Status.TOO_LARGE || t.status() == Status.ERROR;
    }

    /** refs: fails when any candidate has at least the threshold confidence. */
    public static int refs(Map<Confidence, Long> counts, Threshold threshold) {
        if (threshold == Threshold.NEVER) return OK;
        Confidence limit = Confidence.valueOf(threshold.name());
        for (Map.Entry<Confidence, Long> e : counts.entrySet()) {
            if (e.getKey().ordinal() <= limit.ordinal() && e.getValue() != null && e.getValue() > 0) return FINDINGS;
        }
        return OK;
    }

    /** plan: fails on any blocker (including unresolved conflicts), and on warnings when asked. */
    public static int plan(MergePlan plan, boolean failOnWarnings) {
        if (plan.hasBlockers()) return FINDINGS;
        if (failOnWarnings && plan.issues().stream().anyMatch(i -> i.level() == Level.WARNING)) return FINDINGS;
        return OK;
    }
}
