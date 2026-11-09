package com.dbmerge.web;

import com.dbmerge.config.DbMergeProperties;
import com.dbmerge.data.DataCompareService;
import com.dbmerge.data.DataExcelReportWriter;
import com.dbmerge.data.DataHtmlReportWriter;
import com.dbmerge.data.DataModel.DataReport;
import com.dbmerge.data.DataModel.TableDataResult;
import com.dbmerge.ddl.compare.ComparisonResult;
import com.dbmerge.ddl.model.ConstraintDef;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.TableDef;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Phase 2 endpoints: choose business keys per table, compare data, produce reports. */
@RestController
@RequestMapping("/api")
public class DataController {

    private final ComparisonStore store;
    private final DataCompareService service;
    private final DataHtmlReportWriter html;
    private final DataExcelReportWriter excel;
    private final DbMergeProperties props;
    private final Path reportsDir;

    public DataController(ComparisonStore store, DataCompareService service, DataHtmlReportWriter html,
                          DataExcelReportWriter excel, DbMergeProperties props) {
        this.store = store;
        this.service = service;
        this.html = html;
        this.excel = excel;
        this.props = props;
        this.reportsDir = Path.of(props.getReportsDir()).toAbsolutePath().normalize();
    }

    /** Tables of a finished DDL comparison with their columns and primary key, for the key picker in the UI. */
    @GetMapping("/ddl/{id}/tables")
    public List<Map<String, Object>> tables(@PathVariable String id) {
        ComparisonResult r = store.get(id).orElseThrow(() -> new IllegalArgumentException("Unknown DDL comparison: " + id));
        Set<String> names = new LinkedHashSet<>();
        for (SchemaModel m : r.models()) names.addAll(m.tables().keySet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (String t : names.stream().sorted().toList()) {
            Set<String> common = null;
            List<String> presentIn = new ArrayList<>();
            List<String> pk = List.of();
            for (SchemaModel m : r.models()) {
                TableDef td = m.tables().get(t);
                if (td == null) continue;
                presentIn.add(m.nickname());
                if (common == null) common = new LinkedHashSet<>(td.columns().keySet()); else common.retainAll(td.columns().keySet());
                if (pk.isEmpty()) pk = td.constraints().stream().filter(c -> c.type().equals("P")).map(ConstraintDef::columns).findFirst().orElse(List.of());
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", t);
            row.put("presentIn", presentIn);
            row.put("columns", common == null ? List.of() : new ArrayList<>(common));
            row.put("primaryKey", pk);
            out.add(row);
        }
        return out;
    }

    public record DataCompareRequest(String ddlId, Map<String, List<String>> keys, List<String> tables) { }

    @PostMapping("/data/compare")
    public Map<String, Object> compare(@RequestBody DataCompareRequest req) throws SQLException, IOException {
        if (req.ddlId() == null) throw new IllegalArgumentException("ddlId is required (run the DDL comparison first).");
        ComparisonResult ddl = store.requireLive(req.ddlId());
        Set<String> only = req.tables() == null ? Set.of() : new LinkedHashSet<>(req.tables());
        DataReport report = service.run(ddl.id(), ddl.models(), req.keys(), only, props.getSampleLimit());
        store.put(report);

        Files.createDirectories(reportsDir);
        String base = "data-" + report.id();
        Files.writeString(reportsDir.resolve(base + ".html"), html.render(report), StandardCharsets.UTF_8);
        excel.write(report, reportsDir.resolve(base + ".xlsx"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", report.id());
        List<Map<String, Object>> tables = new ArrayList<>();
        for (TableDataResult t : report.tables()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", t.table());
            m.put("status", t.status().name());
            m.put("message", t.message());
            m.put("key", t.keyColumns());
            m.put("keyIsPrimaryKey", t.keyIsPrimaryKey());
            m.put("rowCounts", t.rowCounts());
            m.put("onlyInOne", t.onlyInTotal());
            m.put("identical", t.identicalCount());
            m.put("conflicts", t.conflictCount());
            m.put("pkCollisions", t.pkCollisionCount());
            m.put("samePkDifferentKey", t.pkDivergenceCount());
            m.put("warnings", t.warnings());
            tables.add(m);
        }
        body.put("tables", tables);
        body.put("htmlReport", "/api/reports/" + base + ".html");
        body.put("excelReport", "/api/reports/" + base + ".xlsx");
        return body;
    }
}
