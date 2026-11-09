package com.dbmerge.merge;

import com.dbmerge.conn.ConnectionSource;
import com.dbmerge.ddl.compare.ComparisonResult;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.merge.MergeModel.MergePlan;
import com.dbmerge.merge.MergeModel.MergeResult;
import com.dbmerge.merge.MergeModel.Options;
import com.dbmerge.merge.MergeModel.RunStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Orchestrates dry-run planning and the guarded execution. Planning never writes to any database. */
public class MergeService {

    public static String confirmationPhrase(String master) {
        return "MERGE INTO " + master;
    }

    private static final int KEEP = 3;

    private final ConnectionSource connections;
    private final int maxRows;
    private final Path reportsDir;
    private final RuleStore ruleStore;
    private final MergeReportWriter reports = new MergeReportWriter();
    private final Map<String, MergePlan> plans = new LinkedHashMap<>();
    private final Set<String> executed = new LinkedHashSet<>();

    public MergeService(ConnectionSource connections, int maxRows, Path reportsDir, RuleStore ruleStore) {
        this.connections = connections;
        this.maxRows = maxRows;
        this.reportsDir = reportsDir;
        this.ruleStore = ruleStore;
    }

    public RuleStore rules() { return ruleStore; }

    /** Dry run: reads the master and the sources, builds the plan, writes the review script and preview report. */
    public synchronized MergePlan plan(ComparisonResult ddl, String master, List<String> sources, Map<String, List<String>> keys, Options options)
            throws SQLException, IOException {
        Map<String, SchemaModel> models = new LinkedHashMap<>();
        for (SchemaModel m : ddl.models()) models.put(m.nickname(), m);
        // Tables above maxRows are streamed and their plan kept in files under reports/plans (owner-only).
        MergePlan plan = new MergePlanner(new JdbcMergeData(connections, maxRows), maxRows, reportsDir.resolve("plans"))
                .plan(master, sources, models, keys, ruleStore.get(), options);
        plans.put(plan.id(), plan);
        while (plans.size() > KEEP) discard(plans.remove(plans.keySet().iterator().next()));
        Files.createDirectories(reportsDir);
        Files.writeString(reportsDir.resolve("merge-" + plan.id() + ".html"), reports.renderPlan(plan), StandardCharsets.UTF_8);
        Files.writeString(reportsDir.resolve("merge-" + plan.id() + ".sql"), ScriptRenderer.render(plan, allAlterIds(plan)), StandardCharsets.UTF_8);
        return plan;
    }

    public synchronized MergePlan get(String id) {
        MergePlan p = plans.get(id);
        if (p == null) throw new IllegalArgumentException("Unknown or expired plan: " + id + ". Run the dry run again.");
        return p;
    }

    public synchronized MergeResult execute(String planId, String confirmation, boolean backupConfirmed, Set<String> approvedAlters) throws IOException {
        MergePlan plan = get(planId);
        if (!backupConfirmed) return refuse(plan, "Confirm that you have taken a Data Pump backup of the master first.");
        if (!confirmationPhrase(plan.master()).equals(confirmation == null ? "" : confirmation.trim())) {
            return refuse(plan, "Confirmation text does not match. Type exactly: " + confirmationPhrase(plan.master()));
        }
        if (executed.contains(planId)) return refuse(plan, "This plan was already executed. Run a new dry run.");
        MergeResult result = new MergeExecutor(connections).execute(plan, approvedAlters == null ? Set.of() : approvedAlters);
        if (result.status() != RunStatus.REFUSED) {
            executed.add(planId);
        }
        Files.createDirectories(reportsDir);
        Files.writeString(reportsDir.resolve("merge-" + plan.id() + "-final.html"), reports.renderResult(plan, result), StandardCharsets.UTF_8);
        Files.writeString(reportsDir.resolve("merge-" + plan.id() + "-final.txt"), String.join("\n", result.log()), StandardCharsets.UTF_8);
        if (executed.contains(planId)) discard(plan); // a plan runs once; its rows are not needed any more
        return result;
    }

    /** Deletes a plan's files (large tables); the plan cannot be read or run afterwards. */
    private static void discard(MergePlan plan) {
        for (var s : plan.steps()) if (s.rows() instanceof SpooledRows spooled) spooled.delete();
    }

    private MergeResult refuse(MergePlan plan, String why) {
        return new MergeResult(plan.id(), RunStatus.REFUSED, why, Map.of(), List.of(), List.of("REFUSED: " + why));
    }

    private static Set<String> allAlterIds(MergePlan p) {
        Set<String> ids = new LinkedHashSet<>();
        p.alters().forEach(a -> ids.add(a.id()));
        return ids;
    }
}
