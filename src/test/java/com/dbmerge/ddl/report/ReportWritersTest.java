package com.dbmerge.ddl.report;

import static com.dbmerge.ddl.compare.Fixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.ddl.compare.ComparisonResult;
import com.dbmerge.ddl.compare.SchemaComparator;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportWritersTest {

    private static ComparisonResult sample() {
        var a = table("EVIL<B>").col(number("ID", 1, 10, 0, false), varchar("NAME", 2, 50, false)).build();
        var b = table("EVIL<B>").col(number("ID", 1, 10, 0, false), varchar("NAME", 2, 80, false)).build();
        var onlyA = table("ONLY_A").col(number("ID", 1, 10, 0, false)).build();
        return new SchemaComparator().compare(List.of(model("DB_A", a, onlyA), model("DB_B", b)));
    }

    @Test
    void htmlEscapesDatabaseDerivedTextAndShowsSeverityAndAllDatabases() {
        String html = new HtmlReportWriter().render(sample());
        assertFalse(html.contains("EVIL<B>"));
        assertTrue(html.contains("EVIL&lt;B&gt;"));
        assertTrue(html.contains("DB_A") && html.contains("DB_B"));
        assertTrue(html.contains("Missing table"));
        assertTrue(html.contains("HIGH"));
        assertTrue(html.contains("(missing)"));
    }

    @Test
    void htmlSaysSoWhenThereAreNoDifferences() {
        var t = table("T").col(number("ID", 1, 10, 0, false)).build();
        ComparisonResult r = new SchemaComparator().compare(List.of(model("A", t), model("B", t)));
        assertTrue(new HtmlReportWriter().render(r).contains("No structural differences"));
    }

    @Test
    void excelHasExpectedSheetsAndOneRowPerDifference(@TempDir Path dir) throws Exception {
        ComparisonResult r = sample();
        Path file = dir.resolve("report.xlsx");
        new ExcelReportWriter().write(r, file);
        try (InputStream in = Files.newInputStream(file); Workbook wb = new XSSFWorkbook(in)) {
            assertNotNull(wb.getSheet("Summary"));
            assertNotNull(wb.getSheet("Table Matrix"));
            assertNotNull(wb.getSheet("Differences"));
            assertNotNull(wb.getSheet("Column Matrix"));
            // header + one row per difference
            assertEquals(r.differences().size(), wb.getSheet("Differences").getLastRowNum());
            assertEquals("Severity", wb.getSheet("Differences").getRow(0).getCell(0).getStringCellValue());
        }
    }
}
