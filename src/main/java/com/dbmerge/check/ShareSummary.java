package com.dbmerge.check;

import com.dbmerge.check.Preflight.Check;
import com.dbmerge.check.Preflight.ConnectionReport;
import com.dbmerge.check.Preflight.TableSize;
import com.dbmerge.data.DataModel.Conflict;
import com.dbmerge.data.DataModel.DataReport;
import com.dbmerge.data.DataModel.TableDataResult;
import com.dbmerge.ddl.compare.ComparisonResult;
import com.dbmerge.ddl.compare.Difference;
import com.dbmerge.merge.MergeModel.AlterStatement;
import com.dbmerge.merge.MergeModel.Issue;
import com.dbmerge.merge.MergeModel.MergePlan;
import com.dbmerge.merge.MergeModel.RowAction;
import com.dbmerge.merge.MergeModel.TableStep;
import com.dbmerge.refs.RefModel.Candidate;
import com.dbmerge.refs.RefModel.DataCheck;
import com.dbmerge.refs.RefModel.ReferenceReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The part of a report bundle that may leave the organisation: counts, schema object names, check results and
 * schema differences, and <b>no row values</b>. It is built field by field from the structured results, never by
 * copying report text: the planner's and the data comparison's messages can quote keys and values (orphan
 * examples, conversion errors), so they are reduced to a kind and a count. Free text that does pass (table status notes,
 * schema difference descriptions) loses its single-quoted literals ({@link #scrubQuotes}); error text also loses long
 * numbers ({@link #scrub}).
 *
 * <p>The Markdown is rendered from the same map as the JSON, so the two always carry the same information.
 */
public final class ShareSummary {

    /** Outcome of one bundle step. {@code error} is already scrubbed. */
    public record Step(String name, String status, long seconds, String error) { }

    /** Everything the summary is made of; any part may be null when its step was skipped or failed. */
    public record Input(String generatedAt, String toolVersion, Map<String, Object> options, List<Step> steps,
                        String master, List<String> sources, List<ConnectionReport> preflight, Estimate estimate,
                        ComparisonResult ddl, List<ReferenceReport> refs, DataReport data, MergePlan plan) { }

    private static final Pattern QUOTED = Pattern.compile("'[^']*'");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\b\\d{5,}\\b");
    private static final Pattern ORA = Pattern.compile("\\b(ORA|TNS)-\\d{5}\\b");
    private static final int MAX_LIST = 200;

    private ShareSummary() { }

    /** Removes single-quoted literals, which is how this tool's messages quote values. */
    public static String scrubQuotes(String s) {
        return s == null ? null : QUOTED.matcher(s).replaceAll("'…'");
    }

    /**
     * For error text, which may quote a key unpredictably: removes quoted literals and numbers of five digits or more
     * (ORA codes survive).
     */
    public static String scrub(String s) {
        if (s == null) return null;
        String out = scrubQuotes(s);
        StringBuilder b = new StringBuilder();
        Matcher m = LONG_NUMBER.matcher(out);
        int last = 0;
        while (m.find()) {
            boolean oraCode = m.start() >= 4 && ORA.matcher(out.substring(Math.max(0, m.start() - 4), m.end())).find();
            b.append(out, last, m.start()).append(oraCode ? m.group() : "#");
            last = m.end();
        }
        return b.append(out.substring(last)).toString();
    }

    /** Oracle error codes in a throwable, or its class name: an error description that cannot carry data. */
    public static String safeError(Throwable t) {
        List<String> codes = new ArrayList<>();
        for (Throwable c = t; c != null && codes.size() < 3; c = c.getCause() == c ? null : c.getCause()) {
            if (c.getMessage() == null) continue;
            Matcher m = ORA.matcher(c.getMessage());
            while (m.find()) if (!codes.contains(m.group())) codes.add(m.group());
        }
        return t.getClass().getSimpleName() + (codes.isEmpty() ? "" : " " + String.join(", ", codes));
    }

    /** What a planner issue is about, from its wording; the message itself is not copied because it can quote values. */
    static String issueKind(Issue i) {
        String m = i.message().toLowerCase(Locale.ROOT);
        if (m.contains("reference a parent row that does not exist")) return "orphan rows";
        if (m.contains("primary key collision")) return "primary key collision";
        if (m.contains("cycle")) return "reference cycle";
        if (m.contains("cannot be converted")) return "value does not fit the master column";
        if (m.contains("no primary key and no business key") || m.contains("lacks key column")) return "no usable key";
        if (m.startsWith("key column ")) return "key column type differs";
        if (m.contains("declare the foreign key")) return "undeclared reference";
        if (m.contains("large table")) return "large table, streamed";
        if (m.contains("self-referencing")) return "self-referencing table";
        if (m.contains("do not exist in the master yet")) return "new columns";
        if (m.contains("sequence")) return "sequence";
        if (m.contains("plan was stopped")) return "streaming stopped";
        if (m.contains("the values taken from")) return "taken values violate a constraint";
        if (m.contains("row(s)")) return "row constraint";
        return "other";
    }

    private static final Pattern LEADING_COUNT = Pattern.compile("^(\\d+) ");

    // ------------------------------------------------------------------ build

    public static Map<String, Object> build(Input in) {
        Map<String, Object> j = new LinkedHashMap<>();
        j.put("generatedAt", in.generatedAt());
        j.put("tool", "oracle-db-merge " + in.toolVersion());
        j.put("master", in.master());
        j.put("sources", in.sources());
        j.put("options", in.options());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Step s : in.steps()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("step", s.name());
            m.put("status", s.status());
            m.put("seconds", s.seconds());
            if (s.error() != null) m.put("error", s.error());
            steps.add(m);
        }
        j.put("steps", steps);
        if (in.preflight() != null) j.put("connections", connections(in.preflight()));
        if (in.estimate() != null) {
            Estimate e = in.estimate();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("dataComparisonSeconds", e.dataSeconds());
            m.put("dryRunSeconds", e.planSeconds());
            m.put("planFileBytesAtMost", e.planFileBytes());
            m.put("freeBytes", e.freeBytes());
            m.put("checks", checks(e.checks()));
            m.put("basis", "perf kit rates on a laptop; a real server may differ several times");
            j.put("estimate", m);
        }
        if (in.ddl() != null) j.put("schemaDifferences", ddl(in.ddl()));
        if (in.refs() != null) j.put("undeclaredReferences", refs(in.refs()));
        if (in.data() != null) j.put("dataComparison", data(in.data()));
        if (in.plan() != null) j.put("dryRun", plan(in.plan()));
        return j;
    }

    private static List<Map<String, Object>> checks(List<Check> checks) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Check c : checks) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("check", c.name());
            m.put("category", c.category().name());
            m.put("status", c.status().name());
            m.put("detail", c.detail());
            out.add(m);
        }
        return out;
    }

    private static List<Map<String, Object>> connections(List<ConnectionReport> reports) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConnectionReport r : reports) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nickname", r.nickname());
            m.put("schema", r.schema());
            m.put("status", r.status().name());
            if (r.error() != null) {
                m.put("error", scrub(r.error()));
                out.add(m);
                continue;
            }
            m.put("connectedAs", r.sessionUser());
            m.put("database", r.dbName());
            m.put("container", r.container());
            m.put("role", r.databaseRole());
            m.put("version", r.version());
            m.put("dictionary", r.owner() ? "owner" : r.dbaDictionary() ? "DBA_*" : "ALL_* (incomplete)");
            m.put("checks", checks(r.checks()));
            m.put("tables", r.tables());
            m.put("views", r.views());
            m.put("plsqlUnits", r.plsqlUnits());
            m.put("triggers", r.triggers());
            m.put("rowsByStatistics", r.totalRows());
            m.put("bytesByStatistics", r.totalBytes());
            m.put("tablesWithoutStatistics", r.tablesWithoutStats());
            m.put("tablesWithoutKey", r.tablesWithoutKey());
            m.put("tablesWithLobs", r.lobTables());
            m.put("partitionedTables", r.partitionedTables());
            m.put("temporaryTables", r.temporaryTables());
            List<Map<String, Object>> largest = new ArrayList<>();
            for (TableSize t : r.largest()) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("table", t.table());
                tm.put("rows", t.rows());
                tm.put("bytes", t.bytes());
                tm.put("lastAnalyzed", t.lastAnalyzed() == null ? null : t.lastAnalyzed().toString().substring(0, 10));
                List<String> flags = new ArrayList<>();
                if (!t.hasKey()) flags.add("no key");
                if (t.hasLob()) flags.add("LOB");
                if (t.partitioned()) flags.add("partitioned");
                tm.put("flags", flags);
                largest.add(tm);
            }
            m.put("largestTables", largest);
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> ddl(ComparisonResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("databases", r.databases());
        Map<String, Integer> sev = new LinkedHashMap<>();
        r.countsBySeverity().forEach((k, v) -> sev.put(k.name(), v));
        m.put("bySeverity", sev);
        Map<String, Integer> kinds = new TreeMap<>();
        for (Difference d : r.differences()) kinds.merge(d.kind().label(), 1, Integer::sum);
        m.put("byKind", kinds);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Difference d : r.differences()) {
            if (list.size() == MAX_LIST) break;
            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("severity", d.severity().name());
            dm.put("kind", d.kind().label());
            dm.put("table", d.table());
            dm.put("object", d.object());
            dm.put("description", scrubQuotes(d.description()));
            list.add(dm);
        }
        m.put("differences", list);
        m.put("listed", list.size() + " of " + r.differences().size());
        Map<String, Integer> warnings = new LinkedHashMap<>();
        r.models().forEach(x -> warnings.put(x.nickname(), x.warnings().size()));
        m.put("extractionWarnings", warnings);
        return m;
    }

    private static List<Map<String, Object>> refs(List<ReferenceReport> reports) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ReferenceReport r : reports) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nickname", r.nickname());
            m.put("tables", r.tableCount());
            m.put("declaredForeignKeys", r.declaredForeignKeys());
            m.put("dataChecked", r.dataChecked());
            List<Map<String, Object>> cands = new ArrayList<>();
            for (Candidate c : r.candidates()) {
                if (cands.size() == MAX_LIST) break;
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("column", c.qualifiedColumn());
                cm.put("kind", c.kind().name());
                cm.put("confidence", c.confidence().name());
                cm.put("parents", c.parentTables());
                cm.put("rule", scrubQuotes(c.rule()));
                List<Map<String, Object>> checks = new ArrayList<>();
                for (DataCheck d : c.dataChecks()) {
                    Map<String, Object> dm = new LinkedHashMap<>();
                    dm.put("parent", d.parentTable());
                    dm.put("values", d.nonNullValues());
                    dm.put("unresolved", d.unresolvedValues());  // counts only: the examples are values
                    if (d.error() != null) dm.put("error", "check failed");
                    checks.add(dm);
                }
                if (!checks.isEmpty()) cm.put("dataChecks", checks);
                cands.add(cm);
            }
            m.put("candidates", cands);
            m.put("listed", cands.size() + " of " + r.candidates().size());
            m.put("warnings", r.warnings().size());
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> data(DataReport r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("databases", r.databases());
        List<Map<String, Object>> tables = new ArrayList<>();
        Map<String, Integer> byStatus = new TreeMap<>();
        for (TableDataResult t : r.tables()) {
            byStatus.merge(t.status().name(), 1, Integer::sum);
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("table", t.table());
            tm.put("status", t.status().name());
            if (t.message() != null) tm.put("note", t.status() == com.dbmerge.data.DataModel.Status.ERROR ? scrub(t.message()) : scrubQuotes(t.message()));
            tm.put("key", t.keyColumns());
            tm.put("rows", t.rowCounts());
            tm.put("onlyIn", t.onlyInCounts());
            tm.put("identical", t.identicalCount());
            tm.put("conflicts", t.conflictCount());
            tm.put("pkCollisions", t.pkCollisionCount());
            tm.put("samePkDifferentKey", t.pkDivergenceCount());
            Map<String, Integer> cols = new TreeMap<>();
            for (Conflict c : t.conflicts()) for (String col : c.differingColumns()) cols.merge(col, 1, Integer::sum);
            if (!cols.isEmpty()) {
                tm.put("differingColumnsInSamples", cols);
                tm.put("samples", t.conflicts().size());
            }
            if (!t.uncomparedColumns().isEmpty()) tm.put("uncomparedColumns", t.uncomparedColumns());
            tables.add(tm);
        }
        m.put("byStatus", byStatus);
        m.put("tables", tables);
        return m;
    }

    private static Map<String, Object> plan(MergePlan p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("master", p.master());
        m.put("sources", p.sources());
        m.put("hasBlockers", p.hasBlockers());
        List<Map<String, Object>> steps = new ArrayList<>();
        Map<String, Long> totals = new LinkedHashMap<>();
        for (TableStep s : p.steps()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("table", s.table());
            Map<String, Long> counts = new LinkedHashMap<>();
            for (RowAction a : RowAction.values()) {
                long n = s.count(a);
                if (n > 0) {
                    counts.put(a.name(), n);
                    totals.merge(a.name(), n, Long::sum);
                }
            }
            sm.put("rows", counts);
            steps.add(sm);
        }
        m.put("totals", totals);
        m.put("tables", steps);
        // Issues grouped by table, level and kind; message text is not copied (it can quote keys and values).
        Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
        for (Issue i : p.issues()) {
            String kind = issueKind(i);
            String id = i.level() + "|" + i.table() + "|" + kind;
            Map<String, Object> g = groups.computeIfAbsent(id, k -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("level", i.level().name());
                x.put("table", i.table());
                x.put("kind", kind);
                x.put("issues", 0);
                return x;
            });
            g.put("issues", (Integer) g.get("issues") + 1);
            Matcher n = LEADING_COUNT.matcher(i.message());
            if (n.find()) g.merge("rows", Long.parseLong(n.group(1)), (a, b) -> (Long) a + (Long) b);
        }
        m.put("issues", new ArrayList<>(groups.values()));
        List<Map<String, Object>> alters = new ArrayList<>();
        for (AlterStatement a : p.alters()) {
            Map<String, Object> am = new LinkedHashMap<>();
            am.put("kind", a.kind().name());
            am.put("table", a.table());
            am.put("requiredByPlan", a.requiredByPlan());
            alters.add(am);
        }
        m.put("schemaChangesProposed", alters);
        return m;
    }

    // ----------------------------------------------------------------- render

    @SuppressWarnings("unchecked")
    public static String markdown(Map<String, Object> j) {
        StringBuilder b = new StringBuilder();
        b.append("# oracle-db-merge report bundle\n\n");
        b.append("Generated ").append(j.get("generatedAt")).append(" by ").append(j.get("tool")).append(".  \n");
        b.append("Master **").append(j.get("master")).append("**, sources **").append(String.join(", ", (List<String>) j.get("sources"))).append("**.\n\n");
        b.append("This summary has counts, names of schema objects and schema differences only: no row values, no keys,"
                + " no host names. The full reports with row values are in `../private/`.\n\n");

        b.append("## Steps\n\n| Step | Status | Time | Error |\n|---|---|---|---|\n");
        for (Map<String, Object> s : (List<Map<String, Object>>) j.get("steps")) {
            b.append("| ").append(s.get("step")).append(" | ").append(s.get("status")).append(" | ")
                    .append(Estimate.duration(((Number) s.get("seconds")).longValue())).append(" | ")
                    .append(s.get("error") == null ? "" : cell(s.get("error"))).append(" |\n");
        }
        b.append('\n');

        if (j.containsKey("connections")) {
            b.append("## Read-only check\n\n");
            for (Map<String, Object> c : (List<Map<String, Object>>) j.get("connections")) {
                b.append("### ").append(c.get("nickname")).append(": ").append(c.get("status")).append("\n\n");
                if (c.containsKey("error")) {
                    b.append(c.get("error")).append("\n\n");
                    continue;
                }
                b.append("Schema ").append(c.get("schema")).append(", connected as ").append(c.get("connectedAs"))
                        .append(", database ").append(c.get("database")).append(" / ").append(c.get("container"))
                        .append(" (").append(c.get("role")).append(", ").append(c.get("version")).append("). Dictionary: ")
                        .append(c.get("dictionary")).append(".\n\n");
                b.append("| Check | Result | Detail |\n|---|---|---|\n");
                for (Map<String, Object> ch : (List<Map<String, Object>>) c.get("checks")) {
                    b.append("| ").append(ch.get("check")).append(" | ").append(ch.get("status")).append(" | ").append(cell(ch.get("detail"))).append(" |\n");
                }
                b.append("\n").append(c.get("tables")).append(" tables, ").append(c.get("views")).append(" views, ")
                        .append(c.get("plsqlUnits")).append(" PL/SQL units, ").append(c.get("triggers")).append(" triggers; about ")
                        .append(String.format(Locale.ROOT, "%,d", ((Number) c.get("rowsByStatistics")).longValue())).append(" rows, ")
                        .append(Estimate.bytes(((Number) c.get("bytesByStatistics")).longValue())).append(" (optimizer statistics). ")
                        .append(c.get("tablesWithoutStatistics")).append(" without statistics, ").append(c.get("tablesWithoutKey"))
                        .append(" without a key, ").append(c.get("tablesWithLobs")).append(" with LOBs, ").append(c.get("partitionedTables"))
                        .append(" partitioned, ").append(c.get("temporaryTables")).append(" temporary.\n\n");
                b.append("| Largest tables | Rows | Size | Analyzed | Notes |\n|---|---|---|---|---|\n");
                for (Map<String, Object> t : (List<Map<String, Object>>) c.get("largestTables")) {
                    Object rows = t.get("rows");
                    b.append("| ").append(t.get("table")).append(" | ")
                            .append(rows == null ? "?" : String.format(Locale.ROOT, "%,d", ((Number) rows).longValue())).append(" | ")
                            .append(Estimate.bytes(((Number) t.get("bytes")).longValue())).append(" | ")
                            .append(t.get("lastAnalyzed") == null ? "never" : t.get("lastAnalyzed")).append(" | ")
                            .append(String.join(", ", (List<String>) t.get("flags"))).append(" |\n");
                }
                b.append('\n');
            }
        }
        if (j.containsKey("estimate")) {
            Map<String, Object> e = (Map<String, Object>) j.get("estimate");
            b.append("## Estimates\n\nData comparison about ").append(Estimate.duration(((Number) e.get("dataComparisonSeconds")).longValue()))
                    .append(", dry run about ").append(Estimate.duration(((Number) e.get("dryRunSeconds")).longValue()))
                    .append(", plan files up to ").append(Estimate.bytes(((Number) e.get("planFileBytesAtMost")).longValue()))
                    .append(" (").append(e.get("basis")).append(").\n\n");
            for (Map<String, Object> ch : (List<Map<String, Object>>) e.get("checks")) {
                b.append("* ").append(ch.get("check")).append(": ").append(ch.get("status")).append(", ").append(ch.get("detail")).append('\n');
            }
            b.append('\n');
        }
        if (j.containsKey("schemaDifferences")) {
            Map<String, Object> d = (Map<String, Object>) j.get("schemaDifferences");
            b.append("## Schema differences\n\n").append(d.get("bySeverity")).append(", ").append(d.get("listed")).append(" listed.");
            b.append(" Extraction warnings per database: ").append(d.get("extractionWarnings")).append(".\n\n");
            b.append("| Severity | Kind | Object | Description |\n|---|---|---|---|\n");
            for (Map<String, Object> x : (List<Map<String, Object>>) d.get("differences")) {
                b.append("| ").append(x.get("severity")).append(" | ").append(x.get("kind")).append(" | ")
                        .append(x.get("object") != null ? x.get("object") : x.get("table")).append(" | ").append(cell(x.get("description"))).append(" |\n");
            }
            b.append('\n');
        }
        if (j.containsKey("undeclaredReferences")) {
            b.append("## Undeclared references\n\n");
            for (Map<String, Object> r : (List<Map<String, Object>>) j.get("undeclaredReferences")) {
                b.append("### ").append(r.get("nickname")).append("\n\n").append(r.get("tables")).append(" tables, ")
                        .append(r.get("declaredForeignKeys")).append(" declared foreign keys, ").append(r.get("listed"))
                        .append(" candidates listed").append(Boolean.TRUE.equals(r.get("dataChecked")) ? ", data checked" : "").append(".\n\n");
                b.append("| Column | Confidence | Points at | Resolves |\n|---|---|---|---|\n");
                for (Map<String, Object> c : (List<Map<String, Object>>) r.get("candidates")) {
                    List<String> parents = (List<String>) c.get("parents");
                    String res = "";
                    if (c.containsKey("dataChecks")) {
                        List<String> parts = new ArrayList<>();
                        for (Map<String, Object> dc : (List<Map<String, Object>>) c.get("dataChecks")) {
                            long v = ((Number) dc.get("values")).longValue(), u = ((Number) dc.get("unresolved")).longValue();
                            parts.add(dc.containsKey("error") ? "check failed"
                                    : v == 0 ? "no values" : String.format(Locale.ROOT, "%s %.1f%% of %,d", dc.get("parent"), 100.0 * (v - u) / v, v));
                        }
                        res = String.join("; ", parts);
                    }
                    b.append("| ").append(c.get("column")).append(" | ").append(c.get("confidence")).append(" | ")
                            .append(parents.isEmpty() ? c.get("kind") : String.join(", ", parents)).append(" | ").append(res).append(" |\n");
                }
                b.append('\n');
            }
        }
        if (j.containsKey("dataComparison")) {
            Map<String, Object> d = (Map<String, Object>) j.get("dataComparison");
            b.append("## Data comparison\n\nTables by status: ").append(d.get("byStatus")).append(".\n\n");
            b.append("| Table | Status | Rows | Only in | Conflicts | PK collisions | Same PK, other key | Columns differing (samples) |\n|---|---|---|---|---|---|---|---|\n");
            for (Map<String, Object> t : (List<Map<String, Object>>) d.get("tables")) {
                boolean same = "COMPARED".equals(t.get("status")) && ((Number) t.get("conflicts")).longValue() == 0
                        && ((Number) t.get("pkCollisions")).longValue() == 0 && ((Number) t.get("samePkDifferentKey")).longValue() == 0
                        && ((Map<String, Number>) t.get("onlyIn")).values().stream().allMatch(n -> n.longValue() == 0);
                if (same) continue; // identical tables are only counted
                b.append("| ").append(t.get("table")).append(" | ").append(t.get("status"))
                        .append(t.containsKey("note") ? ": " + cell(t.get("note")) : "").append(" | ").append(t.get("rows")).append(" | ")
                        .append(t.get("onlyIn")).append(" | ").append(t.get("conflicts")).append(" | ").append(t.get("pkCollisions")).append(" | ")
                        .append(t.get("samePkDifferentKey")).append(" | ").append(t.containsKey("differingColumnsInSamples") ? t.get("differingColumnsInSamples") : "")
                        .append(" |\n");
            }
            b.append("\nTables not listed are identical in every database.\n\n");
        }
        if (j.containsKey("dryRun")) {
            Map<String, Object> p = (Map<String, Object>) j.get("dryRun");
            b.append("## Merge dry run (nothing was written)\n\n").append(Boolean.TRUE.equals(p.get("hasBlockers")) ? "**Has blockers.** " : "No blockers. ")
                    .append("Rows: ").append(p.get("totals")).append(".\n\n");
            b.append("| Level | Table | Kind | Issues | Rows |\n|---|---|---|---|---|\n");
            for (Map<String, Object> i : (List<Map<String, Object>>) p.get("issues")) {
                b.append("| ").append(i.get("level")).append(" | ").append(i.get("table") == null ? "" : i.get("table")).append(" | ")
                        .append(i.get("kind")).append(" | ").append(i.get("issues")).append(" | ").append(i.get("rows") == null ? "" : i.get("rows")).append(" |\n");
            }
            b.append("\n| Table | Rows by action |\n|---|---|\n");
            for (Map<String, Object> t : (List<Map<String, Object>>) p.get("tables")) {
                b.append("| ").append(t.get("table")).append(" | ").append(t.get("rows")).append(" |\n");
            }
            List<Map<String, Object>> alters = (List<Map<String, Object>>) p.get("schemaChangesProposed");
            if (!alters.isEmpty()) {
                b.append("\nSchema changes proposed: ").append(alters.size()).append(".\n");
            }
            b.append('\n');
        }
        return b.toString();
    }

    private static String cell(Object o) {
        return o == null ? "" : o.toString().replace("|", "\\|").replace("\n", " ");
    }
}
