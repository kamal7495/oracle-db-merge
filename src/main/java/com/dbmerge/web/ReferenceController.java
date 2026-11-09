package com.dbmerge.web;

import com.dbmerge.config.DbMergeProperties;
import com.dbmerge.refs.RefModel.Candidate;
import com.dbmerge.refs.RefModel.Confidence;
import com.dbmerge.refs.RefModel.DataCheck;
import com.dbmerge.refs.RefModel.Kind;
import com.dbmerge.refs.RefModel.ReferenceReport;
import com.dbmerge.refs.ReferenceHtmlReportWriter;
import com.dbmerge.refs.ReferenceService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Undeclared-reference report for one connection. Read-only: extraction plus optional SELECT-only data checks. */
@RestController
@RequestMapping("/api/refs")
public class ReferenceController {

    private static final int MAX_IN_RESPONSE = 500;
    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_MAX_CHECKS = 1_000;

    private final ReferenceService service;
    private final ReferenceHtmlReportWriter writer;
    private final Path reportsDir;

    public ReferenceController(ReferenceService service, ReferenceHtmlReportWriter writer, DbMergeProperties props) {
        this.service = service;
        this.writer = writer;
        this.reportsDir = Path.of(props.getReportsDir()).toAbsolutePath().normalize();
    }

    public record DetectRequest(String nickname, Boolean checkData, Integer timeoutSeconds, Integer maxChecks) { }

    @PostMapping("/detect")
    public Map<String, Object> detect(@RequestBody DetectRequest req) throws SQLException, IOException {
        if (req.nickname() == null || req.nickname().isBlank()) throw new IllegalArgumentException("Choose a connection.");
        int timeout = clamp(req.timeoutSeconds(), DEFAULT_TIMEOUT_SECONDS, 1, 3_600);
        int maxChecks = clamp(req.maxChecks(), DEFAULT_MAX_CHECKS, 1, 100_000);
        ReferenceReport r = service.run(req.nickname(), Boolean.TRUE.equals(req.checkData()), timeout, maxChecks);

        Files.createDirectories(reportsDir);
        String name = "refs-" + r.id() + ".html";
        Files.writeString(reportsDir.resolve(name), writer.render(r), StandardCharsets.UTF_8);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", r.id());
        body.put("nickname", r.nickname());
        body.put("schema", r.owner());
        body.put("tables", r.tableCount());
        body.put("declaredForeignKeys", r.declaredForeignKeys());
        body.put("dataChecked", r.dataChecked());
        Map<String, Long> kinds = new LinkedHashMap<>();
        for (Kind k : Kind.values()) kinds.put(k.name(), r.count(k));
        body.put("kindCounts", kinds);
        Map<String, Long> conf = new LinkedHashMap<>();
        for (Confidence c : Confidence.values()) conf.put(c.name(), r.count(c));
        body.put("confidenceCounts", conf);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Candidate c : r.candidates().subList(0, Math.min(MAX_IN_RESPONSE, r.candidates().size()))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("confidence", c.confidence().name());
            m.put("kind", c.kind().label());
            m.put("column", c.qualifiedColumn());
            m.put("type", c.columnType());
            m.put("parents", c.parentTables());
            m.put("parentColumn", c.parentColumn());
            m.put("rule", c.rule());
            m.put("notes", c.notes());
            List<Map<String, Object>> checks = new ArrayList<>();
            for (DataCheck d : c.dataChecks()) {
                Map<String, Object> dc = new LinkedHashMap<>();
                dc.put("parent", d.parentTable());
                dc.put("nonNull", d.nonNullValues());
                dc.put("unresolved", d.unresolvedValues());
                dc.put("error", d.error());
                checks.add(dc);
            }
            m.put("dataChecks", checks);
            list.add(m);
        }
        body.put("candidates", list);
        body.put("candidateCount", r.candidates().size());
        body.put("truncated", r.candidates().size() > list.size());
        body.put("warnings", r.warnings());
        body.put("htmlReport", "/api/reports/" + name);
        return body;
    }

    private static int clamp(Integer v, int dflt, int min, int max) {
        return v == null ? dflt : Math.max(min, Math.min(max, v));
    }
}
