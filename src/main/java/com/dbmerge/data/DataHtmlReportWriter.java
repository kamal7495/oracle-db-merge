package com.dbmerge.data;

import static com.dbmerge.ddl.report.HtmlReportWriter.esc;

import com.dbmerge.data.DataModel.Conflict;
import com.dbmerge.data.DataModel.DataReport;
import com.dbmerge.data.DataModel.OnlyRow;
import com.dbmerge.data.DataModel.PkCollision;
import com.dbmerge.data.DataModel.PkDivergence;
import com.dbmerge.data.DataModel.Status;
import com.dbmerge.data.DataModel.TableDataResult;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** Self-contained HTML report for Phase 2. All database content is escaped. */
public class DataHtmlReportWriter {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z");

    public String render(DataReport r) {
        StringBuilder h = new StringBuilder(64 * 1024);
        h.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Data comparison ").append(esc(r.id()))
                .append("</title><style>").append(CSS).append("</style></head><body>");
        h.append("<h1>Oracle data comparison</h1><p class=\"muted\">Run ").append(esc(r.id())).append(" &middot; ")
                .append(esc(TS.format(r.generatedAt().atZone(ZoneId.systemDefault())))).append(" &middot; databases: ")
                .append(esc(String.join(", ", r.databases()))).append(" &middot; detail lists capped at ")
                .append(r.sampleLimit()).append(" rows per category (counts are exact)</p>");

        h.append("<h2>Summary</h2><table><tr><th>Table</th><th>Business key</th>");
        for (String db : r.databases()) h.append("<th>Rows in ").append(esc(db)).append("</th>");
        h.append("<th>Only in one</th><th>Identical</th><th>Conflicts</th><th>PK collisions</th><th>Same key, different PK</th><th>Status</th></tr>");
        for (TableDataResult t : r.tables()) {
            h.append("<tr><td><a href=\"#t-").append(esc(t.table())).append("\">").append(esc(t.table())).append("</a></td><td>")
                    .append(esc(String.join(", ", t.keyColumns()))).append(t.keyIsPrimaryKey() ? " <span class=\"muted\">(PK)</span>" : "").append("</td>");
            for (String db : r.databases()) {
                Long n = t.rowCounts().get(db);
                h.append(n == null || n < 0 ? "<td class=\"miss\">no table</td>" : "<td>" + n + "</td>");
            }
            if (t.status() == Status.COMPARED) {
                h.append("<td>").append(t.onlyInTotal()).append("</td><td>").append(t.identicalCount()).append("</td><td")
                        .append(t.conflictCount() > 0 ? " class=\"warn\"" : "").append(">").append(t.conflictCount()).append("</td><td")
                        .append(t.pkCollisionCount() > 0 ? " class=\"bad\"" : "").append(">").append(t.pkCollisionCount()).append("</td><td>")
                        .append(t.pkDivergenceCount()).append("</td><td>").append(t.message() != null ? "compared by hash" : "compared")
                        .append(t.warnings().isEmpty() ? "" : ", " + t.warnings().size() + " warning(s)").append("</td>");
            } else {
                h.append("<td colspan=\"5\">").append(esc(t.message())).append("</td><td>").append(esc(t.status().name())).append("</td>");
            }
            h.append("</tr>");
        }
        h.append("</table>");

        for (TableDataResult t : r.tables()) {
            if (t.status() == Status.COMPARED) detail(h, r, t);
        }
        h.append("</body></html>");
        return h.toString();
    }

    private void detail(StringBuilder h, DataReport r, TableDataResult t) {
        h.append("<details id=\"t-").append(esc(t.table())).append("\"><summary>").append(esc(t.table())).append(" &mdash; ")
                .append(t.onlyInTotal()).append(" only-in-one, ").append(t.identicalCount()).append(" identical, ")
                .append(t.conflictCount()).append(" conflicts, ").append(t.pkCollisionCount()).append(" PK collisions</summary>");
        if (t.message() != null) h.append("<p class=\"muted\">").append(esc(t.message())).append("</p>");
        for (String w : t.warnings()) h.append("<p class=\"warnbox\">").append(esc(w)).append("</p>");
        h.append("<p class=\"muted\">Business key: ").append(esc(String.join(", ", t.keyColumns())))
                .append(" &middot; primary key: ").append(esc(String.join(", ", t.pkColumns())))
                .append(" &middot; compared columns: ").append(esc(String.join(", ", t.comparedColumns()))).append("</p>");

        if (!t.onlyRows().isEmpty()) {
            h.append("<h3>Rows only in one database (").append(t.onlyInTotal()).append(")</h3><table><tr><th>Database</th><th>Key</th><th>Row</th></tr>");
            for (OnlyRow o : t.onlyRows()) {
                h.append("<tr><td>").append(esc(o.database())).append("</td><td>").append(esc(o.key())).append("</td><td>").append(esc(rowText(o.row()))).append("</td></tr>");
            }
            h.append("</table>");
        }
        if (!t.identicalRows().isEmpty()) {
            h.append("<h3>Identical in several databases (").append(t.identicalCount()).append(")</h3><table><tr><th>Key</th><th>Present in</th></tr>");
            for (var i : t.identicalRows()) {
                h.append("<tr><td>").append(esc(i.key())).append("</td><td>").append(esc(String.join(", ", i.databases()))).append("</td></tr>");
            }
            h.append("</table>");
        }
        if (!t.conflicts().isEmpty()) {
            h.append("<h3>Same key, different data (").append(t.conflictCount()).append(")</h3>");
            for (Conflict c : t.conflicts()) {
                h.append("<table class=\"conf\"><tr><th colspan=\"").append(1 + c.databases().size()).append("\">").append(esc(c.key())).append("</th></tr><tr><th>Column</th>");
                for (String db : c.databases()) h.append("<th>").append(esc(db)).append("</th>");
                h.append("</tr>");
                for (String col : c.differingColumns()) {
                    h.append("<tr><td>").append(esc(col)).append("</td>");
                    for (String db : c.databases()) h.append("<td class=\"warn\">").append(esc(show(c.rowsByDb().get(db).get(col)))).append("</td>");
                    h.append("</tr>");
                }
                h.append("</table>");
            }
        }
        if (!t.pkCollisions().isEmpty()) {
            h.append("<h3>PK collisions: same primary key, different record (").append(t.pkCollisionCount()).append(")</h3>")
                    .append("<table><tr><th>Primary key</th><th>Business key per database</th></tr>");
            for (PkCollision p : t.pkCollisions()) {
                h.append("<tr><td>").append(esc(p.pk())).append("</td><td>");
                for (Map.Entry<String, String> e : p.businessKeyByDb().entrySet()) {
                    h.append(esc(e.getKey())).append(": ").append(esc(e.getValue())).append("<br>");
                }
                h.append("</td></tr>");
            }
            h.append("</table>");
        }
        if (!t.pkDivergences().isEmpty()) {
            h.append("<h3>Same record, different primary key (").append(t.pkDivergenceCount()).append(")</h3>")
                    .append("<table><tr><th>Business key</th><th>Primary key per database</th></tr>");
            for (PkDivergence p : t.pkDivergences()) {
                h.append("<tr><td>").append(esc(p.key())).append("</td><td>");
                for (Map.Entry<String, String> e : p.pkByDb().entrySet()) {
                    h.append(esc(e.getKey())).append(": ").append(esc(e.getValue())).append("<br>");
                }
                h.append("</td></tr>");
            }
            h.append("</table>");
        }
        h.append("</details>");
    }

    static String rowText(Map<String, String> row) {
        StringBuilder sb = new StringBuilder();
        for (var e : row.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getKey()).append('=').append(show(e.getValue()));
        }
        return sb.toString();
    }

    static String show(String v) {
        return v == null ? "NULL" : v;
    }

    private static final String CSS = """
            body{font:14px/1.4 system-ui,sans-serif;margin:24px;color:#1b1f24}
            h1{margin:0 0 4px}h2{margin-top:28px;border-bottom:1px solid #d0d7de;padding-bottom:4px}h3{margin:16px 0 4px;font-size:14px}
            table{border-collapse:collapse;margin:8px 0;width:100%}
            th,td{border:1px solid #d0d7de;padding:4px 8px;text-align:left;vertical-align:top;font-size:13px}
            th{background:#f6f8fa}.muted{color:#57606a}
            td.miss{background:#ffebe9;font-style:italic}td.warn{background:#fff8c5}td.bad{background:#ffd8d3;font-weight:600}
            .warnbox{background:#fff8c5;border:1px solid #d4a72c;border-radius:6px;padding:6px 10px}
            details{margin:10px 0;border:1px solid #d0d7de;border-radius:6px;padding:4px 10px}
            summary{cursor:pointer;font-weight:600;padding:4px 0}table.conf{width:auto;min-width:50%}
            """;
}
