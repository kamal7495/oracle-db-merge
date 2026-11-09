package com.dbmerge.cli;

import com.dbmerge.DbMergeApplication;
import com.dbmerge.cli.CliArgs.Command;
import com.dbmerge.config.DbMergeProperties;
import com.dbmerge.conn.ConnectionRegistry;
import com.dbmerge.conn.ConnectionRequest;
import com.dbmerge.conn.ConnectionView;
import com.dbmerge.conn.TestResult;
import com.dbmerge.data.DataCompareService;
import com.dbmerge.data.DataExcelReportWriter;
import com.dbmerge.data.DataHtmlReportWriter;
import com.dbmerge.data.DataModel.DataReport;
import com.dbmerge.data.DataModel.TableDataResult;
import com.dbmerge.ddl.SchemaExtractor;
import com.dbmerge.ddl.compare.ComparisonResult;
import com.dbmerge.ddl.compare.Difference;
import com.dbmerge.ddl.compare.SchemaComparator;
import com.dbmerge.ddl.compare.Severity;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.report.ExcelReportWriter;
import com.dbmerge.ddl.report.HtmlReportWriter;
import com.dbmerge.merge.MergeModel.Issue;
import com.dbmerge.merge.MergeModel.Level;
import com.dbmerge.merge.MergeModel.MergePlan;
import com.dbmerge.merge.MergeModel.Options;
import com.dbmerge.merge.MergeModel.OrphanPolicy;
import com.dbmerge.merge.MergeModel.RowAction;
import com.dbmerge.merge.MergeModel.TableStep;
import com.dbmerge.merge.MergeService;
import com.dbmerge.refs.RefModel.Candidate;
import com.dbmerge.refs.RefModel.Confidence;
import com.dbmerge.refs.RefModel.ReferenceReport;
import com.dbmerge.refs.ReferenceHtmlReportWriter;
import com.dbmerge.refs.ReferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Command-line mode: {@code java -jar oracle-db-merge.jar cli <command> ...}. Runs a comparison, report or merge
 * dry run without the web server and exits with a code a CI job can act on (see {@link ExitCodes}).
 * There is deliberately no command that executes a merge.
 *
 * <p>stdout carries only the summary (or the JSON with {@code --json}); Spring's own log lines go to stderr.
 */
public final class Cli {

    private static final int MAX_LINES = 20;

    private final PrintStream out;
    private final PrintStream err;
    private final UnaryOperator<String> env;

    Cli(PrintStream out, PrintStream err, UnaryOperator<String> env) {
        this.out = out;
        this.err = err;
        this.env = env;
    }

    /** Entry point used by {@link DbMergeApplication#main}. */
    public static int main(String[] args) {
        PrintStream stdout = System.out;
        System.setOut(System.err); // anything else that prints (Spring, logback) goes to stderr
        try {
            return new Cli(stdout, System.err, System::getenv).run(args);
        } finally {
            stdout.flush();
            System.setOut(stdout);
        }
    }

    int run(String[] args) {
        CliArgs a;
        try {
            a = CliArgs.parse(args);
        } catch (UsageException e) {
            err.println("error: " + e.getMessage());
            err.println("Run 'cli help' for usage.");
            return ExitCodes.USAGE;
        }
        if (a.help()) {
            out.print(CliHelp.text(a.command()));
            return ExitCodes.OK;
        }
        for (String f : new String[] {"config", "rules"}) {
            if (a.value(f) != null && !Files.isRegularFile(Path.of(a.value(f)))) {
                err.println("error: --" + f + " file not found: " + a.value(f));
                return ExitCodes.USAGE;
            }
        }
        Path outDir = Path.of(a.value("out") == null ? "reports" : a.value("out")).toAbsolutePath().normalize();
        try (ConfigurableApplicationContext ctx = startContext(a, outDir)) {
            ConnectionRegistry registry = ctx.getBean(ConnectionRegistry.class);
            applyPasswordEnv(a, registry, ctx.getBean(DbMergeProperties.class));
            requireUsable(a, registry);
            Result r = switch (a.command()) {
                case CONNECTIONS -> connections(a, registry);
                case DDL -> ddl(a, ctx, outDir);
                case DATA -> data(a, ctx, outDir);
                case REFS -> refs(a, ctx, outDir);
                case PLAN -> plan(a, ctx);
                case HELP -> throw new IllegalStateException();
            };
            print(a, r);
            return r.exitCode();
        } catch (UsageException e) {
            err.println("error: " + e.getMessage());
            return ExitCodes.USAGE;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().lines().findFirst().orElse("");
            err.println("error: " + msg);
            return ExitCodes.RUNTIME;
        }
    }

    /** Spring context with no web server; reports go to {@code outDir}. */
    static ConfigurableApplicationContext startContext(CliArgs a, Path outDir) {
        SpringApplication app = new SpringApplication(DbMergeApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        List<String> props = new ArrayList<>(List.of(
                "--spring.main.web-application-type=none",
                "--logging.level.root=WARN",
                "--dbmerge.reports-dir=" + outDir));
        if (a.value("store-dir") != null) props.add("--dbmerge.store-dir=" + a.value("store-dir"));
        if (a.value("profile") != null) props.add("--spring.profiles.active=" + a.value("profile"));
        if (a.value("config") != null) props.add("--spring.config.additional-location=file:" + Path.of(a.value("config")).toAbsolutePath());
        if (a.value("rules") != null) props.add("--dbmerge.rules-file=" + Path.of(a.value("rules")).toAbsolutePath());
        return app.run(props.toArray(String[]::new));
    }

    /**
     * Gives connections their password from the named environment variables. A configured (preloaded) entry
     * without a password is skipped at start-up, so it is registered here with the variable's value.
     */
    private void applyPasswordEnv(CliArgs a, ConnectionRegistry registry, DbMergeProperties props) {
        for (Map.Entry<String, String> e : a.passwordEnv().entrySet()) {
            String nick = e.getKey();
            boolean registered = registry.list().stream().anyMatch(v -> v.nickname().equals(nick));
            DbMergeProperties.Preload preload = props.getPreload().stream()
                    .filter(p -> nick.equals(p.getNickname())).findFirst().orElse(null);
            if (!registered && preload == null) {
                throw new UsageException("--password-env names unknown connection '" + nick + "'." + known(registry));
            }
            String pw = env.apply(e.getValue());
            if (pw == null || pw.isEmpty()) throw new UsageException("Environment variable " + e.getValue() + " is not set or empty.");
            if (registered) {
                registry.unlock(nick, pw.toCharArray(), false);
            } else {
                registry.add(new ConnectionRequest(preload.getNickname(), preload.getHost(), preload.getPort(),
                        preload.getServiceName(), preload.getUsername(), pw.toCharArray(), preload.getSchema()));
            }
        }
    }

    private static void requireUsable(CliArgs a, ConnectionRegistry registry) {
        List<String> needed = new ArrayList<>(a.positional());
        if (a.value("master") != null) needed.add(0, a.value("master"));
        Map<String, ConnectionView> byName = new LinkedHashMap<>();
        for (ConnectionView v : registry.list()) byName.put(v.nickname(), v);
        for (String n : needed) {
            ConnectionView v = byName.get(n);
            if (v == null) throw new UsageException("Unknown connection '" + n + "'." + known(registry));
            if (v.locked()) {
                throw new UsageException("Connection '" + n + "' has no saved password. Put it in an environment variable"
                        + " and pass --password-env " + n + "=VARIABLE.");
            }
        }
    }

    private static String known(ConnectionRegistry registry) {
        List<String> names = registry.list().stream().map(ConnectionView::nickname).toList();
        return names.isEmpty() ? " No connections are saved or configured (save one in the UI, or use --profile/--config)."
                : " Known: " + String.join(", ", names) + ".";
    }

    // ---------------------------------------------------------------- commands

    /** Outcome of a command: exit code, human summary lines, and the JSON body. */
    record Result(int exitCode, List<String> lines, Map<String, Object> json) { }

    private Result connections(CliArgs a, ConnectionRegistry registry) {
        List<String> lines = new ArrayList<>();
        List<Map<String, Object>> list = new ArrayList<>();
        boolean failed = false;
        for (ConnectionView v : registry.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nickname", v.nickname());
            m.put("target", v.host() + ":" + v.port() + "/" + v.serviceName());
            m.put("user", v.username());
            m.put("schema", v.schema());
            m.put("saved", v.saved());
            m.put("passwordSaved", v.passwordSaved());
            m.put("locked", v.locked());
            String line = String.format("%-16s %-40s %s / %s%s%s", v.nickname(), m.get("target"), v.username(), v.schema(),
                    v.saved() ? "  [saved]" : "  [config]", v.locked() ? " [locked]" : "");
            if (a.has("test")) {
                TestResult t = registry.test(v.nickname());
                m.put("ok", t.ok());
                m.put("message", t.message());
                failed |= !t.ok();
                line += "  " + (t.ok() ? "OK" : "FAILED: " + t.message());
            }
            list.add(m);
            lines.add(line);
        }
        if (lines.isEmpty()) lines.add("No connections saved or configured.");
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("connections", list);
        return new Result(failed ? ExitCodes.RUNTIME : ExitCodes.OK, lines, json);
    }

    private static List<SchemaModel> extract(ConfigurableApplicationContext ctx, List<String> nicks, boolean ddlText) throws Exception {
        SchemaExtractor extractor = ctx.getBean(SchemaExtractor.class);
        List<SchemaModel> models = new ArrayList<>();
        for (String n : nicks) models.add(extractor.extract(n, ddlText));
        return models;
    }

    private Result ddl(CliArgs a, ConfigurableApplicationContext ctx, Path outDir) throws Exception {
        List<SchemaModel> models = extract(ctx, a.positional(), !a.has("no-ddl-text"));
        ComparisonResult result = ctx.getBean(SchemaComparator.class).compare(models);
        Files.createDirectories(outDir);
        Path html = outDir.resolve("ddl-" + result.id() + ".html");
        Path xlsx = outDir.resolve("ddl-" + result.id() + ".xlsx");
        Files.writeString(html, ctx.getBean(HtmlReportWriter.class).render(result), StandardCharsets.UTF_8);
        ctx.getBean(ExcelReportWriter.class).write(result, xlsx);

        Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) counts.put(s, 0);
        counts.putAll(result.countsBySeverity());
        ExitCodes.Threshold threshold = a.ddlThreshold();
        int code = ExitCodes.ddl(counts, threshold);

        List<String> lines = new ArrayList<>();
        lines.add("DDL comparison " + result.id() + ": " + String.join(" vs ", a.positional()));
        for (SchemaModel m : models) {
            lines.add("  " + m.nickname() + " (" + m.owner() + "): " + m.tables().size() + " tables, " + m.sequences().size() + " sequences"
                    + (m.warnings().isEmpty() ? "" : ", " + m.warnings().size() + " warning(s)"));
        }
        lines.add("  " + result.differences().size() + " difference(s): " + counts);
        result.differences().stream().limit(MAX_LINES).forEach(d ->
                lines.add("    " + d.severity() + "  " + d.kind().label() + "  " + d.table() + (d.object() == null ? "" : "." + d.object())));
        if (result.differences().size() > MAX_LINES) lines.add("    ... see the report for the rest");
        lines.add("  fail-on " + threshold + " -> " + (code == ExitCodes.OK ? "pass" : "FAIL"));
        lines.add("  report: " + html);
        lines.add("  excel:  " + xlsx);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("command", "ddl");
        json.put("id", result.id());
        json.put("databases", a.positional());
        Map<String, Integer> sev = new LinkedHashMap<>();
        counts.forEach((k, v) -> sev.put(k.name(), v));
        json.put("severityCounts", sev);
        json.put("differenceCount", result.differences().size());
        List<Map<String, Object>> diffs = new ArrayList<>();
        for (Difference d : result.differences()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("severity", d.severity().name());
            m.put("kind", d.kind().label());
            m.put("table", d.table());
            m.put("object", d.object());
            m.put("description", d.description());
            diffs.add(m);
        }
        json.put("differences", diffs);
        json.put("failOn", threshold.name());
        json.put("reports", List.of(html.toString(), xlsx.toString()));
        return new Result(code, lines, json);
    }

    private Result data(CliArgs a, ConfigurableApplicationContext ctx, Path outDir) throws Exception {
        List<SchemaModel> models = extract(ctx, a.positional(), false);
        ComparisonResult ddl = ctx.getBean(SchemaComparator.class).compare(models);
        DataReport report = ctx.getBean(DataCompareService.class).run(ddl.id(), ddl.models(), a.keys(), a.tables(),
                ctx.getBean(DbMergeProperties.class).getSampleLimit());
        Files.createDirectories(outDir);
        Path html = outDir.resolve("data-" + report.id() + ".html");
        Path xlsx = outDir.resolve("data-" + report.id() + ".xlsx");
        Files.writeString(html, ctx.getBean(DataHtmlReportWriter.class).render(report), StandardCharsets.UTF_8);
        ctx.getBean(DataExcelReportWriter.class).write(report, xlsx);
        int code = ExitCodes.data(report.tables(), a.has("allow-uncompared"));

        List<String> lines = new ArrayList<>();
        lines.add("Data comparison " + report.id() + ": " + String.join(" vs ", a.positional()) + ", " + report.tables().size() + " table(s)");
        List<Map<String, Object>> tables = new ArrayList<>();
        int shown = 0;
        for (TableDataResult t : report.tables()) {
            boolean differs = ExitCodes.differs(t);
            boolean uncompared = ExitCodes.uncompared(t);
            if ((differs || uncompared) && shown++ < MAX_LINES) {
                lines.add("    " + t.table() + "  " + t.status() + "  rows " + t.rowCounts() + "  only-in-one " + t.onlyInTotal()
                        + "  conflicts " + t.conflictCount() + "  pk-collisions " + t.pkCollisionCount()
                        + (t.message() == null ? "" : "  (" + t.message() + ")"));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", t.table());
            m.put("status", t.status().name());
            m.put("message", t.message());
            m.put("key", t.keyColumns());
            m.put("rowCounts", t.rowCounts());
            m.put("onlyInOne", t.onlyInTotal());
            m.put("identical", t.identicalCount());
            m.put("conflicts", t.conflictCount());
            m.put("pkCollisions", t.pkCollisionCount());
            m.put("samePkDifferentKey", t.pkDivergenceCount());
            m.put("differs", differs);
            m.put("compared", !uncompared);
            tables.add(m);
        }
        if (shown == 0) lines.add("  all tables match");
        else if (shown > MAX_LINES) lines.add("    ... " + (shown - MAX_LINES) + " more, see the report");
        lines.add("  " + (code == ExitCodes.OK ? "pass" : "FAIL"));
        lines.add("  report: " + html);
        lines.add("  excel:  " + xlsx);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("command", "data");
        json.put("id", report.id());
        json.put("databases", a.positional());
        json.put("tables", tables);
        json.put("reports", List.of(html.toString(), xlsx.toString()));
        return new Result(code, lines, json);
    }

    private Result refs(CliArgs a, ConfigurableApplicationContext ctx, Path outDir) throws Exception {
        String nick = a.positional().get(0);
        ReferenceReport r = ctx.getBean(ReferenceService.class).run(nick, a.has("check-data"),
                a.intOption("timeout", 60, 1, 3_600), a.intOption("max-checks", 1_000, 1, 100_000));
        Files.createDirectories(outDir);
        Path html = outDir.resolve("refs-" + r.id() + ".html");
        Files.writeString(html, ctx.getBean(ReferenceHtmlReportWriter.class).render(r), StandardCharsets.UTF_8);

        Map<Confidence, Long> counts = new EnumMap<>(Confidence.class);
        for (Confidence c : Confidence.values()) counts.put(c, r.count(c));
        ExitCodes.Threshold threshold = a.refsThreshold();
        int code = ExitCodes.refs(counts, threshold);

        List<String> lines = new ArrayList<>();
        lines.add("Undeclared references " + r.id() + ": " + nick + " (" + r.owner() + "), " + r.tableCount() + " tables, "
                + r.declaredForeignKeys() + " declared foreign key(s)" + (r.dataChecked() ? ", data checked" : ""));
        lines.add("  " + r.candidates().size() + " candidate(s): " + counts);
        r.candidates().stream().limit(MAX_LINES).forEach(c ->
                lines.add("    " + c.confidence() + "  " + c.qualifiedColumn() + " -> " + String.join(", ", c.parentTables())));
        if (r.candidates().size() > MAX_LINES) lines.add("    ... see the report for the rest");
        if (!r.warnings().isEmpty()) lines.add("  " + r.warnings().size() + " warning(s), see the report");
        lines.add("  fail-on " + threshold + " -> " + (code == ExitCodes.OK ? "pass" : "FAIL"));
        lines.add("  report: " + html);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("command", "refs");
        json.put("id", r.id());
        json.put("nickname", nick);
        json.put("schema", r.owner());
        json.put("dataChecked", r.dataChecked());
        Map<String, Long> conf = new LinkedHashMap<>();
        counts.forEach((k, v) -> conf.put(k.name(), v));
        json.put("confidenceCounts", conf);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Candidate c : r.candidates()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("confidence", c.confidence().name());
            m.put("kind", c.kind().name());
            m.put("column", c.qualifiedColumn());
            m.put("parents", c.parentTables());
            m.put("rule", c.rule());
            list.add(m);
        }
        json.put("candidates", list);
        json.put("warnings", r.warnings());
        json.put("failOn", threshold.name());
        json.put("reports", List.of(html.toString()));
        return new Result(code, lines, json);
    }

    /** Dry run only. Nothing is written to any database; there is no execute command. */
    private Result plan(CliArgs a, ConfigurableApplicationContext ctx) throws Exception {
        String master = a.value("master");
        List<String> all = new ArrayList<>();
        all.add(master);
        all.addAll(a.positional());
        List<SchemaModel> models = extract(ctx, all, true);
        ComparisonResult ddl = ctx.getBean(SchemaComparator.class).compare(models);
        Options options = new Options(false, OrphanPolicy.valueOf(a.orphans()), a.sequences(), a.acknowledged());
        MergeService service = ctx.getBean(MergeService.class);
        MergePlan p = service.plan(ddl, master, a.positional(), a.keys(), options);
        int code = ExitCodes.plan(p, a.has("fail-on-warnings"));
        Path dir = Path.of(ctx.getBean(DbMergeProperties.class).getReportsDir());
        Path html = dir.resolve("merge-" + p.id() + ".html");
        Path sql = dir.resolve("merge-" + p.id() + ".sql");

        List<String> lines = new ArrayList<>();
        lines.add("Merge dry run " + p.id() + ": " + String.join(", ", a.positional()) + " -> " + master + " (nothing was written)");
        List<Map<String, Object>> tables = new ArrayList<>();
        for (TableStep s : p.steps()) {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (RowAction ra : RowAction.values()) if (s.count(ra) > 0) counts.put(ra.name(), s.count(ra));
            lines.add("    " + s.table() + "  " + counts);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", s.table());
            m.put("counts", counts);
            tables.add(m);
        }
        Map<Level, Long> byLevel = new EnumMap<>(Level.class);
        for (Issue i : p.issues()) byLevel.merge(i.level(), 1L, Long::sum);
        lines.add("  issues: " + byLevel + (p.alters().isEmpty() ? "" : ", " + p.alters().size() + " schema change(s) proposed"));
        p.issues().stream().filter(i -> i.level() == Level.BLOCKER).limit(MAX_LINES)
                .forEach(i -> lines.add("    BLOCKER  " + (i.table() == null ? "" : i.table() + ": ") + i.message()));
        lines.add("  " + (code == ExitCodes.OK ? "pass" : "FAIL") + (p.hasBlockers() ? " (blockers)" : ""));
        lines.add("  report: " + html);
        lines.add("  script: " + sql + " (review copy; ends in ROLLBACK)");

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("command", "plan");
        json.put("planId", p.id());
        json.put("master", master);
        json.put("sources", a.positional());
        json.put("hasBlockers", p.hasBlockers());
        json.put("fingerprint", p.fingerprint());
        json.put("tables", tables);
        List<Map<String, Object>> issues = new ArrayList<>();
        for (Issue i : p.issues()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("level", i.level().name());
            m.put("table", i.table());
            m.put("message", i.message());
            issues.add(m);
        }
        json.put("issues", issues);
        json.put("alterCount", p.alters().size());
        json.put("reports", List.of(html.toString(), sql.toString()));
        return new Result(code, lines, json);
    }

    // ------------------------------------------------------------------ output

    private void print(CliArgs a, Result r) throws Exception {
        if (a.has("json")) {
            Map<String, Object> body = new LinkedHashMap<>(r.json());
            body.put("exitCode", r.exitCode());
            out.println(new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(body));
        } else {
            r.lines().forEach(out::println);
        }
    }
}
