package com.dbmerge.cli;

import com.dbmerge.check.Estimate;
import com.dbmerge.check.Preflight;
import com.dbmerge.check.Preflight.Category;
import com.dbmerge.check.Preflight.ConnectionReport;
import com.dbmerge.check.ShareSummary;
import com.dbmerge.check.ShareSummary.Step;
import com.dbmerge.config.DbMergeProperties;
import com.dbmerge.conn.ConnectionRegistry;
import com.dbmerge.conn.ConnectionView;
import com.dbmerge.data.DataCompareService;
import com.dbmerge.data.DataExcelReportWriter;
import com.dbmerge.data.DataHtmlReportWriter;
import com.dbmerge.data.DataModel.DataReport;
import com.dbmerge.ddl.SchemaExtractor;
import com.dbmerge.ddl.compare.ComparisonResult;
import com.dbmerge.ddl.compare.SchemaComparator;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.SchemaSnapshot;
import com.dbmerge.ddl.report.ExcelReportWriter;
import com.dbmerge.ddl.report.HtmlReportWriter;
import com.dbmerge.merge.MergeModel.MergePlan;
import com.dbmerge.merge.MergeModel.Options;
import com.dbmerge.merge.MergeModel.OrphanPolicy;
import com.dbmerge.merge.MergeService;
import com.dbmerge.refs.RefModel.ReferenceReport;
import com.dbmerge.refs.ReferenceHtmlReportWriter;
import com.dbmerge.refs.ReferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * {@code cli bundle}: the read-only check, then every report a first look at a real database needs, into one
 * folder:
 *
 * <pre>
 * bundle-YYYYMMDD-HHMMSS/
 *   README.txt
 *   share/    summary.md, summary.json: counts and schema names only, no row values (see {@link ShareSummary})
 *   private/  the full reports (they show rows), the review script, schema snapshots, run.log
 * </pre>
 *
 * Nothing is written to any database: every step reads in a READ ONLY transaction and the merge step is a dry run.
 * If the read-only check fails, nothing else runs, unless that category of check is overridden explicitly.
 */
final class Bundle {

    /** Where a bundle goes: a new folder under the --out directory. */
    static Path root(CliArgs a) {
        Path base = Path.of(a.value("out") == null ? "reports" : a.value("out")).toAbsolutePath().normalize();
        return base.resolve("bundle-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
    }

    static Path privateDir(Path root) { return root.resolve("private"); }

    private final CliArgs a;
    private final ConfigurableApplicationContext ctx;
    private final ConnectionRegistry registry;
    private final PrintStream progress;
    private final Path root;
    private final Path share;
    private final Path priv;
    private final List<Step> steps = new ArrayList<>();
    private final StringBuilder log = new StringBuilder();

    Bundle(CliArgs a, ConfigurableApplicationContext ctx, ConnectionRegistry registry, PrintStream progress, Path root) {
        this.a = a;
        this.ctx = ctx;
        this.registry = registry;
        this.progress = progress;
        this.root = root;
        this.share = root.resolve("share");
        this.priv = privateDir(root);
    }

    @FunctionalInterface
    private interface Work<T> { T run() throws Exception; }

    /** Runs one step: times it, logs the full error privately and keeps only a data-free description for share/. */
    private <T> T step(String name, Work<T> w) {
        progress.println("[bundle] " + name + " ...");
        long t0 = System.nanoTime();
        try {
            T out = w.run();
            long s = (System.nanoTime() - t0) / 1_000_000_000;
            steps.add(new Step(name, "done", s, null));
            log.append(name).append(": done in ").append(s).append(" s\n");
            return out;
        } catch (Exception e) {
            long s = (System.nanoTime() - t0) / 1_000_000_000;
            steps.add(new Step(name, "FAILED", s, ShareSummary.safeError(e)));
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            log.append(name).append(": FAILED after ").append(s).append(" s\n").append(sw).append('\n');
            progress.println("[bundle] " + name + " failed: " + ShareSummary.safeError(e) + " (details in private/run.log)");
            return null;
        }
    }

    private void skipped(String name, String why) {
        steps.add(new Step(name, "skipped: " + why, 0, null));
        log.append(name).append(": skipped, ").append(why).append('\n');
    }

    Cli.Result run() throws IOException {
        String master = a.value("master");
        List<String> sources = a.positional();
        List<String> all = new ArrayList<>();
        all.add(master);
        all.addAll(sources);
        ownerOnly(Files.createDirectories(root));
        ownerOnly(Files.createDirectories(share));
        ownerOnly(Files.createDirectories(priv));
        DbMergeProperties props = ctx.getBean(DbMergeProperties.class);
        int maxRows = props.getMaxRowsPerTable();

        // 1. read-only check: before anything reads table data
        Map<String, String> targets = new LinkedHashMap<>();
        for (ConnectionView v : registry.list()) targets.put(v.nickname(), v.host() + ":" + v.port() + "/" + v.serviceName());
        Preflight preflight = new Preflight(registry, maxRows);
        List<ConnectionReport> reports = step("read-only check", () -> {
            List<ConnectionReport> out = new ArrayList<>();
            for (String n : all) out.add(preflight.check(n, targets.get(n)));
            return out;
        });
        Estimate estimate = reports == null ? null : Estimate.of(reports.get(0), reports.subList(1, reports.size()), maxRows, root);
        List<String> refusal = reports == null ? List.of("the read-only check itself failed") : refusal(reports, estimate);

        ComparisonResult ddl = null;
        List<ReferenceReport> refs = null;
        DataReport data = null;
        MergePlan plan = null;
        if (refusal.isEmpty()) {
            // 2. structure, read once and reused by every later step
            List<SchemaModel> models = step("read schemas", () -> {
                List<SchemaModel> out = new ArrayList<>();
                SchemaExtractor ex = ctx.getBean(SchemaExtractor.class);
                for (String n : all) {
                    SchemaModel m = ex.extract(n, true);
                    out.add(m);
                    SchemaSnapshot.write(a.filter().apply(m), a.filter().describe(), priv.resolve("snapshot-" + n + ".json"));
                }
                return out;
            });
            if (models != null) {
                ddl = step("schema comparison", () -> {
                    ComparisonResult r = ctx.getBean(SchemaComparator.class).compare(models, a.filter());
                    Files.writeString(priv.resolve("ddl-" + r.id() + ".html"), ctx.getBean(HtmlReportWriter.class).render(r), StandardCharsets.UTF_8);
                    ctx.getBean(ExcelReportWriter.class).write(r, priv.resolve("ddl-" + r.id() + ".xlsx"));
                    return r;
                });
            } else {
                skipped("schema comparison", "schemas could not be read");
            }
            refs = step("undeclared references", () -> {
                List<ReferenceReport> out = new ArrayList<>();
                for (String n : all) {
                    ReferenceReport r = ctx.getBean(ReferenceService.class).run(n, a.filter(), !a.has("no-check-data"), 60, 1_000);
                    Files.writeString(priv.resolve("refs-" + n + "-" + r.id() + ".html"), ctx.getBean(ReferenceHtmlReportWriter.class).render(r),
                            StandardCharsets.UTF_8);
                    out.add(r);
                }
                return out;
            });
            final ComparisonResult cmp = ddl;
            if (a.has("skip-data")) skipped("data comparison", "--skip-data");
            else if (cmp == null) skipped("data comparison", "no schema comparison");
            else {
                progress.println("[bundle] data comparison: estimated " + Estimate.duration(estimate.dataSeconds()));
                data = step("data comparison", () -> {
                    DataReport r = ctx.getBean(DataCompareService.class).run(cmp.id(), cmp.models(), a.keys(), a.tables(), props.getSampleLimit());
                    Files.writeString(priv.resolve("data-" + r.id() + ".html"), ctx.getBean(DataHtmlReportWriter.class).render(r), StandardCharsets.UTF_8);
                    ctx.getBean(DataExcelReportWriter.class).write(r, priv.resolve("data-" + r.id() + ".xlsx"));
                    return r;
                });
            }
            if (a.has("skip-plan")) skipped("merge dry run", "--skip-plan");
            else if (cmp == null) skipped("merge dry run", "no schema comparison");
            else {
                progress.println("[bundle] merge dry run: estimated " + Estimate.duration(estimate.planSeconds()));
                plan = step("merge dry run", () -> {
                    Options options = new Options(false, OrphanPolicy.valueOf(a.orphans()), a.sequences(), a.acknowledged());
                    MergeService service = ctx.getBean(MergeService.class);
                    MergePlan p = service.plan(cmp, master, sources, a.keys(), options);
                    service.forget(p.id()); // the reports are written; the plan files (row data) are not needed
                    return p;
                });
            }
        } else {
            for (String s : List.of("read schemas", "schema comparison", "undeclared references", "data comparison", "merge dry run")) {
                skipped(s, "the read-only check did not pass");
            }
        }

        // 3. the summary that may be shared, the private log, and the README
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("filter", a.filter().describe());
        options.put("keys", a.keys());
        options.put("orphans", a.orphans());
        options.put("maxRows", maxRows);
        options.put("referenceDataCheck", !a.has("no-check-data"));
        List<String> overrides = new ArrayList<>();
        if (a.has("allow-write-access")) overrides.add("allow-write-access");
        if (a.has("allow-incomplete")) overrides.add("allow-incomplete");
        options.put("overrides", overrides);
        String version = Bundle.class.getPackage().getImplementationVersion();
        ShareSummary.Input in = new ShareSummary.Input(OffsetDateTime.now().withNano(0).toString(), version == null ? "dev" : version,
                options, steps, master, sources, reports, estimate, ddl, refs, data, plan);
        Map<String, Object> summary = ShareSummary.build(in);
        if (!refusal.isEmpty()) summary.put("refused", refusal);
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        json.findAndRegisterModules();
        String md = ShareSummary.markdown(summary);
        if (!refusal.isEmpty()) {
            md = md.replaceFirst("\n## Steps", "\n## Stopped by the read-only check\n\n* " + String.join("\n* ", refusal) + "\n\n## Steps");
        }
        lockDown(priv);
        write(share.resolve("summary.json"), json.writeValueAsString(summary));
        write(share.resolve("summary.md"), md);
        write(priv.resolve("run.log"), log.toString());
        write(root.resolve("README.txt"), readme(refusal.isEmpty()));

        boolean failed = steps.stream().anyMatch(s -> s.status().equals("FAILED"));
        int code = !refusal.isEmpty() ? ExitCodes.FINDINGS : failed ? ExitCodes.RUNTIME : ExitCodes.OK;
        List<String> lines = new ArrayList<>();
        lines.add("Report bundle: " + String.join(", ", sources) + " -> " + master + " (nothing was written to any database)");
        if (reports != null) {
            for (ConnectionReport r : reports) {
                lines.add("  " + r.nickname() + ": " + r.status() + (r.error() != null ? "  " + r.error()
                        : "  (" + r.schema() + " as " + r.sessionUser() + " on " + r.dbName() + "/" + r.container() + ")"));
                for (Preflight.Check c : r.checks()) if (c.status() != Preflight.Status.OK) lines.add("      " + c.status() + "  " + c.name() + ": " + c.detail());
            }
        }
        if (estimate != null) {
            for (Preflight.Check c : estimate.checks()) if (c.status() != Preflight.Status.OK) lines.add("  " + c.status() + "  " + c.name() + ": " + c.detail());
        }
        if (!refusal.isEmpty()) {
            lines.add("  STOPPED before reading any data:");
            refusal.forEach(r -> lines.add("    " + r));
        }
        for (Step s : steps) {
            lines.add(String.format("  %-22s %-40s %s", s.name(), s.status(), s.status().startsWith("skipped") ? "" : Estimate.duration(s.seconds()))
                    + (s.error() == null ? "" : "  " + s.error()));
        }
        lines.add("  share:   " + share + "  (counts and names only; read it before sending)");
        lines.add("  private: " + priv + "  (row values: keep it inside your organisation)");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("command", "bundle");
        out.put("folder", root.toString());
        out.put("refused", refusal);
        out.put("steps", summary.get("steps"));
        return new Cli.Result(code, lines, out);
    }

    /** Why the bundle must stop after the read-only check; empty when it may go on. */
    private List<String> refusal(List<ConnectionReport> reports, Estimate estimate) {
        List<String> why = new ArrayList<>();
        for (ConnectionReport r : reports) {
            if (r.error() != null) { why.add(r.nickname() + ": " + r.error()); continue; }
            if (r.failed(Category.WRITE) && !a.has("allow-write-access")) {
                why.add(r.nickname() + ": this login can change the schema (pass --allow-write-access to go on anyway; the tool still only reads)");
            }
            if (r.failed(Category.VISIBILITY) && !a.has("allow-incomplete")) {
                why.add(r.nickname() + ": part of the schema is not visible or readable, so differences could be hidden (pass --allow-incomplete to go on anyway)");
            }
            if (r.failed(Category.SUPPORT)) why.add(r.nickname() + ": the database lacks something the comparison needs (see the checks)");
        }
        if (!a.has("skip-plan") && estimate.checks().stream().anyMatch(c -> c.status() == Preflight.Status.FAIL)) {
            why.add("not enough disk space for the dry run's plan files (free space, use --out elsewhere, or --skip-plan)");
        }
        return why;
    }

    private static String readme(boolean ran) {
        return """
                oracle-db-merge report bundle
                =============================

                Nothing was written to any database: every step read in a READ ONLY transaction, and the merge step
                is a dry run.%s

                share/      summary.md and summary.json
                            Counts, check results, schema object names and short descriptions of schema differences.
                            No row values, keys, sample rows, host names or passwords.
                            It still names your tables, columns and PL/SQL objects: read it before sending it on.

                private/    the full reports
                            ddl-*.html/.xlsx   schema comparison, with DDL text and PL/SQL source
                            refs-*.html        undeclared references, with example values
                            data-*.html/.xlsx  data comparison, WITH SAMPLE ROWS
                            merge-*.html/.sql  dry-run plan and review script, WITH ROW VALUES
                            snapshot-*.json    schema structure and source code (no rows)
                            run.log            timings and full error messages
                            Keep this folder inside your organisation.
                """.formatted(ran ? "" : "\n\nThe read-only check did not pass, so only that check ran: see share/summary.md.");
    }

    private static void write(Path p, String text) throws IOException {
        Files.writeString(p, text, StandardCharsets.UTF_8);
        ownerOnly(p);
    }

    /** Makes every report owner-only and drops the dry run's plan folder once it is empty. */
    private static void lockDown(Path dir) throws IOException {
        Path plans = dir.resolve("plans");
        if (Files.isDirectory(plans)) {
            try (var s = Files.list(plans)) {
                if (s.findAny().isEmpty()) Files.delete(plans);
            }
        }
        try (var s = Files.walk(dir)) {
            for (Path p : s.toList()) ownerOnly(p);
        }
    }

    /** rwx------ / rw------- where the file system supports it. */
    static Path ownerOnly(Path p) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(Files.isDirectory(p) ? "rwx------" : "rw-------"));
        }
        return p;
    }
}
