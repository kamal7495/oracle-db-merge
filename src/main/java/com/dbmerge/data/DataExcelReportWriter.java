package com.dbmerge.data;

import com.dbmerge.data.DataModel.Conflict;
import com.dbmerge.data.DataModel.DataReport;
import com.dbmerge.data.DataModel.IdenticalRow;
import com.dbmerge.data.DataModel.OnlyRow;
import com.dbmerge.data.DataModel.PkCollision;
import com.dbmerge.data.DataModel.PkDivergence;
import com.dbmerge.data.DataModel.Status;
import com.dbmerge.data.DataModel.TableDataResult;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Excel report for Phase 2: Summary, Only in one DB, Identical, Conflicts, PK collisions, Same key different PK, Warnings. */
public class DataExcelReportWriter {

    private static final int MAX_CELL = 32_000;

    public void write(DataReport r, Path file) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            CellStyle head = style(wb, true, IndexedColors.GREY_25_PERCENT);
            CellStyle plain = style(wb, false, null);
            CellStyle warn = style(wb, false, IndexedColors.LIGHT_YELLOW);

            // Summary
            Sheet s = wb.createSheet("Summary");
            List<String> dbs = r.databases();
            int cols = 2 + dbs.size() + 6;
            String[] h = new String[cols];
            h[0] = "Table";
            h[1] = "Business key";
            for (int i = 0; i < dbs.size(); i++) h[2 + i] = "Rows in " + dbs.get(i);
            h[2 + dbs.size()] = "Only in one";
            h[3 + dbs.size()] = "Identical";
            h[4 + dbs.size()] = "Conflicts";
            h[5 + dbs.size()] = "PK collisions";
            h[6 + dbs.size()] = "Same key, different PK";
            h[7 + dbs.size()] = "Status / message";
            header(s, 0, head, h);
            int row = 1;
            for (TableDataResult t : r.tables()) {
                Row x = s.createRow(row++);
                cell(x, 0, t.table(), plain);
                cell(x, 1, String.join(", ", t.keyColumns()) + (t.keyIsPrimaryKey() ? " (PK)" : ""), plain);
                int c = 2;
                for (String db : dbs) {
                    Long n = t.rowCounts().get(db);
                    cell(x, c++, n == null || n < 0 ? "no table" : n.toString(), plain);
                }
                if (t.status() == Status.COMPARED) {
                    num(x, c++, t.onlyInTotal(), plain);
                    num(x, c++, t.identicalCount(), plain);
                    num(x, c++, t.conflictCount(), t.conflictCount() > 0 ? warn : plain);
                    num(x, c++, t.pkCollisionCount(), t.pkCollisionCount() > 0 ? warn : plain);
                    num(x, c++, t.pkDivergenceCount(), plain);
                    cell(x, c, t.warnings().isEmpty() ? "compared" : "compared; " + t.warnings().size() + " warning(s)", plain);
                } else {
                    for (int i = 0; i < 5; i++) cell(x, c++, "", plain);
                    cell(x, c, t.status() + ": " + t.message(), plain);
                }
            }
            s.createFreezePane(1, 1);
            s.setAutoFilter(new CellRangeAddress(0, Math.max(row - 1, 0), 0, cols - 1));
            s.setColumnWidth(0, 28 * 256);
            s.setColumnWidth(1, 28 * 256);
            for (int i = 2; i < cols; i++) s.setColumnWidth(i, 16 * 256);
            s.setColumnWidth(cols - 1, 60 * 256);

            // Only in one
            Sheet o = wb.createSheet("Only in one DB");
            header(o, 0, head, "Table", "Database", "Key", "Row");
            row = 1;
            for (TableDataResult t : r.tables()) {
                for (OnlyRow x : t.onlyRows()) {
                    Row y = o.createRow(row++);
                    cell(y, 0, t.table(), plain);
                    cell(y, 1, x.database(), plain);
                    cell(y, 2, x.key(), plain);
                    cell(y, 3, DataHtmlReportWriter.rowText(x.row()), plain);
                }
            }
            widths(o, 24, 16, 40, 100);
            o.createFreezePane(0, 1);

            // Identical
            Sheet i = wb.createSheet("Identical");
            header(i, 0, head, "Table", "Key", "Present in");
            row = 1;
            for (TableDataResult t : r.tables()) {
                for (IdenticalRow x : t.identicalRows()) {
                    Row y = i.createRow(row++);
                    cell(y, 0, t.table(), plain);
                    cell(y, 1, x.key(), plain);
                    cell(y, 2, String.join(", ", x.databases()), plain);
                }
            }
            widths(i, 24, 50, 30);
            i.createFreezePane(0, 1);

            // Conflicts: one row per differing column, one value column per database
            Sheet cf = wb.createSheet("Conflicts");
            String[] ch = new String[3 + dbs.size()];
            ch[0] = "Table";
            ch[1] = "Key";
            ch[2] = "Column";
            for (int k = 0; k < dbs.size(); k++) ch[3 + k] = dbs.get(k);
            header(cf, 0, head, ch);
            row = 1;
            for (TableDataResult t : r.tables()) {
                for (Conflict c : t.conflicts()) {
                    for (String col : c.differingColumns()) {
                        Row y = cf.createRow(row++);
                        cell(y, 0, t.table(), plain);
                        cell(y, 1, c.key(), plain);
                        cell(y, 2, col, plain);
                        int k = 3;
                        for (String db : dbs) {
                            Map<String, String> rr = c.rowsByDb().get(db);
                            cell(y, k++, rr == null ? "(not present)" : DataHtmlReportWriter.show(rr.get(col)), rr == null ? plain : warn);
                        }
                    }
                }
            }
            widths(cf, 24, 40, 24);
            for (int k = 0; k < dbs.size(); k++) cf.setColumnWidth(3 + k, 32 * 256);
            cf.createFreezePane(0, 1);

            // PK collisions
            Sheet pc = wb.createSheet("PK collisions");
            header(pc, 0, head, "Table", "Primary key", "Database", "Business key", "Row");
            row = 1;
            for (TableDataResult t : r.tables()) {
                for (PkCollision p : t.pkCollisions()) {
                    for (var e : p.businessKeyByDb().entrySet()) {
                        Row y = pc.createRow(row++);
                        cell(y, 0, t.table(), plain);
                        cell(y, 1, p.pk(), plain);
                        cell(y, 2, e.getKey(), plain);
                        cell(y, 3, e.getValue(), plain);
                        cell(y, 4, DataHtmlReportWriter.rowText(p.rowsByDb().get(e.getKey())), plain);
                    }
                }
            }
            widths(pc, 24, 24, 16, 40, 100);
            pc.createFreezePane(0, 1);

            // Same key, different PK
            Sheet pd = wb.createSheet("Same key different PK");
            header(pd, 0, head, "Table", "Business key", "Primary key per database");
            row = 1;
            for (TableDataResult t : r.tables()) {
                for (PkDivergence p : t.pkDivergences()) {
                    Row y = pd.createRow(row++);
                    cell(y, 0, t.table(), plain);
                    cell(y, 1, p.key(), plain);
                    cell(y, 2, p.pkByDb().toString(), plain);
                }
            }
            widths(pd, 24, 50, 60);
            pd.createFreezePane(0, 1);

            // Warnings
            Sheet w = wb.createSheet("Warnings");
            header(w, 0, head, "Table", "Warning");
            row = 1;
            for (TableDataResult t : r.tables()) {
                for (String text : t.warnings()) {
                    Row y = w.createRow(row++);
                    cell(y, 0, t.table(), plain);
                    cell(y, 1, text, plain);
                }
            }
            widths(w, 24, 140);

            wb.write(out);
        }
    }

    private static CellStyle style(Workbook wb, boolean bold, IndexedColors fill) {
        CellStyle c = wb.createCellStyle();
        c.setVerticalAlignment(VerticalAlignment.TOP);
        c.setBorderBottom(BorderStyle.THIN);
        c.setBorderTop(BorderStyle.THIN);
        c.setBorderLeft(BorderStyle.THIN);
        c.setBorderRight(BorderStyle.THIN);
        if (bold) {
            Font f = wb.createFont();
            f.setBold(true);
            c.setFont(f);
        }
        if (fill != null) {
            c.setFillForegroundColor(fill.getIndex());
            c.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        return c;
    }

    private static void header(Sheet s, int rowIdx, CellStyle st, String... names) {
        Row r = s.createRow(rowIdx);
        for (int i = 0; i < names.length; i++) cell(r, i, names[i], st);
    }

    private static void cell(Row r, int col, String text, CellStyle style) {
        Cell c = r.createCell(col);
        String t = text == null ? "" : text;
        c.setCellValue(t.length() > MAX_CELL ? t.substring(0, MAX_CELL) : t);
        c.setCellStyle(style);
    }

    private static void num(Row r, int col, long value, CellStyle style) {
        Cell c = r.createCell(col);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    private static void widths(Sheet s, int... chars) {
        for (int i = 0; i < chars.length; i++) s.setColumnWidth(i, chars[i] * 256);
    }
}
