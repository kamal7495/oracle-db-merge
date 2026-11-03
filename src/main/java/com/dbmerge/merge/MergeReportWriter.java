package com.dbmerge.merge;

import static com.dbmerge.ddl.report.HtmlReportWriter.esc;

import com.dbmerge.merge.MergeModel.Issue;
import com.dbmerge.merge.MergeModel.MergePlan;
import com.dbmerge.merge.MergeModel.MergeResult;
import com.dbmerge.merge.MergeModel.PlannedRow;
import com.dbmerge.merge.MergeModel.RowAction;
import com.dbmerge.merge.MergeModel.TableStep;

/** HTML preview of a plan (dry run) and the final report of an executed run. */
public class MergeReportWriter {

    private static final int MAX_DETAIL_ROWS = 300;

    private static final String CSS = "body{font:14px system-ui,sans-serif;margin:24px;color:#1b1f23}table{border-collapse:collapse;margin:8px 0 20px}"
            + "th,td{border:1px solid #ccd;padding:4px 8px;text-align:left;vertical-align:top}th{background:#eef}.BLOCKER,.FAILED,.REFUSED,.bad{color:#b00020;font-weight:600}"
            + ".WARNING{color:#a05a00}.ok{color:#0a7a2f;font-weight:600}code{background:#f3f3f6;padding:1px 4px}";

    public String renderPlan(MergePlan p) {
        StringBuilder sb = head("Merge preview (dry run) " + p.id());
        sb.append("<p><b>Nothing has been changed.</b> Master <code>").append(esc(p.master())).append("</code>, sources ")
                .append(esc(String.join(", ", p.sources()))).append(". Plan fingerprint <code>").append(esc(p.fingerprint())).append("</code>.</p>");
        sb.append("<p class='WARNING'>").append(esc(ScriptRenderer.BACKUP_REMINDER)).append("</p>");
        sb.append(p.hasBlockers() ? "<p class='BLOCKER'>This plan has blockers and cannot be executed until they are resolved.</p>"
                : "<p class='ok'>No blockers. The plan can be executed after explicit confirmation.</p>");
        issues(sb, p);
        if (!p.alters().isEmpty()) {
            sb.append("<h2>Proposed schema changes (need approval; Oracle commits DDL immediately)</h2><table><tr><th>Id</th><th>SQL</th><th>Why</th><th>Required</th></tr>");
            p.alters().forEach(a -> sb.append("<tr><td>").append(esc(a.id())).append("</td><td><pre>").append(esc(a.sql())).append("</pre></td><td>")
                    .append(esc(a.reason())).append("</td><td>").append(a.requiredByPlan() ? "yes" : "no").append("</td></tr>"));
            sb.append("</table>");
        }
        sb.append("<h2>Tables in load order (parents first)</h2><table><tr><th>#</th><th>Table</th><th>Key</th><th>PK strategy</th><th>Master rows</th>");
        for (RowAction a : RowAction.values()) sb.append("<th>").append(a.name()).append("</th>");
        sb.append("</tr>");
        int i = 1;
        for (TableStep s : p.steps()) {
            sb.append("<tr><td>").append(i++).append("</td><td>").append(esc(s.table())).append("</td><td>").append(esc(String.join(", ", s.keyColumns())))
                    .append("</td><td>").append(s.pkMode()).append(s.sequence() == null ? "" : " (" + esc(s.sequence()) + ")").append("</td><td>")
                    .append(s.masterRowsAtPlan()).append("</td>");
            for (RowAction a : RowAction.values()) sb.append("<td>").append(s.count(a)).append("</td>");
            sb.append("</tr>");
        }
        sb.append("</table><h2>Rows that need attention</h2>");
        int shown = 0;
        sb.append("<table><tr><th>Table</th><th>Source</th><th>Action</th><th>Key</th><th>Detail</th></tr>");
        outer:
        for (TableStep s : p.steps()) {
            for (PlannedRow r : s.rows()) {
                if (r.action() == RowAction.INSERT || r.action() == RowAction.SKIP_DUPLICATE) continue;
                if (shown++ >= MAX_DETAIL_ROWS) { sb.append("<tr><td colspan=5>... more rows not shown; see the SQL script.</td></tr>"); break outer; }
                sb.append("<tr><td>").append(esc(s.table())).append("</td><td>").append(esc(r.sourceDb())).append("</td><td class='")
                        .append(r.action() == RowAction.UNRESOLVED_CONFLICT ? "bad" : "").append("'>").append(r.action()).append("</td><td>")
                        .append(esc(r.businessKey())).append("</td><td>").append(esc(r.note() == null ? "" : r.note())).append("</td></tr>");
            }
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    public String renderResult(MergePlan p, MergeResult r) {
        StringBuilder sb = head("Merge report " + p.id());
        sb.append("<p>Status: <span class='").append(r.status()).append("'>").append(r.status()).append("</span> - ").append(esc(r.message())).append("</p>");
        sb.append("<h2>Row counts per table</h2><table><tr><th>Table</th><th>Actions</th></tr>");
        r.countsByTable().forEach((t, m) -> sb.append("<tr><td>").append(esc(t)).append("</td><td>").append(esc(m.toString())).append("</td></tr>"));
        sb.append("</table><h2>Post-merge validation</h2><table><tr><th>Check</th><th>Result</th><th>Detail</th></tr>");
        r.validations().forEach(v -> sb.append("<tr><td>").append(esc(v.check())).append("</td><td class='").append(v.passed() ? "ok'>PASS" : "bad'>FAIL")
                .append("</td><td>").append(esc(v.detail())).append("</td></tr>"));
        sb.append("</table><h2>Action log</h2><pre>").append(esc(String.join("\n", r.log()))).append("</pre></body></html>");
        return sb.toString();
    }

    private static void issues(StringBuilder sb, MergePlan p) {
        if (p.issues().isEmpty()) return;
        sb.append("<h2>Issues</h2><table><tr><th>Level</th><th>Table</th><th>Message</th></tr>");
        for (Issue i : p.issues()) {
            sb.append("<tr><td class='").append(i.level()).append("'>").append(i.level()).append("</td><td>").append(esc(i.table())).append("</td><td>")
                    .append(esc(i.message())).append("</td></tr>");
        }
        sb.append("</table>");
    }

    private static StringBuilder head(String title) {
        return new StringBuilder("<!doctype html><html><head><meta charset='utf-8'><title>").append(esc(title)).append("</title><style>").append(CSS)
                .append("</style></head><body><h1>").append(esc(title)).append("</h1>");
    }
}
