package com.dbmerge.refs;

import static com.dbmerge.ddl.report.HtmlReportWriter.esc;

import com.dbmerge.refs.RefModel.Candidate;
import com.dbmerge.refs.RefModel.Confidence;
import com.dbmerge.refs.RefModel.DataCheck;
import com.dbmerge.refs.RefModel.Kind;
import com.dbmerge.refs.RefModel.ReferenceReport;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Self-contained HTML report of undeclared references. All database-derived text is escaped. */
public class ReferenceHtmlReportWriter {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    public String render(ReferenceReport r) {
        StringBuilder h = new StringBuilder(32 * 1024);
        h.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Undeclared references ")
                .append(esc(r.id())).append("</title><style>").append(CSS).append("</style></head><body>");
        h.append("<h1>Undeclared references</h1><p class=\"muted\">Run ").append(esc(r.id())).append(" &middot; ")
                .append(esc(TS.format(r.generatedAt().atZone(ZoneId.systemDefault())))).append(" &middot; ")
                .append(esc(r.nickname())).append(" (schema ").append(esc(r.owner())).append(")")
                .append(r.dataChecked() ? " &middot; checked against the data" : " &middot; names and types only (data not checked)")
                .append("</p>");

        h.append("<div class=\"why\"><strong>Why this matters for merging.</strong> The merge rewrites a child's reference to a ")
                .append("re-keyed parent only through <em>declared</em> foreign keys. Every column listed here would keep its old value ")
                .append("if its parent row got a new key, silently pointing at the wrong row or at nothing. Before merging this schema, ")
                .append("each HIGH/MEDIUM finding needs a decision: declare it on a copy, exclude the tables, or merge through the application.</div>");

        h.append("<h2>Summary</h2><table class=\"narrow\"><tbody>")
                .append(row("Tables", r.tableCount()))
                .append(row("Declared foreign keys", r.declaredForeignKeys() + " (covering " + r.declaredForeignKeyColumns() + " column(s))"))
                .append(row("Likely foreign keys, not declared", r.count(Kind.LIKELY_FK)))
                .append(row("Shared primary keys (1:1 tables), not declared", r.count(Kind.SHARED_PRIMARY_KEY)))
                .append(row("Type + ID pairs (cannot be declared)", r.count(Kind.POLYMORPHIC)))
                .append(row("ID-like columns with no matching table", r.count(Kind.UNRESOLVED_ID)))
                .append("</tbody></table><p>");
        for (Confidence c : Confidence.values()) {
            h.append("<span class=\"badge ").append(c).append("\">").append(c).append(": ").append(r.count(c)).append("</span> ");
        }
        h.append("</p>");

        section(h, r, "Likely foreign keys", List.of(Kind.LIKELY_FK, Kind.SHARED_PRIMARY_KEY), true);
        section(h, r, "Type + ID pairs", List.of(Kind.POLYMORPHIC), false);
        section(h, r, "ID-like columns with no matching table", List.of(Kind.UNRESOLVED_ID), false);

        if (!r.warnings().isEmpty()) {
            h.append("<h2>Warnings</h2><ul>");
            for (String w : r.warnings()) h.append("<li>").append(esc(w)).append("</li>");
            h.append("</ul>");
        }
        h.append("<p class=\"muted\">Detection is a heuristic over names, types and keys. A HIGH finding can still be a coincidence and ")
                .append("a real reference can be missed (for example IDs held in BLOBs or XML, or columns named unlike their table). ")
                .append(r.dataChecked() ? "The data check counts values only; it does not prove the relationship." : "Run with the data check to see how many values actually resolve.")
                .append("</p></body></html>");
        return h.toString();
    }

    private static void section(StringBuilder h, ReferenceReport r, String title, List<Kind> kinds, boolean withParent) {
        List<Candidate> list = r.candidates().stream().filter(c -> kinds.contains(c.kind())).toList();
        h.append("<h2>").append(esc(title)).append(" (").append(list.size()).append(")</h2>");
        if (list.isEmpty()) {
            h.append("<p class=\"good\">None found.</p>");
            return;
        }
        h.append("<table><thead><tr><th>Confidence</th><th>Column</th><th>Type</th>");
        if (withParent) h.append("<th>Likely parent</th>");
        h.append("<th>Why</th>");
        if (r.dataChecked() && withParent) h.append("<th>Data check</th>");
        h.append("<th>Notes</th><th>Indexed</th></tr></thead><tbody>");
        for (Candidate c : list) {
            h.append("<tr><td><span class=\"badge ").append(c.confidence()).append("\">").append(c.confidence()).append("</span>");
            if (c.kind() == Kind.SHARED_PRIMARY_KEY) h.append("<br><small>shared PK</small>");
            h.append("</td><td><code>").append(esc(c.qualifiedColumn())).append("</code></td><td>").append(esc(c.columnType())).append("</td>");
            if (withParent) {
                h.append("<td>");
                for (String p : c.parentTables()) {
                    h.append("<code>").append(esc(p)).append(c.parentColumn() == null ? "" : "." + esc(c.parentColumn())).append("</code><br>");
                }
                h.append("</td>");
            }
            h.append("<td>").append(esc(c.rule())).append("</td>");
            if (r.dataChecked() && withParent) h.append("<td>").append(checks(c)).append("</td>");
            h.append("<td>");
            for (String n : c.notes()) h.append(esc(n)).append("<br>");
            h.append("</td><td>").append(c.indexed() ? "yes" : "<span class=\"muted\">no</span>").append("</td></tr>");
        }
        h.append("</tbody></table>");
    }

    private static String checks(Candidate c) {
        if (c.dataChecks().isEmpty()) return "<span class=\"muted\">not checked</span>";
        StringBuilder b = new StringBuilder();
        for (DataCheck d : c.dataChecks()) {
            if (c.dataChecks().size() > 1) b.append("<code>").append(esc(d.parentTable())).append("</code>: ");
            if (!d.ran()) {
                b.append("<span class=\"err\">").append(esc(d.error())).append("</span><br>");
                continue;
            }
            Double share = d.resolvedShare();
            if (share == null) {
                b.append("<span class=\"muted\">no values</span><br>");
                continue;
            }
            String cls = d.unresolvedValues() == 0 ? "good" : share >= 0.95 ? "warn" : "err";
            b.append("<span class=\"").append(cls).append("\">")
                    .append(String.format(Locale.ROOT, "%.1f%%", share * 100)).append(" of ").append(d.nonNullValues()).append(" resolve</span>");
            if (d.unresolvedValues() > 0) {
                b.append("<br><small>").append(d.unresolvedValues()).append(" without parent");
                if (!d.unresolvedExamples().isEmpty()) b.append(", e.g. ").append(esc(String.join(", ", d.unresolvedExamples())));
                b.append("</small>");
            }
            b.append("<br>");
        }
        return b.toString();
    }

    private static String row(String label, Object value) {
        return "<tr><th>" + esc(label) + "</th><td>" + esc(String.valueOf(value)) + "</td></tr>";
    }

    private static final String CSS = """
            body{font:14px/1.4 system-ui,sans-serif;margin:24px;color:#1b1f24}
            h1{margin:0 0 4px}h2{margin-top:28px;border-bottom:1px solid #d0d7de;padding-bottom:4px}
            table{border-collapse:collapse;margin:8px 0;width:100%}table.narrow{width:auto}
            th,td{border:1px solid #d0d7de;padding:4px 8px;text-align:left;vertical-align:top;font-size:13px}
            th{background:#f6f8fa}code{font-size:12px}.muted{color:#57606a}.good{color:#1a7f37}.err{color:#cf222e}.warn{color:#9a6700}
            .badge{border-radius:10px;padding:1px 8px;font-size:12px;font-weight:600}
            .HIGH{background:#ffd8d3;color:#82071e}.MEDIUM{background:#fff2b3;color:#5c4400}.LOW{background:#dff6dd;color:#0f5323}
            .why{background:#fff8c5;border:1px solid #d4a72c;border-radius:6px;padding:8px 12px;margin:12px 0}
            """;
}
