package com.dbmerge.cli;

import com.dbmerge.cli.CliArgs.Command;

/** Usage text, overall and per command. */
final class CliHelp {

    private CliHelp() { }

    private static final String COMMON = """

            Common options:
              --out DIR                  where reports are written (default: reports)
              --json                     print a JSON summary on stdout instead of text
              --password-env NICK=VAR    password for connection NICK from environment variable VAR (repeatable)
              --store-dir DIR            saved-connections folder (default: ~/.dbmerge)
              --profile NAME             Spring profile, e.g. 'local' loads config/application-local.yml
              --config FILE              extra configuration file with preloaded connections
              --help                     this text

            Filters (ddl, data, refs, plan):
              --include PATTERNS         only objects whose name matches, e.g. 'TRADE*,BOOK' (repeatable)
              --exclude PATTERNS         leave out matching objects, e.g. 'BKP_*,TMP_*' (repeatable)
              --ignore KINDS             drop findings of these kinds or categories, e.g. CONSTRAINT_NAME_MISMATCH,GRANT
                                         (not for refs). Wildcards: * or % any run, ? one character; _ is literal.

            Connections are referred to by nickname: saved in the UI (~/.dbmerge) or preloaded from configuration.
            Passwords are never accepted as arguments; use a saved password or --password-env.

            Exit codes: 0 pass, 1 findings at/above the threshold, 2 usage error, 3 connection or runtime error.
            """;

    static String text(Command c) {
        return switch (c) {
            case HELP -> """
                    Usage: java -jar oracle-db-merge.jar cli <command> [options] [connections...]

                    Commands:
                      connections                 list saved and configured connections (--test to connect to each)
                      ddl A B [C...]              compare schemas; fails on differences at/above --fail-on (default HIGH)
                      data A B [C...]             compare table data; fails on any difference
                      refs A                      undeclared-reference report; fails on HIGH-confidence findings
                      plan --master M S [S...]    merge dry run (never writes); fails on blockers
                      snapshot A [--file F]       save A's structure to a JSON file, to compare against later
                      preflight A [B...]          read-only check: right database, no write rights, everything visible, sizes
                      bundle --master M S [S...]  read-only check, then every report into one folder, with a summary to share

                    Run 'cli <command> --help' for the options of a command. There is no command that executes a merge:
                    review the dry run and execute it from the web UI.
                    """ + COMMON;
            case CONNECTIONS -> """
                    Usage: cli connections [--test] [common options]

                      --test    open a read-only connection to each one; exit 3 if any fails or is locked
                    """ + COMMON;
            case DDL -> """
                    Usage: cli ddl A [B...] [--snapshot FILE]... [--fail-on SEVERITY] [--no-ddl-text] [common options]

                    Compares every schema with every other and writes ddl-<id>.html and .xlsx. At least two in total:
                    live connections and/or snapshots saved with 'cli snapshot'.
                      --snapshot FILE      a saved snapshot, named after its file (repeatable)
                      --fail-on SEVERITY   CRITICAL, HIGH (default), MEDIUM, LOW or NEVER (report only)
                      --no-ddl-text        skip DBMS_METADATA text (faster; the report shows no DDL)
                    """ + COMMON;
            case SNAPSHOT -> """
                    Usage: cli snapshot A [--file FILE] [--include P] [--exclude P] [common options]

                    Saves the structure of A (tables, columns, keys, indexes, triggers, sequences, views, PL/SQL,
                    synonyms, grants) to a JSON file, by default <out>/snapshot-A-<timestamp>.json. No row data and no
                    passwords are saved. Compare against it later with: cli ddl A --snapshot FILE
                    """ + COMMON;
            case DATA -> """
                    Usage: cli data A B [C...] [--tables T1,T2] [--key TABLE=COL1,COL2]... [--max-rows N] [--allow-uncompared] [common options]

                    Compares rows on a business key (default: primary key) and writes data-<id>.html and .xlsx.
                    Fails on rows only in some databases, conflicts, key collisions, different row counts, or a table
                    present in only one database.
                      --tables T1,T2       only these tables (repeatable)
                      --key TABLE=COLS     business key for a table (repeatable)
                      --max-rows N         tables above N rows are compared by server-side hashes (default 200000;
                                           0 hashes every table)
                      --allow-uncompared   don't fail on tables that could not be compared (no key, error)
                    """ + COMMON;
            case REFS -> """
                    Usage: cli refs A [--check-data [--timeout S] [--max-checks N]] [--fail-on CONFIDENCE] [common options]

                    Lists columns that act like foreign keys without a constraint; writes refs-<id>.html.
                      --check-data         count values without a parent row (SELECT only)
                      --timeout S          per-query timeout in seconds (default 60)
                      --max-checks N       cap on data-check queries (default 1000)
                      --fail-on LEVEL      HIGH (default), MEDIUM, LOW or NEVER
                    """ + COMMON;
            case PREFLIGHT -> """
                    Usage: cli preflight [--master M] A [B...] [--max-rows N] [common options]

                    Checks each connection before a first run against a real database, reading only dictionary and
                    session views (no table is scanned):
                      * which database, container and schema it reached, and as which user
                      * whether the login could change the schema: owner, write privileges, write grants (FAIL)
                      * whether the whole schema is visible (DBA_* views or owner) and every table readable (FAIL)
                      * Oracle version, hashing functions, DDL text, statistics, tables without a key
                      * table count and sizes from optimizer statistics, the largest tables
                    With --master, also estimates the bundle's time and plan-file disk space against the free space.
                    Exit 0 when nothing FAILs, 1 when something does, 3 when a connection cannot be opened.
                      --master M     the master of a later bundle (the others are its sources)
                      --max-rows N   streaming threshold used for the estimates (default 200000)
                    """ + COMMON;
            case BUNDLE -> """
                    Usage: cli bundle --master M S [S...] [plan options] [bundle options] [common options]

                    Runs the read-only check and stops if anything FAILs. Otherwise reads each schema once and writes,
                    into <out>/bundle-<timestamp>/:
                      share/summary.md, summary.json   counts, checks, schema names and differences; no row values
                      private/                         the full reports (ddl, refs, data, merge dry run), snapshots, run.log
                    Nothing is written to any database. A failed step is recorded and the others still run.
                    Bundle options:
                      --tables T1,T2           data comparison only for these tables (repeatable)
                      --max-rows N             tables above N rows are hashed / streamed (default 200000)
                      --no-check-data          don't count unresolved values for undeclared references
                      --skip-data              no data comparison
                      --skip-plan              no merge dry run (it needs disk space for large tables)
                      --allow-write-access     go on although a login could change its schema (the tool still only reads)
                      --allow-incomplete       go on although part of a schema is invisible or unreadable
                    Plan options as for 'cli plan': --key, --ack, --sequence, --orphans, --rules.
                    Exit 0 when every step ran, 1 when the read-only check stopped the bundle, 3 when a step failed.
                    """ + COMMON;
            case PLAN -> """
                    Usage: cli plan --master M S [S...] [options] [common options]

                    Merge DRY RUN only: reads the master and the sources read-only and writes merge-<id>.html and the
                    review script merge-<id>.sql. Nothing is written to any database. Fails on blockers, including
                    unresolved conflicts and undeclared references that would break.
                      --master M               the database the sources would be merged into
                      --key TABLE=COLS         business key for a table (repeatable)
                      --ack TABLE.COLUMN       column confirmed NOT to be a reference (repeatable)
                      --sequence TABLE=SEQ     sequence for re-keying colliding rows (repeatable)
                      --orphans fail|skip      rows whose parent is missing (default fail)
                      --rules FILE             conflict rules (default <out>/merge-rules.json)
                      --fail-on-warnings       also fail on warnings
                    """ + COMMON;
        };
    }
}
