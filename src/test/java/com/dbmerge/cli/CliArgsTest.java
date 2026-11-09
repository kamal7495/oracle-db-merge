package com.dbmerge.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.cli.CliArgs.Command;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CliArgsTest {

    private static CliArgs parse(String... args) { return CliArgs.parse(args); }

    private static String usage(String... args) {
        return assertThrows(UsageException.class, () -> CliArgs.parse(args)).getMessage();
    }

    @Test
    void parsesDdlWithBothOptionStyles() {
        CliArgs a = parse("ddl", "DB_A", "--fail-on", "medium", "DB_B", "--out=ci-reports", "--json");
        assertEquals(Command.DDL, a.command());
        assertEquals(List.of("DB_A", "DB_B"), a.positional());
        assertEquals(ExitCodes.Threshold.MEDIUM, a.ddlThreshold());
        assertEquals("ci-reports", a.value("out"));
        assertTrue(a.has("json"));
        assertEquals(ExitCodes.Threshold.HIGH, parse("ddl", "A", "B").ddlThreshold());
    }

    @Test
    void parsesDataKeysAndTables() {
        CliArgs a = parse("data", "A", "B", "--key", "customer=email", "--key", "ORDERS=CUSTOMER_ID, ORDER_NO",
                "--tables", "customer,orders", "--tables", "items");
        assertEquals(Map.of("CUSTOMER", List.of("EMAIL"), "ORDERS", List.of("CUSTOMER_ID", "ORDER_NO")), a.keys());
        assertEquals(Set.of("CUSTOMER", "ORDERS", "ITEMS"), a.tables());
    }

    @Test
    void parsesPlan() {
        CliArgs a = parse("plan", "--master", "M", "S1", "S2", "--ack", "trade_keyword.trade_id", "--orphans", "skip",
                "--sequence", "trade=trade_seq", "--fail-on-warnings");
        assertEquals("M", a.value("master"));
        assertEquals(List.of("S1", "S2"), a.positional());
        assertEquals(Set.of("TRADE_KEYWORD.TRADE_ID"), a.acknowledged());
        assertEquals("SKIP", a.orphans());
        assertEquals(Map.of("TRADE", "TRADE_SEQ"), a.sequences());
    }

    @Test
    void helpNeedsNoOtherArguments() {
        assertTrue(parse("help").help());
        assertTrue(parse("--help").help());
        assertTrue(parse("plan", "--help").help(), "per-command help skips validation");
        assertTrue(parse("ddl", "-h").help());
    }

    @Test
    void rejectsBadUsage() {
        assertTrue(usage().contains("No command"));
        assertTrue(usage("merge").contains("Unknown command"));
        assertTrue(usage("execute").contains("Unknown command"), "there is no execute command");
        assertTrue(usage("ddl", "A").contains("at least two"));
        assertTrue(usage("ddl", "A", "A").contains("twice"));
        assertTrue(usage("ddl", "A", "B", "--bogus").contains("Unknown option --bogus"));
        assertTrue(usage("ddl", "A", "B", "--check-data").contains("Unknown option"), "options are per command");
        assertTrue(usage("ddl", "A", "B", "--fail-on").contains("needs a value"));
        assertTrue(usage("ddl", "A", "B", "--fail-on", "--json").contains("needs a value"));
        assertTrue(usage("ddl", "A", "B", "--fail-on", "SEVERE").contains("must be one of"));
        assertTrue(usage("ddl", "A", "B", "--out", "x", "--out", "y").contains("more than once"));
        assertTrue(usage("ddl", "A", "B", "--json=yes").contains("does not take a value"));
        assertTrue(usage("ddl", "A", "B", "-x").contains("Unknown option"));
        assertTrue(usage("ddl", "A", "1bad").contains("not a valid connection nickname"));
        assertTrue(usage("refs", "A", "B").contains("exactly one"));
        assertTrue(usage("refs", "A", "--fail-on", "CRITICAL").contains("must be one of"));
        assertTrue(usage("refs", "A", "--timeout", "5").contains("only apply with --check-data"));
        assertTrue(usage("refs", "A", "--check-data", "--timeout", "0").contains("between"));
        assertTrue(usage("refs", "A", "--check-data", "--max-checks", "ten").contains("whole number"));
        assertTrue(usage("plan", "S").contains("--master"));
        assertTrue(usage("plan", "--master", "M").contains("source"));
        assertTrue(usage("plan", "--master", "M", "M").contains("cannot also be a source"));
        assertTrue(usage("plan", "--master", "M", "S", "--orphans", "keep").contains("fail' or 'skip"));
        assertTrue(usage("plan", "--master", "M", "S", "--ack", "NOCOLUMN").contains("TABLE.COLUMN"));
        assertTrue(usage("data", "A", "B", "--key", "T").contains("TABLE=COLUMN"));
        assertTrue(usage("data", "A", "B", "--key", "T=A,A").contains("twice"));
        assertTrue(usage("data", "A", "B", "--key", "T=A;DROP").contains("not a valid column"));
        assertTrue(usage("data", "A", "B", "--tables", "X Y").contains("not a valid table"));
        assertTrue(usage("connections", "A").contains("no connection names"));
    }

    @Test
    void validatesPasswordEnv() {
        assertEquals(Map.of("DB_A", "DBMERGE_A_PASSWORD"),
                parse("refs", "DB_A", "--password-env", "DB_A=DBMERGE_A_PASSWORD").passwordEnv());
        assertTrue(usage("refs", "DB_A", "--password-env", "DB_A").contains("NICKNAME=VARIABLE"));
        assertTrue(usage("refs", "DB_A", "--password-env", "DB_A=$(id)").contains("not a valid environment variable"));
        assertTrue(usage("refs", "DB_A", "--password-env", "DB_A=X", "--password-env", "DB_A=Y").contains("twice"));
    }

    @Test
    void refusesPasswordsOnTheCommandLineWithoutEchoingThem() {
        String secret = "Hunter2-secret-value";
        for (String[] args : List.of(
                new String[] {"ddl", "A", "B", "--password", secret},
                new String[] {"ddl", "A", "B", "--password=" + secret},
                new String[] {"ddl", "A", "B", "-p", secret},
                new String[] {"ddl", "A", "B", "--pwd=" + secret},
                new String[] {"ddl", "A", "B", "--db-passwd", secret},
                new String[] {"ddl", "A", "B", "--dbmerge.preload[0].password=" + secret},
                new String[] {"ddl", "A", "B", "--PASSWORD", secret},
                new String[] {"ddl", "scott/" + secret + "@db:1521/FREEPDB1", "B"},
                new String[] {"--password", secret})) {
            UsageException e = assertThrows(UsageException.class, () -> CliArgs.parse(args), String.join(" ", args));
            assertFalse(e.getMessage().contains(secret), "the value is never echoed");
            assertTrue(e.getMessage().contains("--password-env"), e.getMessage());
        }
        parse("ddl", "A", "B", "--password-env", "A=VAR"); // --password-env itself is fine
    }

    @Test
    void filterOptionsAreParsedAndValidated() {
        CliArgs a = parse("ddl", "A", "B", "--exclude", "BKP_*,TMP_*", "--exclude", "OLD_%", "--ignore", "GRANT");
        assertEquals("exclude BKP_*, TMP_*, OLD_%; ignore GRANT_OPTION_MISMATCH, MISSING_GRANT", a.filter().describe());
        assertTrue(parse("data", "A", "B").filter().isEmpty());
        assertTrue(usage("ddl", "A", "B", "--exclude", "A;B").contains("Invalid name pattern"));
        assertTrue(usage("ddl", "A", "B", "--ignore", "EVERYTHING").contains("Unknown difference kind"));
        assertTrue(usage("refs", "A", "--ignore", "GRANT").contains("ignore"), "findings are not compared in refs");
        assertTrue(usage("connections", "--exclude", "X").contains("exclude"));
        assertEquals(0, parse("data", "A", "B", "--max-rows", "0").intOption("max-rows", 200_000, 0, Integer.MAX_VALUE));
        assertTrue(usage("data", "A", "B", "--max-rows", "-1").contains("max-rows"));
    }

    @Test
    void snapshotCommandAndDdlAgainstSnapshots() {
        assertEquals(Command.SNAPSHOT, parse("snapshot", "DB_A", "--file", "base.json", "--exclude", "BKP_*").command());
        assertTrue(usage("snapshot", "DB_A", "DB_B").contains("exactly one"));
        assertTrue(usage("snapshot", "DB_A", "--ignore", "GRANT").contains("ignore"));
        assertEquals(List.of("base.json"), parse("ddl", "DB_A", "--snapshot", "base.json").values("snapshot"));
        assertEquals(2, parse("ddl", "--snapshot", "a.json", "--snapshot", "b.json").values("snapshot").size());
        assertTrue(usage("ddl", "DB_A").contains("--snapshot"), "one live database alone is not enough");
        assertTrue(usage("data", "DB_A", "--snapshot", "x.json").contains("snapshot"), "data needs live rows");
    }
}
