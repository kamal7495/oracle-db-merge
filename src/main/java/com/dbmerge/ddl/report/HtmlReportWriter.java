package com.dbmerge.ddl.report;

import com.dbmerge.ddl.compare.ComparisonResult;
import com.dbmerge.ddl.compare.Difference;
import com.dbmerge.ddl.compare.SchemaComparator;
import com.dbmerge.ddl.compare.Severity;
import com.dbmerge.ddl.model.ColumnDef;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.TableDef;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Self-contained HTML report (inline CSS/JS, no external resources). All database-derived text is escaped. */
public class HtmlReportWriter {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    public String render(ComparisonResult r) {
        StringBuilder h = new StringBuilder(64 * 1024);
        h.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">")
                .append("<title>DDL comparison ").append(esc(r.id())).append("</title><style>").append(CSS).append("</style></head><body>");
        h.append("<h1>Oracle DDL comparison</h1><p class=\"muted\">Run ").append(esc(r.id())).append(" &middot; ")
                .append(esc(TS.format(r.generatedAt().atZone(ZoneId.systemDefault()))))
                .append(" &middot; databases compared with each other: ").append(esc(String.join(", ", r.databases())))
                .append("</p>");

        databases(h, r);
        summary(h, r);
        tableMatrix(h, r);
        differences(h, r);
        tableDetails(h, r);
        warnings(h, r);

        h.append("<script>").append(JS).append("</script></body></html>");
        return h.toString();
    }

    // ---------------------------------------------------------------- sections

    private void databases(StringBuilder h, ComparisonResult r) {
        h.append("<h2>Databases</h2><table><tr><th>Nickname</th><th>Schema</th><th>Tables</th><th>Columns</th><th>Sequences</th><th>Extraction warnings</th></tr>");
        for (SchemaModel m : r.models()) {
            h.append("<tr><td>").append(esc(m.nickname())).append("</td><td>").append(esc(m.owner())).append("</td><td>")
                    .append(m.tables().size()).append("</td><td>").append(m.columnCount()).append("</td><td>")
                    .append(m.sequences().size()).append("</td><td>").append(m.warnings().size()).append("</td></tr>");
        }
        h.append("</table>");
    }

    private void summary(StringBuilder h, ComparisonResult r) {
        h.append("<h2>Summary</h2><p>");
        Map<Severity, Integer> counts = r.countsBySeverity();
        for (Severity s : Severity.values()) {
            h.append("<label class=\"chip sev-").append(s.name().toLowerCase()).append("\"><input type=\"checkbox\" checked data-sev=\"")
                    .append(s.name()).append("\"> ").append(s.name()).append(": ").append(counts.get(s)).append("</label> ");
        }
        h.append("<input id=\"q\" type=\"search\" placeholder=\"filter findings…\"></p>");
        if (r.differences().isEmpty()) {
            h.append("<p class=\"ok\">No structural differences found between the compared databases.</p>");
        }
        h.append("<p class=\"muted\">Severity = how likely the difference is to block or corrupt a merge, judged without knowing which database "
                + "will be the master. CRITICAL: incompatible data types or a different/missing primary key. HIGH: missing table, "
                + "length/precision mismatch, missing foreign/unique key, NOT NULL column with no default. MEDIUM: nullability, "
                + "check constraints, triggers, sequences, compatible type changes. LOW: names, defaults, enabled/disabled flags.</p>");
    }

    private void tableMatrix(StringBuilder h, ComparisonResult r) {
        Set<String> all = new TreeSet<>();
        for (SchemaModel m : r.models()) all.addAll(m.tables().keySet());
        Map<String, Severity> worst = r.worstSeverityByTable();
        h.append("<h2>Table presence matrix</h2><table><tr><th>Table</th>");
        for (String db : r.databases()) h.append("<th>").append(esc(db)).append("</th>");
        h.append("<th>Findings</th></tr>");
        for (String t : all) {
            h.append("<tr>").append("<td>").append(esc(t)).append("</td>");
            for (SchemaModel m : r.models()) {
                TableDef td = m.tables().get(t);
                h.append(td == null ? "<td class=\"miss\">missing</td>" : "<td class=\"same\">" + td.columns().size() + " cols</td>");
            }
            Severity w = worst.get(t);
            h.append(w == null ? "<td class=\"ok\">identical</td>"
                    : "<td><span class=\"badge sev-" + w.name().toLowerCase() + "\">" + w.name() + "</span></td>");
            h.append("</tr>");
        }
        h.append("</table>");
    }

    private void differences(StringBuilder h, ComparisonResult r) {
        h.append("<h2>Differences (side by side)</h2>");
        if (r.differences().isEmpty()) { h.append("<p>None.</p>"); return; }
        h.append("<table id=\"diffs\"><tr><th>Severity</th><th>Kind</th><th>Table</th><th>Object</th>");
        for (String db : r.databases()) h.append("<th>").append(esc(db)).append("</th>");
        h.append("<th>Why it matters</th></tr>");
        for (Difference d : r.differences()) {
            h.append("<tr data-sev=\"").append(d.severity().name()).append("\"><td><span class=\"badge sev-")
                    .append(d.severity().name().toLowerCase()).append("\">").append(d.severity().name()).append("</span></td><td>")
                    .append(esc(d.kind().label())).append("</td><td>").append(esc(d.table() == null ? "" : d.table()))
                    .append("</td><td>").append(esc(d.object())).append("</td>");
            List<String> distinct = new ArrayList<>(new LinkedHashSet<>(d.values().values()));
            for (String db : r.databases()) {
                String v = d.values().get(db);
                boolean missing = SchemaComparator.MISSING.equals(v) || SchemaComparator.NO_TABLE.equals(v);
                h.append("<td class=\"").append(missing ? "miss" : "v" + (distinct.indexOf(v) % 4)).append("\">")
                        .append(esc(v)).append("</td>");
            }
            h.append("<td>").append(esc(d.description())).append("</td></tr>");
        }
        h.append("</table>");
    }

    private void tableDetails(StringBuilder h, ComparisonResult r) {
        Map<String, Severity> worst = r.worstSeverityByTable();
        if (worst.isEmpty()) return;
        h.append("<h2>Tables with differences: columns and DDL</h2>");
        for (var e : worst.entrySet()) {
            String table = e.getKey();
            long n = r.differences().stream().filter(d -> table.equals(d.table())).count();
            h.append("<details><summary>").append(esc(table)).append(" &mdash; ").append(n).append(" finding(s), worst ")
                    .append(e.getValue().name()).append("</summary>");
            Set<String> cols = new LinkedHashSet<>();
            for (SchemaModel m : r.models()) {
                TableDef t = m.tables().get(table);
                if (t != null) cols.addAll(t.columns().keySet());
            }
            h.append("<table><tr><th>Column</th>");
            for (String db : r.databases()) h.append("<th>").append(esc(db)).append("</th>");
            h.append("</tr>");
            for (String col : cols) {
                Map<String, String> cells = new LinkedHashMap<>();
                for (SchemaModel m : r.models()) {
                    TableDef t = m.tables().get(table);
                    ColumnDef c = t == null ? null : t.columns().get(col);
                    cells.put(m.nickname(), t == null ? SchemaComparator.NO_TABLE : c == null ? SchemaComparator.MISSING : c.summary());
                }
                boolean differs = new LinkedHashSet<>(cells.values()).size() > 1;
                h.append("<tr").append(differs ? " class=\"rowdiff\"" : "").append("><td>").append(esc(col)).append("</td>");
                for (String db : r.databases()) {
                    String v = cells.get(db);
                    boolean missing = SchemaComparator.MISSING.equals(v) || SchemaComparator.NO_TABLE.equals(v);
                    h.append("<td class=\"").append(missing ? "miss" : "same").append("\">").append(esc(v)).append("</td>");
                }
                h.append("</tr>");
            }
            h.append("</table>");
            boolean anyDdl = r.models().stream().anyMatch(m -> m.ddl().containsKey("TABLE:" + table));
            if (anyDdl) {
                h.append("<div class=\"grid\" style=\"grid-template-columns:repeat(").append(r.databases().size()).append(",1fr)\">");
                for (SchemaModel m : r.models()) {
                    String ddl = m.ddl().get("TABLE:" + table);
                    h.append("<div><strong>").append(esc(m.nickname())).append("</strong><pre>")
                            .append(ddl == null ? "(no DDL)" : esc(ddl)).append("</pre></div>");
                }
                h.append("</div>");
            }
            h.append("</details>");
        }
    }

    private void warnings(StringBuilder h, ComparisonResult r) {
        boolean any = r.models().stream().anyMatch(m -> !m.warnings().isEmpty());
        if (!any) return;
        h.append("<h2>Extraction warnings</h2><ul>");
        for (SchemaModel m : r.models()) {
            for (String w : m.warnings()) h.append("<li>").append(esc(m.nickname())).append(": ").append(esc(w)).append("</li>");
        }
        h.append("</ul>");
    }

    // ---------------------------------------------------------------- helpers

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static final String CSS = """
            body{font:14px/1.4 system-ui,sans-serif;margin:24px;color:#1b1f24}
            h1{margin:0 0 4px}h2{margin-top:32px;border-bottom:1px solid #d0d7de;padding-bottom:4px}
            table{border-collapse:collapse;margin:8px 0;width:100%}
            th,td{border:1px solid #d0d7de;padding:4px 8px;text-align:left;vertical-align:top;font-size:13px}
            th{background:#f6f8fa;position:sticky;top:0}
            .muted{color:#57606a}.ok{color:#1a7f37;font-weight:600}
            .badge,.chip{border-radius:10px;padding:1px 8px;font-size:12px;font-weight:600}
            .chip{display:inline-block;margin-right:6px;padding:3px 10px}
            .sev-critical{background:#ffd8d3;color:#82071e}.sev-high{background:#ffe1c2;color:#7d3a00}
            .sev-medium{background:#fff2b3;color:#5c4400}.sev-low{background:#dff6dd;color:#0f5323}
            td.miss{background:#ffebe9;color:#82071e;font-style:italic}td.same{background:#f6fff6}
            td.v0{background:#ddf4ff}td.v1{background:#fff8c5}td.v2{background:#fbefff}td.v3{background:#e6ffec}
            tr.rowdiff td{border-color:#d4a72c}
            details{margin:8px 0;border:1px solid #d0d7de;border-radius:6px;padding:4px 10px}
            summary{cursor:pointer;font-weight:600;padding:4px 0}
            .grid{display:grid;gap:12px}pre{background:#f6f8fa;padding:8px;overflow:auto;font-size:12px;margin:4px 0}
            input[type=search]{padding:4px 8px;margin-left:12px;width:220px}
            """;

    private static final String JS = """
            (function(){
              var boxes=document.querySelectorAll('input[data-sev]'),q=document.getElementById('q');
              function apply(){
                var on={};boxes.forEach(function(b){on[b.getAttribute('data-sev')]=b.checked});
                var term=(q.value||'').toLowerCase();
                document.querySelectorAll('#diffs tr[data-sev]').forEach(function(r){
                  var ok=on[r.getAttribute('data-sev')]&&(!term||r.textContent.toLowerCase().indexOf(term)>=0);
                  r.style.display=ok?'':'none';
                });
              }
              boxes.forEach(function(b){b.addEventListener('change',apply)});
              q.addEventListener('input',apply);
            })();
            """;
}
