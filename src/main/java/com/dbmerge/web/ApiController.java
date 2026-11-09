package com.dbmerge.web;

import com.dbmerge.config.DbMergeProperties;
import com.dbmerge.conn.ConnectionRegistry;
import com.dbmerge.conn.ConnectionRequest;
import com.dbmerge.conn.ConnectionView;
import com.dbmerge.conn.TestResult;
import com.dbmerge.ddl.SchemaExtractor;
import com.dbmerge.ddl.compare.CompareFilter;
import com.dbmerge.ddl.model.SchemaSnapshot;
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
import java.util.Set;
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

    /** {@code include}/{@code exclude}: name patterns; {@code ignore}: difference kinds or categories (see CompareFilter). */
    public record CompareRequest(List<String> nicknames, Boolean includeDdl, List<String> include, List<String> exclude,
                                 List<String> ignore, List<SnapshotUpload> snapshots) { }

    /** A snapshot file uploaded from the browser: its file name (used as nickname) and JSON content. */
    public record SnapshotUpload(String name, String content) { }

    @PostMapping("/ddl/compare")
    public Map<String, Object> compareDdl(@RequestBody CompareRequest req) throws SQLException, IOException {
        List<String> nicks = req.nicknames() == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(req.nicknames()));
        List<SnapshotUpload> uploads = req.snapshots() == null ? List.of() : req.snapshots();
        if (nicks.size() + uploads.size() < 2) throw new IllegalArgumentException("Select at least two connections, or a connection and a snapshot.");
        boolean ddl = req.includeDdl() == null || req.includeDdl();

        CompareFilter filter = CompareFilter.of(req.include(), req.exclude(), req.ignore());
        List<SchemaModel> extracted = new ArrayList<>();
        for (String n : nicks) extracted.add(extractor.extract(n, ddl));
        Set<String> snapshotNames = new LinkedHashSet<>();
        for (SnapshotUpload u : uploads) {
            if (u == null || u.content() == null) throw new IllegalArgumentException("Empty snapshot upload.");
            String name = SchemaSnapshot.nicknameFor(u.name() == null ? "snapshot" : u.name());
            SchemaModel m = SchemaSnapshot.fromJson(u.content(), name);
            if (nicks.contains(name) || !snapshotNames.add(name)) {
                throw new IllegalArgumentException("Snapshot '" + u.name() + "' would be named " + name
                        + ", like another database in this comparison: rename the file.");
            }
            extracted.add(m);
        }

        ComparisonResult result = comparator.compare(extracted, filter);
        List<SchemaModel> models = result.models();
        store.put(result, snapshotNames);

        Files.createDirectories(reportsDir);
        String base = "ddl-" + result.id();
        Files.writeString(reportsDir.resolve(base + ".html"), htmlWriter.render(result), StandardCharsets.UTF_8);
        excelWriter.write(result, reportsDir.resolve(base + ".xlsx"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", result.id());
        body.put("generatedAt", result.generatedAt().toString());
        body.put("filter", result.filter());
        body.put("snapshots", List.copyOf(snapshotNames));
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

    /** Downloads one database of a stored comparison as a snapshot file (filtered as that comparison was). */
    @GetMapping("/ddl/{id}/snapshot/{nickname}")
    public ResponseEntity<byte[]> snapshot(@PathVariable String id, @PathVariable String nickname) throws IOException {
        ComparisonResult r = store.get(id).orElseThrow(() -> new IllegalArgumentException("Unknown DDL comparison: " + id));
        SchemaModel m = r.models().stream().filter(x -> x.nickname().equals(nickname)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No database '" + nickname + "' in comparison " + id + "."));
        String stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"snapshot-" + m.nickname() + "-" + stamp + ".json\"")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(SchemaSnapshot.toJson(m, r.filter()).getBytes(StandardCharsets.UTF_8));
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
