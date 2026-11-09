package com.dbmerge.web;

import com.dbmerge.config.DbMergeProperties;
import com.dbmerge.conn.ConnectionRegistry;
import com.dbmerge.conn.ConnectionRequest;
import com.dbmerge.conn.ConnectionView;
import com.dbmerge.conn.TestResult;
import com.dbmerge.ddl.SchemaExtractor;
import com.dbmerge.ddl.compare.ComparisonResult;
import com.dbmerge.ddl.compare.Difference;
import com.dbmerge.ddl.compare.SchemaComparator;
import com.dbmerge.ddl.compare.Severity;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.report.ExcelReportWriter;
import com.dbmerge.ddl.report.HtmlReportWriter;
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
import java.util.regex.Pattern;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {

    private static final Pattern REPORT_NAME = Pattern.compile("^(ddl|data|merge|refs)-[0-9a-f]{8}(-[a-z]+)?\\.(html|xlsx|sql|txt)$");
    private static final int MAX_DIFFS_IN_RESPONSE = 500;

    private final ConnectionRegistry registry;
    private final SchemaExtractor extractor;
    private final SchemaComparator comparator;
    private final HtmlReportWriter htmlWriter;
    private final ExcelReportWriter excelWriter;
    private final ComparisonStore store;
    private final Path reportsDir;

    public ApiController(ConnectionRegistry registry, SchemaExtractor extractor, SchemaComparator comparator,
                         HtmlReportWriter htmlWriter, ExcelReportWriter excelWriter, ComparisonStore store,
                         DbMergeProperties props) {
        this.registry = registry;
        this.extractor = extractor;
        this.comparator = comparator;
        this.htmlWriter = htmlWriter;
        this.excelWriter = excelWriter;
        this.store = store;
        this.reportsDir = Path.of(props.getReportsDir()).toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------ connections

    @GetMapping("/connections")
    public List<ConnectionView> list() {
        return registry.list();
    }

    @PostMapping("/connections")
    public ConnectionView add(@RequestBody ConnectionRequest request) {
        return registry.add(request);
    }

    @DeleteMapping("/connections/{nickname}")
    public ResponseEntity<Void> remove(@PathVariable String nickname) {
        return registry.remove(nickname) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    public record UnlockRequest(char[] password, Boolean savePassword) { }

    @PostMapping("/connections/{nickname}/unlock")
    public ConnectionView unlock(@PathVariable String nickname, @RequestBody UnlockRequest request) {
        return registry.unlock(nickname, request.password(), Boolean.TRUE.equals(request.savePassword()));
    }

    @PostMapping("/connections/{nickname}/test")
    public TestResult test(@PathVariable String nickname) {
        return registry.test(nickname);
    }

    // ----------------------------------------------------------- phase 1: DDL

    public record CompareRequest(List<String> nicknames, Boolean includeDdl) { }

    @PostMapping("/ddl/compare")
    public Map<String, Object> compareDdl(@RequestBody CompareRequest req) throws SQLException, IOException {
        List<String> nicks = req.nicknames() == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(req.nicknames()));
        if (nicks.size() < 2) throw new IllegalArgumentException("Select at least two connections.");
        boolean ddl = req.includeDdl() == null || req.includeDdl();

        List<SchemaModel> models = new ArrayList<>();
        for (String n : nicks) models.add(extractor.extract(n, ddl));

        ComparisonResult result = comparator.compare(models);
        store.put(result);

        Files.createDirectories(reportsDir);
        String base = "ddl-" + result.id();
        Files.writeString(reportsDir.resolve(base + ".html"), htmlWriter.render(result), StandardCharsets.UTF_8);
        excelWriter.write(result, reportsDir.resolve(base + ".xlsx"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", result.id());
        body.put("generatedAt", result.generatedAt().toString());
        List<Map<String, Object>> dbs = new ArrayList<>();
        for (SchemaModel m : models) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("nickname", m.nickname());
            d.put("schema", m.owner());
            d.put("tables", m.tables().size());
            d.put("sequences", m.sequences().size());
            d.put("views", m.viewCount());
            d.put("plsql", m.plsqlCount());
            d.put("synonyms", m.synonyms().size());
            d.put("grants", m.grants().size());
            d.put("warnings", m.warnings());
            dbs.add(d);
        }
        body.put("databases", dbs);
        Map<String, Integer> sev = new LinkedHashMap<>();
        result.countsBySeverity().forEach((k, v) -> sev.put(k.name(), v));
        body.put("severityCounts", sev);
        body.put("differenceCount", result.differences().size());
        List<Difference> shown = result.differences().size() > MAX_DIFFS_IN_RESPONSE
                ? result.differences().subList(0, MAX_DIFFS_IN_RESPONSE) : result.differences();
        List<Map<String, Object>> diffs = new ArrayList<>();
        for (Difference d : shown) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", d.severity().name());
            m.put("kind", d.kind().label());
            m.put("table", d.table());
            m.put("object", d.object());
            m.put("values", d.values());
            m.put("description", d.description());
            diffs.add(m);
        }
        body.put("differences", diffs);
        body.put("truncated", result.differences().size() > shown.size());
        body.put("htmlReport", "/api/reports/" + base + ".html");
        body.put("excelReport", "/api/reports/" + base + ".xlsx");
        return body;
    }

    @GetMapping("/reports/{name}")
    public ResponseEntity<Resource> report(@PathVariable String name) {
        if (!REPORT_NAME.matcher(name).matches()) return ResponseEntity.notFound().build();
        Path file = reportsDir.resolve(name).normalize();
        if (!file.startsWith(reportsDir) || !Files.isRegularFile(file)) return ResponseEntity.notFound().build();
        boolean html = name.endsWith(".html");
        boolean text = name.endsWith(".sql") || name.endsWith(".txt");
        ResponseEntity.BodyBuilder b = ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .contentType(html ? MediaType.TEXT_HTML : text ? MediaType.TEXT_PLAIN
                        : MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        if (!html && !text) b.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"");
        return b.body(new FileSystemResource(file));
    }
}
