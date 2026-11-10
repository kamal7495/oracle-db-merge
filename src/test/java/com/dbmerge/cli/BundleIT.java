package com.dbmerge.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.SqlScriptRunner;
import com.dbmerge.conn.ConnectionRegistry;
import com.dbmerge.conn.ConnectionRequest;
import com.dbmerge.ddl.SchemaExtractor;
import com.dbmerge.ddl.compare.ComparisonResult;
import com.dbmerge.ddl.compare.CompareFilter;
import com.dbmerge.ddl.compare.SchemaComparator;
import com.dbmerge.ddl.model.SchemaModel;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

/**
 * The read-only check and the report bundle against real Oracle users: schema owners, a read-only user granted as
 * docs/readonly_user.sql does, and one without the catalog role. Row values planted in B must reach the private
 * reports and never the share folder.
 */
@Testcontainers(disabledWithoutDocker = true)
class BundleIT {

    private static final String PW = "Sample_pw_1";
    private static final String SENTINEL = "sentinel-7q";

    @Container
    static final OracleContainer ORACLE = new OracleContainer("gvenzl/oracle-free:23-slim-faststart").withPassword("Sys_pw_1");

    @TempDir
    static Path config;

    static String url() {
        return "jdbc:oracle:thin:@//" + ORACLE.getHost() + ":" + ORACLE.getOraclePort() + "/" + ORACLE.getDatabaseName();
    }

    static void sys(String... sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url(), "system", ORACLE.getPassword()); Statement s = c.createStatement()) {
            for (String q : sql) s.execute(q);
        }
    }

    static void as(String user, String... sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url(), user, PW); Statement s = c.createStatement()) {
            for (String q : sql) s.execute(q);
        }
    }

    /** What docs/readonly_user.sql grants, done over JDBC (the script itself is SQL*Plus). */
    static void grantReadOnly(String user, String owner, boolean catalogRole) throws Exception {
        sys("GRANT CREATE SESSION TO " + user);
        if (catalogRole) sys("GRANT SELECT_CATALOG_ROLE TO " + user);
        sys("BEGIN FOR o IN (SELECT object_name, object_type FROM dba_objects WHERE owner = '" + owner + "'"
                + " AND object_type IN ('TABLE','VIEW','SEQUENCE') AND generated = 'N' AND object_name NOT LIKE 'BIN$%') LOOP"
                + " EXECUTE IMMEDIATE 'GRANT ' || CASE o.object_type WHEN 'SEQUENCE' THEN 'SELECT' ELSE 'READ' END"
                + " || ' ON " + owner + ".\"' || o.object_name || '\" TO " + user + "'; END LOOP; END;");
    }

    @BeforeAll
    static void setUp() throws Exception {
        for (String u : List.of("OWN_A", "OWN_B", "RO", "RO_THIN")) {
            sys("CREATE USER " + u + " IDENTIFIED BY \"" + PW + "\"");
        }
        for (String u : List.of("OWN_A", "OWN_B")) {
            sys("GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE, CREATE TRIGGER, CREATE PROCEDURE, CREATE VIEW, UNLIMITED TABLESPACE TO " + u);
            try (Connection c = DriverManager.getConnection(url(), u, PW)) {
                SqlScriptRunner.run(c, u.equals("OWN_A") ? "/sample/schema_a.sql" : "/sample/schema_b.sql");
            }
            as(u, "CREATE OR REPLACE PROCEDURE p_touch(p_id NUMBER) IS BEGIN NULL; END;");
        }
        // Values that must never leave the private folder: a conflicting name and a new business key.
        as("OWN_B", "UPDATE customer SET name = 'SENTINEL-7Q name' WHERE email = 'alice@example.com'",
                "INSERT INTO customer (customer_id, email, name, region_id, credit_limit) VALUES (9, '" + SENTINEL + "@example.com', 'New', 1, 10)");
        for (String o : List.of("OWN_A", "OWN_B")) {
            grantReadOnly("RO", o, true);
            grantReadOnly("RO_THIN", o, false);
        }
        String host = ORACLE.getHost();
        int port = ORACLE.getOraclePort();
        String svc = ORACLE.getDatabaseName();
        StringBuilder yml = new StringBuilder("dbmerge:\n  preload:\n");
        for (String[] c : new String[][] {{"OWN_A", "OWN_A", "OWN_A"}, {"OWN_B", "OWN_B", "OWN_B"}, {"RO_A", "RO", "OWN_A"},
                {"RO_B", "RO", "OWN_B"}, {"THIN_A", "RO_THIN", "OWN_A"}}) {
            yml.append("    - {nickname: ").append(c[0]).append(", host: ").append(host).append(", port: ").append(port)
                    .append(", service-name: ").append(svc).append(", username: ").append(c[1]).append(", schema: ").append(c[2]).append("}\n");
        }
        Files.writeString(config.resolve("bundle-it.yml"), yml.toString());
    }

    final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    int cli(Path out, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(List.of("--config", config.resolve("bundle-it.yml").toString(), "--store-dir", out.resolve("store").toString(),
                "--out", out.toString()));
        for (String n : List.of("OWN_A", "OWN_B", "RO_A", "RO_B", "THIN_A")) {
            if (all.contains(n)) all.addAll(List.of("--password-env", n + "=PW"));
        }
        Cli c = new Cli(new PrintStream(stdout, true, StandardCharsets.UTF_8), new PrintStream(stderr, true, StandardCharsets.UTF_8),
                Map.of("PW", PW)::get);
        return c.run(all.toArray(String[]::new));
    }

    String out() { return stdout.toString(StandardCharsets.UTF_8); }

    static String readAll(Path dir) throws Exception {
        StringBuilder b = new StringBuilder();
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                b.append(p.getFileName()).append('\n');
                if (!p.toString().endsWith(".xlsx")) b.append(Files.readString(p)).append('\n');
            }
        }
        return b.toString().toLowerCase(Locale.ROOT);
    }

    static Path onlyBundle(Path out) throws Exception {
        try (Stream<Path> s = Files.list(out)) {
            List<Path> b = s.filter(p -> p.getFileName().toString().startsWith("bundle-")).toList();
            assertEquals(1, b.size(), b.toString());
            return b.get(0);
        }
    }

    @Test
    void preflightFailsTheOwnerAndPassesTheReadOnlyUser(@TempDir Path out) {
        assertEquals(ExitCodes.FINDINGS, cli(out, "preflight", "OWN_A"), out());
        assertTrue(out().contains("Connected as the owner OWN_A"), out());

        stdout.reset();
        assertEquals(ExitCodes.OK, cli(out, "preflight", "--master", "RO_A", "RO_B"), out());
        assertTrue(out().contains("dictionary DBA_*"), out());
        assertTrue(out().contains("Disk space for plan files"), out());
        assertFalse(out().contains("FAIL"), out());
    }

    @Test
    void preflightFailsAUserThatCannotSeeTheWholeSchemaOrMayWrite(@TempDir Path out) throws Exception {
        assertEquals(ExitCodes.FINDINGS, cli(out, "preflight", "THIN_A"), out());
        assertTrue(out().contains("FAIL  Whole schema visible"), out());

        sys("GRANT UPDATE ON OWN_A.customer TO RO_THIN", "REVOKE READ ON OWN_A.region FROM RO_THIN");
        try {
            stdout.reset();
            cli(out, "preflight", "THIN_A");
            assertTrue(out().contains("FAIL  No write grants on the schema: This login may change OWN_A: UPDATE on 1 object(s)"), out());
            // a table the user may not read is invisible without the DBA views: the check says it cannot tell
            assertTrue(out().contains("WARN  Every table readable"), out());
        } finally {
            sys("REVOKE UPDATE ON OWN_A.customer FROM RO_THIN", "GRANT READ ON OWN_A.region TO RO_THIN");
        }
        sys("REVOKE READ ON OWN_A.region FROM RO");
        try {
            stdout.reset();
            cli(out, "preflight", "RO_A");
            assertTrue(out().contains("FAIL  Every table readable: 1 table(s) cannot be read") && out().contains("REGION"), out());
        } finally {
            sys("GRANT READ ON OWN_A.region TO RO");
        }
    }

    @Test
    void aReadOnlyUserWithTheCatalogRoleSeesWhatTheOwnerSees() throws Exception {
        ConnectionRegistry reg = new ConnectionRegistry();
        reg.add(new ConnectionRequest("OWN_A", ORACLE.getHost(), ORACLE.getOraclePort(), ORACLE.getDatabaseName(), "OWN_A", PW.toCharArray(), null));
        reg.add(new ConnectionRequest("RO_A", ORACLE.getHost(), ORACLE.getOraclePort(), ORACLE.getDatabaseName(), "RO", PW.toCharArray(), "OWN_A"));
        reg.add(new ConnectionRequest("THIN_A", ORACLE.getHost(), ORACLE.getOraclePort(), ORACLE.getDatabaseName(), "RO_THIN", PW.toCharArray(), "OWN_A"));
        SchemaExtractor ex = new SchemaExtractor(reg);
        SchemaModel owner = ex.extract("OWN_A", true);
        SchemaModel ro = ex.extract("RO_A", true);
        SchemaModel thin = ex.extract("THIN_A", false);
        assertTrue(owner.plsqlCount() > 0 && owner.tables().values().stream().anyMatch(t -> !t.triggers().isEmpty()), "the sample has PL/SQL and a trigger");

        ComparisonResult same = new SchemaComparator().compare(List.of(owner, ro), CompareFilter.NONE);
        assertEquals(List.of(), same.differences(), "through the DBA_* views the read-only user sees the whole schema");
        assertTrue(ro.warnings().isEmpty(), ro.warnings().toString());

        assertEquals(0, thin.plsqlCount(), "without the catalog role Oracle hides the PL/SQL");
        assertTrue(thin.warnings().stream().anyMatch(w -> w.contains("not the schema owner") && w.contains("SELECT_CATALOG_ROLE")), thin.warnings().toString());
        assertEquals("Connected. Comparing schema OWN_A (connected as RO).", reg.test("RO_A").message());
    }

    @Test
    void bundleWritesValuesOnlyToThePrivateFolder(@TempDir Path out) throws Exception {
        int code = cli(out, "bundle", "--master", "RO_A", "RO_B", "--key", "CUSTOMER=EMAIL", "--key", "REGION=NAME", "--key", "PRODUCT=SKU");
        assertEquals(ExitCodes.OK, code, out() + stderr.toString(StandardCharsets.UTF_8));
        Path b = onlyBundle(out);

        String md = Files.readString(b.resolve("share/summary.md"));
        for (String section : List.of("## Read-only check", "## Estimates", "## Schema differences", "## Undeclared references",
                "## Data comparison", "## Merge dry run")) {
            assertTrue(md.contains(section), section + " missing:\n" + md);
        }
        assertTrue(md.contains("| CUSTOMER | COMPARED"), md);
        assertTrue(Files.readString(b.resolve("share/summary.json")).contains("\"dataComparison\""));

        String share = readAll(b.resolve("share"));
        assertFalse(share.contains(SENTINEL), "a row value reached share/");
        assertFalse(share.contains("alice@example.com") || share.contains(ORACLE.getHost() + ":"), "a key or host reached share/");
        String priv = readAll(b.resolve("private"));
        assertTrue(priv.contains(SENTINEL), "the private reports keep the values");
        assertTrue(priv.contains("snapshot-ro_a.json") && priv.contains("merge-") && priv.contains("data-"), priv.substring(0, 300));
        assertFalse(Files.exists(b.resolve("private/plans")) && Files.list(b.resolve("private/plans")).findAny().isPresent(),
                "plan files are deleted after the dry run");
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(b.resolve("private"))));
        assertTrue(Files.readString(b.resolve("README.txt")).contains("WITH SAMPLE ROWS"));
    }

    @Test
    void bundleStopsBeforeReadingDataWhenALoginCanWrite(@TempDir Path out) throws Exception {
        assertEquals(ExitCodes.FINDINGS, cli(out, "bundle", "--master", "OWN_A", "RO_B"), out());
        assertTrue(out().contains("STOPPED before reading any data"), out());
        Path b = onlyBundle(out);
        try (Stream<Path> s = Files.list(b.resolve("private"))) {
            assertEquals(List.of("run.log"), s.map(p -> p.getFileName().toString()).toList(), "no report was written");
        }
        assertTrue(Files.readString(b.resolve("share/summary.md")).contains("## Stopped by the read-only check"));

        stdout.reset();
        assertEquals(ExitCodes.OK, cli(out.resolve("again"), "bundle", "--master", "OWN_A", "RO_B", "--allow-write-access", "--skip-plan"), out());
    }
}
