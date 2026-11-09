package com.dbmerge.ddl.report;

import com.dbmerge.ddl.compare.ComparisonResult;
import com.dbmerge.ddl.compare.Difference;
import com.dbmerge.ddl.compare.SchemaComparator;
import com.dbmerge.ddl.compare.Severity;
import com.dbmerge.ddl.model.ColumnDef;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.TableDef;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
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

/** Excel report: Summary, Table Matrix, Differences, Column Matrix, DDL. Fixed column widths (no AWT needed). */
public class ExcelReportWriter {

    private static final int MAX_CELL = 32_000;

    public void write(ComparisonResult r, Path file) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(file)) {
            Styles st = new Styles(wb);
            summary(wb, st, r);
            tableMatrix(wb, st, r);
            differences(wb, st, r);
            columnMatrix(wb, st, r);
            ddl(wb, st, r);
            wb.write(out);
        }
    }

    private void summary(Workbook wb, Styles st, ComparisonResult r) {
        Sheet s = wb.createSheet("Summary");
        int row = 0;
        put(s, row++, 0, "Oracle DDL comparison", st.bold);
        put(s, row++, 0, "Run " + r.id() + " at " + r.generatedAt(), st.plain);
        row++;
        header(s, row++, st, "Database", "Schema", "Tables", "Columns", "Sequences", "Warnings",
                "Views", "PL/SQL units", "Synonyms", "Grants");
        for (SchemaModel m : r.models()) {
            Row x = s.createRow(row++);
            cell(x, 0, m.nickname(), st.plain);
            cell(x, 1, m.owner(), st.plain);
            num(x, 2, m.tables().size(), st.plain);
            num(x, 3, m.columnCount(), st.plain);
            num(x, 4, m.sequences().size(), st.plain);
            num(x, 5, m.warnings().size(), st.plain);
            num(x, 6, m.viewCount(), st.plain);
            num(x, 7, m.plsqlCount(), st.plain);
            num(x, 8, m.synonyms().size(), st.plain);
            num(x, 9, m.grants().size(), st.plain);
        }
        row++;
        header(s, row++, st, "Severity", "Findings");
        Map<Severity, Integer> counts = r.countsBySeverity();
        for (Severity sev : Severity.values()) {
            Row x = s.createRow(row++);
            cell(x, 0, sev.name(), st.sev.get(sev));
            num(x, 1, counts.get(sev), st.plain);
        }
        row++;
        for (SchemaModel m : r.models()) {
            for (String w : m.warnings()) put(s, row++, 0, m.nickname() + ": " + w, st.plain);
        }
        widths(s, 28, 18, 12, 12, 12, 12, 12, 14, 12, 12);
    }

    private void tableMatrix(Workbook wb, Styles st, ComparisonResult r) {
        Sheet s = wb.createSheet("Table Matrix");
        List<String> dbs = r.databases();
        String[] head = new String[dbs.size() + 2];
        head[0] = "Table";
        for (int i = 0; i < dbs.size(); i++) head[i + 1] = dbs.get(i);
        head[dbs.size() + 1] = "Worst severity";
        header(s, 0, st, head);
        Set<String> all = new TreeSet<>();
        for (SchemaModel m : r.models()) all.addAll(m.tables().keySet());
        Map<String, Severity> worst = r.worstSeverityByTable();
        int row = 1;
        for (String t : all) {
            Row x = s.createRow(row++);
            cell(x, 0, t, st.plain);
            int col = 1;
            for (SchemaModel m : r.models()) {
                TableDef td = m.tables().get(t);
                cell(x, col++, td == null ? SchemaComparator.MISSING : td.columns().size() + " columns",
                        td == null ? st.missing : st.plain);
            }
            Severity w = worst.get(t);
            cell(x, col, w == null ? "identical" : w.name(), w == null ? st.plain : st.sev.get(w));
        }
        s.createFreezePane(1, 1);
        s.setAutoFilter(new CellRangeAddress(0, Math.max(row - 1, 0), 0, dbs.size() + 1));
        widths(s, 34);
        for (int i = 1; i <= dbs.size() + 1; i++) s.setColumnWidth(i, 18 * 256);
    }

    private void differences(Workbook wb, Styles st, ComparisonResult r) {
        Sheet s = wb.createSheet("Differences");
        List<String> dbs = r.databases();
        String[] head = new String[dbs.size() + 5];
        head[0] = "Severity";
        head[1] = "Kind";
        head[2] = "Table";
        head[3] = "Object";
        for (int i = 0; i < dbs.size(); i++) head[4 + i] = dbs.get(i);
        head[4 + dbs.size()] = "Why it matters";
        header(s, 0, st, head);
        int row = 1;
        for (Difference d : r.differences()) {
            Row x = s.createRow(row++);
            cell(x, 0, d.severity().name(), st.sev.get(d.severity()));
            cell(x, 1, d.kind().label(), st.wrap);
            cell(x, 2, d.table() == null ? "" : d.table(), st.wrap);
            cell(x, 3, d.object(), st.wrap);
            int col = 4;
            for (String db : dbs) {
                String v = d.values().get(db);
                boolean missing = SchemaComparator.MISSING.equals(v) || SchemaComparator.NO_TABLE.equals(v);
                cell(x, col++, v, missing ? st.missing : st.wrap);
            }
            cell(x, col, d.description(), st.wrap);
        }
        s.createFreezePane(4, 1);
        s.setAutoFilter(new CellRangeAddress(0, Math.max(row - 1, 0), 0, dbs.size() + 4));
        widths(s, 11, 26, 22, 42);
        for (int i = 0; i < dbs.size(); i++) s.setColumnWidth(4 + i, 36 * 256);
        s.setColumnWidth(4 + dbs.size(), 60 * 256);
    }

    private void columnMatrix(Workbook wb, Styles st, ComparisonResult r) {
        Sheet s = wb.createSheet("Column Matrix");
        List<String> dbs = r.databases();
        String[] head = new String[dbs.size() + 2];
        head[0] = "Table";
        head[1] = "Column";
        for (int i = 0; i < dbs.size(); i++) head[2 + i] = dbs.get(i);
        header(s, 0, st, head);
        Set<String> tables = new TreeSet<>();
        for (SchemaModel m : r.models()) tables.addAll(m.tables().keySet());
        int row = 1;
        for (String t : tables) {
            Set<String> cols = new LinkedHashSet<>();
            for (SchemaModel m : r.models()) {
                TableDef td = m.tables().get(t);
                if (td != null) cols.addAll(td.columns().keySet());
            }
            for (String col : cols) {
                Row x = s.createRow(row++);
                cell(x, 0, t, st.plain);
                cell(x, 1, col, st.plain);
                int c = 2;
                Set<String> seen = new LinkedHashSet<>();
                String[] vals = new String[dbs.size()];
                int i = 0;
                for (SchemaModel m : r.models()) {
                    TableDef td = m.tables().get(t);
                    ColumnDef cd = td == null ? null : td.columns().get(col);
                    vals[i] = td == null ? SchemaComparator.NO_TABLE : cd == null ? SchemaComparator.MISSING : cd.summary();
                    seen.add(vals[i]);
                    i++;
                }
                boolean differs = seen.size() > 1;
                for (String v : vals) {
                    boolean missing = SchemaComparator.MISSING.equals(v) || SchemaComparator.NO_TABLE.equals(v);
                    cell(x, c++, v, missing ? st.missing : differs ? st.diffCell : st.plain);
                }
            }
        }
        s.createFreezePane(2, 1);
        s.setAutoFilter(new CellRangeAddress(0, Math.max(row - 1, 0), 0, dbs.size() + 1));
        widths(s, 28, 28);
        for (int i = 0; i < dbs.size(); i++) s.setColumnWidth(2 + i, 36 * 256);
    }

    private void ddl(Workbook wb, Styles st, ComparisonResult r) {
        boolean any = r.models().stream().anyMatch(m -> !m.ddl().isEmpty());
        if (!any) return;
        Sheet s = wb.createSheet("DDL");
        header(s, 0, st, "Database", "Object", "DDL (DBMS_METADATA)");
        int row = 1;
        for (SchemaModel m : r.models()) {
            for (var e : m.ddl().entrySet()) {
                Row x = s.createRow(row++);
                cell(x, 0, m.nickname(), st.plain);
                cell(x, 1, e.getKey(), st.plain);
                cell(x, 2, e.getValue(), st.wrap);
            }
        }
        s.createFreezePane(0, 1);
        widths(s, 16, 36, 110);
    }

    // ---------------------------------------------------------------- helpers

    private static void header(Sheet s, int rowIdx, Styles st, String... names) {
        Row r = s.createRow(rowIdx);
        for (int i = 0; i < names.length; i++) cell(r, i, names[i], st.header);
    }

    private static void put(Sheet s, int rowIdx, int col, String text, CellStyle style) {
        cell(s.createRow(rowIdx), col, text, style);
    }

    private static void cell(Row r, int col, String text, CellStyle style) {
        Cell c = r.createCell(col);
        String t = text == null ? "" : text;
        c.setCellValue(t.length() > MAX_CELL ? t.substring(0, MAX_CELL) : t);
        c.setCellStyle(style);
    }

    private static void num(Row r, int col, int value, CellStyle style) {
        Cell c = r.createCell(col);
        c.setCellValue(value);
        c.setCellStyle(style);
    }

    private static void widths(Sheet s, int... chars) {
        for (int i = 0; i < chars.length; i++) s.setColumnWidth(i, chars[i] * 256);
    }

    private static final class Styles {
        final CellStyle plain, bold, header, wrap, missing, diffCell;
        final Map<Severity, CellStyle> sev = new EnumMap<>(Severity.class);

        Styles(Workbook wb) {
            plain = base(wb);
            Font bf = wb.createFont();
            bf.setBold(true);
            bold = base(wb);
            bold.setFont(bf);
            header = base(wb);
            header.setFont(bf);
            fill(header, IndexedColors.GREY_25_PERCENT);
            wrap = base(wb);
            wrap.setWrapText(true);
            missing = base(wb);
            missing.setWrapText(true);
            fill(missing, IndexedColors.ROSE);
            Font it = wb.createFont();
            it.setItalic(true);
            missing.setFont(it);
            diffCell = base(wb);
            diffCell.setWrapText(true);
            fill(diffCell, IndexedColors.LIGHT_YELLOW);
            sev.put(Severity.CRITICAL, sevStyle(wb, bf, IndexedColors.CORAL));
            sev.put(Severity.HIGH, sevStyle(wb, bf, IndexedColors.LIGHT_ORANGE));
            sev.put(Severity.MEDIUM, sevStyle(wb, bf, IndexedColors.LIGHT_YELLOW));
            sev.put(Severity.LOW, sevStyle(wb, bf, IndexedColors.LIGHT_GREEN));
        }

        private static CellStyle base(Workbook wb) {
            CellStyle c = wb.createCellStyle();
            c.setVerticalAlignment(VerticalAlignment.TOP);
            c.setBorderBottom(BorderStyle.THIN);
            c.setBorderTop(BorderStyle.THIN);
            c.setBorderLeft(BorderStyle.THIN);
            c.setBorderRight(BorderStyle.THIN);
            return c;
        }

        private static CellStyle sevStyle(Workbook wb, Font bold, IndexedColors color) {
            CellStyle c = base(wb);
            c.setFont(bold);
            fill(c, color);
            return c;
        }

        private static void fill(CellStyle c, IndexedColors color) {
            c.setFillForegroundColor(color.getIndex());
            c.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
    }
}
