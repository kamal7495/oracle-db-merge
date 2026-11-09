package com.dbmerge.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** Runs the CLI against an empty saved-connection store: no database is needed. */
class CliTest {

    @TempDir Path store;
    @TempDir Path out;

    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();

    private int run(Map<String, String> env, String... args) {
        Cli cli = new Cli(new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8), env::get);
        return cli.run(args);
    }

    private String out() { return stdout.toString(StandardCharsets.UTF_8); }
    private String err() { return stderr.toString(StandardCharsets.UTF_8); }

    @Test
    void cliContextStartsNoWebServer() {
        CliArgs a = CliArgs.parse(new String[] {"connections", "--store-dir", store.toString()});
        try (ConfigurableApplicationContext ctx = Cli.startContext(a, out)) {
            assertFalse(ctx instanceof WebServerApplicationContext, ctx.getClass().getName());
        }
    }

    @Test
    void listsNoConnectionsFromAnEmptyStore() {
        assertEquals(ExitCodes.OK, run(Map.of(), "connections", "--store-dir", store.toString()));
        assertTrue(out().contains("No connections"), out());
    }

    @Test
    void jsonOutputIsTheOnlyThingOnStdout() {
        assertEquals(ExitCodes.OK, run(Map.of(), "connections", "--json", "--store-dir", store.toString()));
        String s = out().trim();
        assertTrue(s.startsWith("{") && s.endsWith("}"), s);
        assertTrue(s.contains("\"exitCode\" : 0"), s);
    }

    @Test
    void unknownConnectionsAreAUsageError() {
        assertEquals(ExitCodes.USAGE,
                run(Map.of(), "ddl", "DB_A", "DB_B", "--store-dir", store.toString(), "--out", out.toString()));
        assertTrue(err().contains("Unknown connection 'DB_A'"), err());
        assertEquals("", out());
    }

    @Test
    void passwordEnvForAnUnknownConnectionIsAUsageError() {
        assertEquals(ExitCodes.USAGE,
                run(Map.of("PW", "x"), "refs", "DB_A", "--password-env", "DB_A=PW", "--store-dir", store.toString()));
        assertTrue(err().contains("unknown connection"), err());
    }

    @Test
    void aConfiguredConnectionWithoutPasswordGetsItFromTheEnvironment() throws Exception {
        Path config = out.resolve("ci.yml");
        java.nio.file.Files.writeString(config, """
                dbmerge:
                  preload:
                    - nickname: UAT
                      host: db.example
                      service-name: UATPDB
                      username: APP
                """);
        assertEquals(ExitCodes.OK, run(Map.of("UAT_PW", "from-env"), "connections", "--json", "--config", config.toString(),
                "--password-env", "UAT=UAT_PW", "--store-dir", store.toString()));
        assertTrue(out().contains("\"nickname\" : \"UAT\""), out());
        assertTrue(out().contains("\"locked\" : false"), out());
        assertFalse(out().contains("from-env"));

        assertEquals(ExitCodes.USAGE, run(Map.of(), "connections", "--config", config.toString(),
                "--password-env", "UAT=UNSET_VAR", "--store-dir", store.toString()));
        assertTrue(err().contains("UNSET_VAR is not set"), err());
    }

    @Test
    void usageErrorsExitWith2AndHelpWith0() {
        assertEquals(ExitCodes.USAGE, run(Map.of(), "ddl", "A", "B", "--password", "nope"));
        assertFalse(err().contains("nope"));
        assertEquals(ExitCodes.USAGE,
                run(Map.of(), "plan", "--master", "M", "S", "--rules", out.resolve("missing.json").toString()));
        assertEquals(ExitCodes.OK, run(Map.of(), "help"));
        assertTrue(out().contains("Exit codes"));
        assertTrue(out().contains("no command that executes a merge"));
    }
}
