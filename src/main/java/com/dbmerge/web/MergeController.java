package com.dbmerge.web;

import com.dbmerge.ddl.compare.ComparisonResult;
import com.dbmerge.merge.MergeModel.ConflictAction;
import com.dbmerge.merge.MergeModel.MergePlan;
import com.dbmerge.merge.MergeModel.MergeResult;
import com.dbmerge.merge.MergeModel.OrphanPolicy;
import com.dbmerge.merge.MergeModel.Options;
import com.dbmerge.merge.MergeModel.PlannedRow;
import com.dbmerge.merge.MergeModel.RowAction;
import com.dbmerge.merge.MergeModel.TableStep;
import com.dbmerge.merge.MergeService;
import com.dbmerge.merge.Rules;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Phase 3 endpoints. /merge/plan is always a dry run; only /merge/execute writes, and only to the master. */
@RestController
@RequestMapping("/api/merge")
public class MergeController {

    private final MergeService service;
    private final ComparisonStore store;

    public MergeController(MergeService service, ComparisonStore store) {
        this.service = service;
        this.store = store;
    }

    public record PlanRequest(String ddlId, String master, List<String> sources, Map<String, List<String>> keys,
                              Boolean disableTriggers, String orphanPolicy, Map<String, String> pkSequences,
                              List<String> acknowledgedReferences) { }

    public record ExecuteRequest(String planId, String confirmation, boolean backupConfirmed, List<String> approvedAlters) { }

    public record TableRuleRequest(String table, ConflictAction action, String dateColumn) { }

    public record RowRuleRequest(String table, String businessKey, ConflictAction action, Map<String, String> manualValues) { }

    @PostMapping("/plan")
    public Map<String, Object> plan(@RequestBody PlanRequest req) throws SQLException, IOException {
        if (req.ddlId() == null) throw new IllegalArgumentException("ddlId is required (run the DDL comparison first).");
        ComparisonResult ddl = store.requireLive(req.ddlId());
        Options options = new Options(Boolean.TRUE.equals(req.disableTriggers()),
                req.orphanPolicy() == null ? OrphanPolicy.FAIL : OrphanPolicy.valueOf(req.orphanPolicy().toUpperCase()),
                req.pkSequences() == null ? Map.of() : req.pkSequences(),
                req.acknowledgedReferences() == null ? java.util.Set.of() : new java.util.HashSet<>(req.acknowledgedReferences()));
        MergePlan p = service.plan(ddl, req.master(), req.sources() == null ? List.of() : req.sources(), req.keys(), options);
        return summary(p);
    }

    @PostMapping("/execute")
    public Map<String, Object> execute(@RequestBody ExecuteRequest req) throws IOException {
        Set<String> approved = req.approvedAlters() == null ? Set.of() : new LinkedHashSet<>(req.approvedAlters());
        MergeResult r = service.execute(req.planId(), req.confirmation(), req.backupConfirmed(), approved);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", r.runId());
        body.put("status", r.status().name());
        body.put("message", r.message());
        body.put("counts", r.countsByTable());
        body.put("validations", r.validations());
        body.put("log", r.log());
        body.put("finalReport", "/api/reports/merge-" + r.runId() + "-final.html");
        body.put("logFile", "/api/reports/merge-" + r.runId() + "-final.txt");
        return body;
    }

    @GetMapping("/rules")
    public Rules rules() { return service.rules().get(); }

    @PostMapping("/rules/table")
    public Rules tableRule(@RequestBody TableRuleRequest req) {
        if (req.table() == null || req.action() == null) throw new IllegalArgumentException("table and action are required.");
        service.rules().get().getTables().put(req.table(), new Rules.TableRule(req.action(), req.dateColumn()));
        service.rules().save();
        return service.rules().get();
    }

    @PostMapping("/rules/row")
    public Rules rowRule(@RequestBody RowRuleRequest req) {
        if (req.table() == null || req.businessKey() == null || req.action() == null) throw new IllegalArgumentException("table, businessKey and action are required.");
        service.rules().get().getRows().computeIfAbsent(req.table(), k -> new LinkedHashMap<>())
                .put(req.businessKey(), new Rules.RowDecision(req.action(), req.manualValues()));
        service.rules().save();
        return service.rules().get();
    }

    @DeleteMapping("/rules")
    public Rules clearRules() {
        service.rules().clear();
        return service.rules().get();
    }

    private Map<String, Object> summary(MergePlan p) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("planId", p.id());
        body.put("master", p.master());
        body.put("sources", p.sources());
        body.put("hasBlockers", p.hasBlockers());
        body.put("fingerprint", p.fingerprint());
        body.put("confirmationPhrase", MergeService.confirmationPhrase(p.master()));
        body.put("backupReminder", com.dbmerge.merge.ScriptRenderer.BACKUP_REMINDER);
        body.put("issues", p.issues());
        body.put("alters", p.alters());
        List<Map<String, Object>> tables = new ArrayList<>();
        List<Map<String, Object>> unresolved = new ArrayList<>();
        for (TableStep s : p.steps()) {
            Map<String, Object> t = new LinkedHashMap<>();
            t.put("table", s.table());
            t.put("key", s.keyColumns());
            t.put("pkMode", s.pkMode().name());
            t.put("sequence", s.sequence());
            t.put("masterRows", s.masterRowsAtPlan());
            Map<String, Long> counts = new LinkedHashMap<>();
            for (RowAction a : RowAction.values()) if (s.count(a) > 0) counts.put(a.name(), s.count(a));
            t.put("counts", counts);
            tables.add(t);
            for (PlannedRow r : s.rows()) {
                if (r.action() != RowAction.UNRESOLVED_CONFLICT || unresolved.size() >= 200) continue;
                Map<String, Object> u = new LinkedHashMap<>();
                u.put("table", s.table());
                u.put("sourceDb", r.sourceDb());
                u.put("businessKey", r.businessKey());
                u.put("differing", r.changedColumns());
                u.put("sourceValues", r.values());
                u.put("note", r.note());
                unresolved.add(u);
            }
        }
        body.put("tables", tables);
        body.put("unresolved", unresolved);
        body.put("previewReport", "/api/reports/merge-" + p.id() + ".html");
        body.put("sqlScript", "/api/reports/merge-" + p.id() + ".sql");
        return body;
    }
}
