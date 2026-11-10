# Oracle DB Merge

Compare and merge **N Oracle databases into one master**. Java 17, Maven, `ojdbc11`, Spring Boot 3.3 and a small web UI.

* **Phase 1** – extract DDL from every database (tables, sequences, triggers, views, materialized views, PL/SQL, synonyms, grants), compare all of them against each other, classify each difference by severity, report as HTML and Excel.
* **Phase 2** – per-table row counts, matches and conflicts on a business key you choose (default: primary key), report as HTML and Excel. Large tables are compared with server-side hashes, with no row limit.
* **Phase 3** – merge the others into the master you choose: dependency-ordered, collision-safe, dry-run by default, one transaction, validated before commit.
* **Undeclared references** – for one schema, list the columns that act like foreign keys but have no FOREIGN KEY constraint, optionally checked against the data. Run this before merging a schema whose integrity lives in the application (typical of vendor packages such as trading systems).

> **Status of this build – read this first.**
> `mvn verify` passes: the unit tests plus the Testcontainers integration tests against Oracle Free 23 (`SchemaExtractorIT`, `MergeIT`, `LobMergeIT`, `ReferenceIT`, `DataHashCompareIT`, `LargeMergeIT`, `BundleIT`, `WebFlowIT`). These cover extraction (also through a read-only user), the Phase 2 comparison (in memory and by server-side hash), planning, executing, rolling back and validating merges (including BLOB/CLOB columns), the undeclared-reference detection with its data check, every REST endpoint (`WebApiTest`, no database), the read-only check and the report bundle (`BundleIT` plants values in a source and checks they never reach `share/`), and the UI itself: `WebFlowIT` drives a complete merge through the page in headless Chromium.
> **Lightly covered:** the content of the Phase 2 Excel report and the merge reports (generated and downloaded in tests, not checked cell by cell).
> **Do not point the executor at a real master before a dry run on a copy and a Data Pump backup.**
> **First time on a real database?** Follow [docs/first-real-run.md](docs/first-real-run.md): a read-only user, `cli preflight`, then `cli bundle`.

## Requirements

* JDK 17+, Maven 3.9+
* Network access to your Oracle databases. Oracle 12.2+ (the extractor reads `SEARCH_CONDITION_VC`); tested design target is Oracle 19c/23ai.
* Docker, only for the integration tests (`gvenzl/oracle-free:23-slim-faststart`)
* The account used per database needs `SELECT` on the `ALL_*` dictionary views (granted by default) and, for DDL text, `EXECUTE` on `DBMS_METADATA` (granted to `PUBLIC` by default). To compare through a **read-only user** instead of the schema owner (recommended for anything real), set the connection's schema and grant what [docs/readonly_user.sql](docs/readonly_user.sql) grants: `READ` on the tables plus `SELECT_CATALOG_ROLE`. With that role the extractor reads the `DBA_*` views, so the read-only user sees exactly what the owner sees. Without it Oracle hides the schema's PL/SQL, triggers and grants from another user; the extractor then warns that differences in them can be hidden. For the **master** account also `CREATE TABLE`/`ALTER` privileges in its own schema if you approve schema changes, plus `ALTER TRIGGER` if you use "disable triggers".

## Run

```bash
mvn spring-boot:run          # or: mvn package && java -jar target/oracle-db-merge-*.jar
# open http://127.0.0.1:8080
```

The server binds to `127.0.0.1` only. Reports are written to `./reports` (`dbmerge.reports-dir`).

1. **Connections** – nickname, host (default `localhost`), port, service name, user, password. Press *Test*. Optional schema if it differs from the user.
2. **Phase 1** – tick two or more connections, *Extract & compare*. Open the HTML / Excel report.
3. **Phase 2** – pick the business key per table (checkboxes), *Compare data*.
4. **Phase 3** – pick the master, *Dry run*. Review the preview report and the generated `.sql`. Decide conflicts, approve schema changes, then confirm and execute.

### Sample configuration

`config/sample-config.yml` pre-registers connections at start-up. **Passwords come only from environment variables**:

```bash
cp config/sample-config.yml config/application-local.yml     # git-ignored; edit hosts/users
export DBMERGE_A_PASSWORD='...'  DBMERGE_B_PASSWORD='...'
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

An entry whose variable is empty is skipped (add it in the UI instead).

## Command line

The same jar runs comparisons without the web server, for CI jobs and scheduled checks:

```bash
java -jar target/oracle-db-merge-*.jar cli <command> [options] [connections...]
java -jar target/oracle-db-merge-*.jar cli help            # or: cli <command> --help
```

| Command | What it does | Fails (exit 1) when |
|---|---|---|
| `connections [--test]` | lists saved and configured connections; `--test` connects to each | `--test` and a connection fails or is locked (exit 3) |
| `ddl A B [C...]` | Phase 1 comparison, writes `ddl-<id>.html` and `.xlsx`. `--snapshot FILE` adds a saved snapshot (so `ddl A --snapshot base.json` works) | a difference at or above `--fail-on` (`CRITICAL`, `HIGH` default, `MEDIUM`, `LOW`, `NEVER`) |
| `data A B [C...]` | Phase 2 comparison, writes `data-<id>.html` and `.xlsx`. `--tables T1,T2`, `--key TABLE=COL1,COL2`, `--max-rows N` (above N rows, compare by hash; `0` hashes every table) | any difference: rows only in some databases, conflicts, key collisions, different row counts, a table in only one database. Tables that could not be compared (no key, error) also fail unless `--allow-uncompared` |
| `refs A` | undeclared-reference report, writes `refs-<id>.html`. `--check-data [--timeout S] [--max-checks N]` | a candidate at or above `--fail-on` confidence (`HIGH` default, `MEDIUM`, `LOW`, `NEVER`) |
| `snapshot A [--file F]` | saves A's structure to a JSON file (default `snapshot-A-<time>.json`), see [Snapshots](#snapshots) | never (exit 0) |
| `preflight [--master M] A [B...]` | read-only check, from the dictionary only (no table scanned): which database, schema and user it reached; whether the login could change the schema (owner, write privileges, write grants); whether the whole schema is visible and every table readable; Oracle version, hashing, DDL text, statistics; sizes and the largest tables. With `--master`, estimated bundle time and plan-file disk space against free space | anything FAILs (exit 3 if a connection cannot be opened) |
| `bundle --master M S [S...]` | `preflight`, then (only if nothing FAILs) every report a first look needs, into `bundle-<time>/`: `share/summary.md` and `summary.json` with counts, checks and schema names but **no row values**, and `private/` with the full reports, snapshots and `run.log`. Each schema is read once. Options as for `plan`, plus `--tables`, `--max-rows`, `--skip-data`, `--skip-plan`, `--no-check-data`, and the overrides `--allow-write-access`, `--allow-incomplete` | the read-only check stopped it (exit 3 if a step failed; the others still run) |
| `plan --master M S [S...]` | merge **dry run**, writes `merge-<id>.html` and the review `.sql`. `--key`, `--ack TABLE.COLUMN`, `--sequence TABLE=SEQ`, `--orphans fail\|skip`, `--rules FILE` | any blocker (including unresolved conflicts and undeclared references that would break); also warnings with `--fail-on-warnings` |

There is **no command that executes a merge**: review the dry run and execute it from the web UI.

Common options: `--out DIR` (reports, default `reports`), `--json` (JSON summary on stdout), `--store-dir DIR`, `--profile NAME`, `--config FILE`, `--password-env NICK=VAR`.

Filters, for `ddl`, `data`, `refs`, `plan`, `bundle` and `snapshot`: `--exclude 'BKP_*,TMP_*'`, `--include 'TRADE*'` and, except for `refs` and `snapshot`, `--ignore CONSTRAINT_NAME_MISMATCH,GRANT`. See [Filters](#filters-and-ignored-differences).

**Exit codes:** `0` pass · `1` findings at or above the threshold · `2` usage error (bad arguments, unknown or locked connection, missing environment variable) · `3` connection or runtime error.

**Connections and passwords.** Connections are named by nickname: saved in the UI (`~/.dbmerge`, see [Saved connections](#saved-connections)) or preloaded with `--profile local` / `--config FILE`. Passwords are **never** accepted as arguments, since arguments end up in shell history and `ps` output: `--password`, `-p` and `user/password@host` strings are refused without being echoed. For a connection without a saved password, put the password in an environment variable and name it: `--password-env DB_A=DBMERGE_A_PASSWORD`. stdout carries only the summary (or the JSON); Spring's log lines go to stderr.

Example: a nightly drift check in GitHub Actions, with the connections in a config file and the passwords in repository secrets.

```yaml
- name: Schema drift UAT vs PROD
  env:
    UAT_PW:  ${{ secrets.UAT_DB_PASSWORD }}
    PROD_PW: ${{ secrets.PROD_DB_PASSWORD }}
  run: |
    java -jar oracle-db-merge.jar cli ddl UAT PROD --config ci/connections.yml \
      --password-env UAT=UAT_PW --password-env PROD=PROD_PW \
      --fail-on HIGH --out drift --json > drift/summary.json
- uses: actions/upload-artifact@v4
  if: always()
  with: { name: drift-report, path: drift/ }
```

`ci/connections.yml` uses the `dbmerge.preload` format of `config/sample-config.yml`, with `password: ${UAT_PW:}`-style placeholders, or none at all when `--password-env` is used. In a shell script, branch on the code:

```bash
java -jar oracle-db-merge.jar cli refs CALYPSO_UAT --check-data --fail-on MEDIUM
case $? in 0) echo clean ;; 1) echo "review refs report" ;; 2) echo "fix the command" ;; *) echo "database problem" ;; esac
```

## Filters and ignored differences

Real vendor schemas carry hundreds of backup and scratch tables (`BKP_TRADE_2023`, `TMP_RECON_STAGE`) that drown the
findings that matter. In the UI (Phase 1) or on the command line:

* **Leave out objects** (`--exclude`) and **only these objects** (`--include`): comma-separated name patterns,
  case-insensitive. `*` or `%` match any characters and `?` one; `_` is literal, since nearly every Oracle name has one.
  An object is kept when it matches an include pattern (or none is given) and no exclude pattern. Patterns apply to
  tables (with their columns, constraints, indexes and triggers), sequences, views, materialized views, PL/SQL and
  synonyms by name, and to grants by the object granted on.
* **Ignore differences of kind** (`--ignore`): a kind such as `CONSTRAINT_NAME_MISMATCH`, `INDEX_NAME_MISMATCH`,
  `DEFAULT_MISMATCH`, `SEQUENCE_PARAM_MISMATCH`, or a whole category: `TABLE`, `COLUMN`, `CONSTRAINT`, `INDEX`,
  `SEQUENCE`, `TRIGGER`, `VIEW`, `MVIEW`, `PLSQL`, `CODE`, `SYNONYM`, `GRANT`. These findings are dropped from the
  result; nothing else changes.
* The filter is applied when the DDL comparison runs, and the data comparison and merge plan reuse that comparison, so
  **a left-out table is left out of them too**. If a kept table has a declared foreign key to a left-out table, the
  comparison warns: a merge would copy those foreign-key values unchanged, so they must already exist in the master.
* Every report and summary states the filter (`Filtered: exclude BKP_*, TMP_*; ignore MISSING_GRANT, …`), so a
  filtered report is never mistaken for a full one.
* Storage clauses, tablespaces and physical attributes are not compared at all, so there is nothing to ignore there.

## Snapshots

A snapshot is a schema's structure saved to a JSON file: tables, columns, keys, indexes, triggers, sequences, views,
PL/SQL source, synonyms and grants. **No row data, no passwords** and no host details; DBMS_METADATA text is left out
to keep it small. Compare live databases against it later, for example "has production drifted from the release-1.4
baseline?", without a second database:

```bash
java -jar oracle-db-merge.jar cli snapshot PROD --exclude 'BKP_*,TMP_*' --file baselines/release-1.4.json
java -jar oracle-db-merge.jar cli ddl PROD --snapshot baselines/release-1.4.json --exclude 'BKP_*,TMP_*' --fail-on MEDIUM
```

* A snapshot takes its name from the file (`release-1.4.json` → `release-1_4`), so a baseline of `PROD` can be compared
  with the live `PROD`. Its first warning says which database and schema it came from, when, and with which filter.
* In the UI: each Phase 1 result has *Save a snapshot* links (one per database, filtered as that run was), and
  *Compare with snapshot file(s)* takes uploaded snapshots alongside the ticked connections.
* Snapshots can be committed to Git next to the release scripts. The JSON is pretty-printed, with objects in the order
  they were extracted, so diffs between snapshots are readable but not guaranteed minimal.
* Data comparison and merging need live rows: a comparison that includes a snapshot is refused for those phases.

## Passwords

Held in memory as `char[]`, wiped when a connection is removed, masked in every `toString` and never logged. They never appear in reports, scripts, the rules file or logs. Request logging is off and error messages are not echoed.

### Saved connections

By default a connection lasts until the app stops. Tick **Remember after restart** to save its details, and **Also save the password (encrypted)** to save the password as well.

* Saved to `~/.dbmerge/connections.json` (change with `dbmerge.store-dir`; blank turns saving off). The directory is `700` and the files `600`. The file is written atomically.
* Passwords are encrypted with **AES-256-GCM**. The key is `DBMERGE_MASTER_KEY` (base64 of 32 random bytes, e.g. `openssl rand -base64 32`) when that variable is set, otherwise `~/.dbmerge/master.key`, generated on first use. A key file readable by group or others is refused, as ssh does.
* Each ciphertext is bound to its nickname, user, host, port and service. Editing the host in the file to point a saved password at another server makes decryption fail; the password is not sent there.
* A remembered connection without a saved password, or one whose password cannot be decrypted (different key, edited entry), comes back **locked**. Enter the password in the connection table to unlock it, optionally saving it. Removing a connection deletes its saved copy.
* **Threat model.** This keeps passwords out of plain text, backups and accidental copies of the JSON file. With the key file beside it, anyone who can read your home directory can decrypt both. Keep the key in `DBMERGE_MASTER_KEY` (from a password manager or keychain), or don't save passwords, when that matters. For production databases prefer not saving passwords at all.

## Severity model (Phase 1)

| Severity | Examples |
|---|---|
| CRITICAL | incompatible data-type families (e.g. NUMBER vs VARCHAR2), missing or different primary key |
| HIGH | table missing, length/precision mismatch, missing foreign key / unique constraint, NOT NULL column without default missing; missing view, materialized view, package, package body, procedure, function, type or type body |
| MEDIUM | nullability, check constraints, triggers, sequences, same-family type change; changed view / materialized view / PL/SQL source; VALID vs INVALID; missing synonym or grant, synonym pointing elsewhere |
| LOW | names, defaults, enabled flags, grant option (`WITH GRANT OPTION`) |

Constraints and indexes are matched by structure (columns, type, referenced table), not by system-generated names. `NOT NULL` check constraints, identity defaults and sequence `LAST_NUMBER` are ignored.

### Views, PL/SQL, synonyms and grants

Besides tables and sequences, Phase 1 reads from the dictionary (read-only, like everything else):

| Object | Source | Compared on |
|---|---|---|
| Views | `ALL_VIEWS.TEXT` | query text, status |
| Materialized views | `ALL_MVIEWS` | query text, refresh method and mode, status |
| Packages and bodies, procedures, functions, types and type bodies | `ALL_SOURCE` | source, status (`ALL_OBJECTS.STATUS`) |
| Private synonyms owned by the schema | `ALL_SYNONYMS` | target object and DB link |
| Object grants on the schema's objects | `ALL_TAB_PRIVS` (`TABLE_SCHEMA` = schema) | privilege, grantee, grant option |

Objects are matched by type and name. Triggers are still compared with their table (body hash), as before. System-generated objects and the recycle bin are skipped.

**Source is normalised before comparing**, so only real changes count: comments, whitespace (including line breaks, line endings and trailing blank lines), the case of keywords and unquoted identifiers, unnecessary quotes (`"TRADE"` = `TRADE`), the schema's own name as a prefix (`SAMPLE_A.TRADE` in one database = `SAMPLE_B.TRADE` in the other) and a leading `CREATE OR REPLACE [EDITIONABLE]` are ignored. String literals are compared exactly. When source differs, the finding names the **first differing line** in each database with a short excerpt (80 characters); whole bodies are never put in the reports or the API response. Wrapped (obfuscated) PL/SQL is compared as wrapped text.

Synonyms that point into the schema itself are shown as `(own schema)`, so schemas with different names compare equal. A missing grant on an object that is itself missing in that database is not reported again (the missing object already is).

Not compared yet: public synonyms, system and role privileges, database links, scheduler jobs, comments, partitioning, Java sources.

## Phase 2 notes

* Business key defaults to the primary key. If the key is not the PK, same-PK-different-record cases are reported as **PK collisions**; same-key-different-PK as **PK divergence**.
* Tables up to `dbmerge.max-rows-per-table` (default 200 000) are compared in memory. **Larger tables are compared with hashes computed in the databases**, so there is no row limit and memory follows the number of differing rows, not the table size. The report marks these tables *compared by hash*, with a note saying how and which columns were left out.
* Report sections: only in one DB, identical in several, different in several (column-level diffs), PK collisions.

### How large tables are compared

1. Each database hashes every row with `STANDARD_HASH` (Oracle 12c or later): one hash of the business key, one of the primary key, one of the compared columns. Short values (numbers, dates, codes up to 200 bytes) are written inline as `V<length>:<text>` and hashed together, so a row costs about three MD5 calls whatever its width; long text, LOBs and national-character columns are hashed on their own first. Rows are spread over buckets (about 10 000 rows each) by key hash, so a key lands in the same bucket everywhere.
2. Each database returns one line per bucket: rows, distinct keys, and the sum of a row hash. Buckets that match in every database are counted as identical and never read.
3. The other buckets are streamed from all databases together, ordered by bucket, as hashes and key values only, one bucket at a time. That classifies only-in-one, identical, conflicts and PK divergences with exact counts.
4. When the business key is not the primary key, a second pass buckets by primary-key hash to find **PK collisions**.
5. Only the sample rows shown in the report (`dbmerge.sample-limit` per category) are fetched in full, looked up by their key values (through the key's index) rather than by hashing the table again. That is how conflicts get their column-level differences.

Every step runs in all databases **at the same time**, each on its own read-only connection, so a table is still compared within one snapshot per database. Each database scans the table twice (bucket totals, then the rows of differing buckets). Each query has a 30-minute timeout (`DataCompareService.DEFAULT_HASH_QUERY_TIMEOUT_SECONDS`). Identical rows are counted but not listed.

Values are hashed in a canonical form that does not depend on session NLS settings:

| Type | Hashed as |
|---|---|
| NUMBER, FLOAT, BINARY_FLOAT/DOUBLE | `TO_CHAR(x, 'TM9')` with `.` as decimal sign. `1.50` in NUMBER(10,2) equals `1.5` in NUMBER. Binary floats compare by their shortest decimal text. |
| DATE, TIMESTAMP | explicit `SYYYY-MM-DD HH24:MI:SS[.FF9]` |
| TIMESTAMP WITH (LOCAL) TIME ZONE | converted to UTC first: the same instant written in two time zones is **equal** (the in-memory path, which compares JDBC text, calls it a conflict) |
| VARCHAR2, CHAR, NVARCHAR2, NCHAR | the stored bytes, so the comparison is exact and case-sensitive. When the databases use different character sets, the text is converted to AL32UTF8 first (`UTL_I18N`) and the note says so. CHAR keeps its blank padding, so CHAR(10) vs CHAR(12) differ. |
| RAW, ROWID, INTERVAL | bytes / text |
| CLOB, NCLOB, BLOB | `DBMS_CRYPTO.HASH`, **only when every database lets the user execute `DBMS_CRYPTO`** (it is not granted by default). Otherwise these columns are left out and the note lists them. |
| LONG, LONG RAW, XMLTYPE, BFILE, UROWID, object types | never hashed; listed as not compared |

NULL and empty LOBs get their own markers, so they never equal a value. Key columns must be of a hashable, non-LOB type. A column whose type differs between databases (e.g. NUMBER vs VARCHAR2) gets a warning, since equal values can hash differently. Row hashes are compared through 60-bit sums per bucket. A false "identical" would need two different sets of rows with the same row count, the same distinct-key count and the same 60-bit sum: possible in principle, not by accident.

## Undeclared references

The merge rewrites a child's reference to a re-keyed parent **only through declared foreign keys**. Many application schemas enforce their relationships in code instead, and in those a re-keyed parent leaves its children pointing at the old key: the wrong row, or nothing. This report finds those columns. It is read-only and works on one connection at a time (UI section *Undeclared references*, or `POST /api/refs/detect` with `{"nickname": "...", "checkData": true}`); the result is written to `reports/refs-<id>.html`. The same rules also run inside every Phase 3 plan, where they block a merge that would actually break one of these references (see *Safety model*).

Columns covered by a declared foreign key are skipped, as are types that cannot hold keys (dates, LOBs, decimals). The first rule that matches a column wins:

| Finding | Example | Confidence |
|---|---|---|
| Same name as another table's single-column primary key | `TRADE_KEYWORD.TRADE_ID` → `TRADE` | HIGH (MEDIUM when several tables have that key) |
| Table name + ID suffix (`_ID`, `_NB`, `_NO`, `_NUM`, `_KEY`, `_REF`, `_CODE`, `ID`) | `CUSTOMER_ID` → `CUSTOMER`; plural tables: `ORDER_ID` → `ORDERS` | HIGH; MEDIUM for plurals |
| Role prefix in front of a key or table name, including self-references | `PARENT_TRADE_ID` → `TRADE` | MEDIUM |
| Shared primary key (1:1 extension table) | `PRODUCT_BOND.PRODUCT_ID` ↔ `PRODUCT_DESC` | MEDIUM; the direction is not guessed |
| Type + ID pair: points at a different table per row and can never be a foreign key | `OBJECT_ID` + `OBJECT_CLASS` | separate section |
| Named like an ID, but no table matches | `CPTY_ID` | LOW |

Generic key names (`ID`, `CODE`, `NAME`, `M_IDENTITY`, …) are never matched by name alone, or every table would link to every other. A child whose type family differs from the parent key (text vs number) drops one confidence level and says why.

**Data check** (optional): for each likely parent, one `LEFT JOIN` query counts the child's non-null values and how many have no parent row, with up to three example values. Each query has a timeout (default 60 s) and runs in a fresh `READ ONLY` transaction, and at most 1 000 queries run (`timeoutSeconds` / `maxChecks` in the request). For ambiguous and shared-key findings every parent is checked, which shows which one really holds the values. On a large production schema run it on a copy or off-hours.

Limits: detection works from names, so it misses references whose names do not match their table, IDs held inside BLOBs or XML, and references to other schemas or systems. Vendor naming such as Murex's `M_` columns mostly lands in the *no matching table* section. The data check counts values; a 100 % match makes a reference likely, it does not prove it.

## Phase 3 – how the merge works

1. **Plan (dry run, always first).** Reads master and sources read-only. Produces `merge-<id>.html` (preview), `merge-<id>.sql` (full review script) and the plan shown in the UI. Nothing is changed.
2. **Order.** Tables are loaded parents-before-children using a topological sort of the FK graph. A cycle is a **blocker** (flagged, not merged). Self-references are handled by ordering rows.
3. **Rows.**
   * New business key, free primary key → inserted with its key.
   * New business key, PK already used by a *different* record → **re-keyed**: next value of the master's sequence (found via trigger/default `NEXTVAL`, or set in the request) or `MAX+1` for numeric keys. Composite/non-numeric colliding keys are a blocker.
   * Same business key, identical data → skipped (duplicate).
   * Same business key, different data → **conflict**: needs a decision.
4. **Child keys are rewritten.** Every source key → master key is recorded; FK values of child rows are rewritten through that mapping. The mapping is stored in the audit table **`DBMERGE_ROW_MAP`** (`RUN_ID, SOURCE_DB, TABLE_NAME, OLD_PK, NEW_PK, BUSINESS_KEY, ACTION, MERGED_AT`). Rows whose parent cannot be found are blockers, or skipped if the orphan policy is *skip*.
5. **Conflicts.** Per table or per row: *keep master*, *take source*, *newest by date column*, or *manual values*. Decisions are remembered in `reports/merge-rules.json` (no secrets, but contains business keys/values – protect it like the data).
6. **Schema differences → ALTER scripts**, shown for approval: audit table, missing tables (FKs/indexes after), missing columns, widened columns. Nothing is dropped or narrowed. New columns that the source marks NOT NULL without default are added as nullable.
7. **Audit.** Instead of adding `SOURCE_DB/SOURCE_ID` columns to every business table, a separate audit table is used (above).

### Large tables

Tables up to `dbmerge.max-rows-per-table` (default 200 000 rows, in the master and every source) are planned in
memory. **Larger tables are streamed**, so a merge is not limited by memory:

* The master and every source are read **sorted by business key**, each in one read-only transaction, and walked side by
  side. Each key's rows (the master row, then each source's rows in source order) go through **the same decision code**
  as the in-memory path: duplicates, conflicts and rules, re-keying, foreign-key rewriting, orphans and constraint checks.
  The sort is the same in Oracle and Java (`NLSSORT ... BINARY`, NULLs last) and is checked while reading: rows out of
  order stop the plan rather than pair the wrong ones.
* Memory holds one key group at a time, the primary keys in use (as primitive longs for numeric keys) and only the
  source keys that change. Keys are kept only for tables that another table points at with a foreign key, and only
  when a collision is possible.
* The planned rows go to a **plan file** per table under `reports/plans/`, readable by the owner only, deleted once the
  plan is executed or replaced (and at exit). The review script shows the first 1 000 statements of such a table.
  The plan stays a read-only dry run: nothing is created in the master to plan.
* The executor reads the plan file and **inserts in batches of 1 000** (also for small tables), fetching sequence values
  in blocks. A table with an enabled trigger that stays active keeps row-by-row inserts, because a trigger may set the
  key and only a single-row insert returns the key actually stored. Everything stays one transaction with the same
  checks before commit.
* `StreamingPlannerTest` plans every scenario both ways, including 300 random databases, and requires identical plans;
  `LargeMergeIT` merges the sample schemas both ways on Oracle and requires identical masters.

**Self-referencing tables** (a trade amended from another trade, a book under a parent book) are streamed in three
passes, holding only keys in memory: the first works out where every row goes and collects the hierarchy; then orphans
are settled down the chains and each row gets a depth (parents first); the second pass redoes the key mapping with the
orphans known; the third plans the rows and writes them to one plan file per depth, which the executor reads in order,
so a parent is always written before its children. The rules for a row's parent in the same table, on both paths:

| The parent row is | The row |
|---|---|
| planned (not an orphan) | points at the parent's new key |
| in the source but an orphan itself | is an orphan too (the whole chain below a broken row is) |
| not in the source but in the master | keeps the reference (a valid reference to the master's row) |
| the row itself (a root marked as its own parent) | points at its own new key, also when it is re-keyed |
| nowhere | is an orphan |

A cycle through the self reference is a blocker. With several sources, when the same new row comes from two of them and
the conflict rule takes the later one's values, and those values point at a row of this table from the later source, the
plan refuses that row (blocker): the combined row would have to be written before its parent.

Other limits of the streamed path: the business-key columns must have **the same type** in every database, and when two
sources bring different new rows with the same primary key, which one keeps it follows key order. The in-memory path now
uses key order too, so re-keyed `MAX+1` numbers are the same both ways. Plan files take about 300 bytes per row (1.5 GB
for 5 million rows).

Measured on the perf kit (MacBook, Colima VM with 4 CPUs and 6 GB, Oracle Free 23ai, app heap capped at 768 MB):
merging `PERF_C` (1 million trades that the master does not have, 300 000 of them amendments of another trade in chains
up to 4 deep, with 3 million keywords, 1 million messages and 100 000 fees; static data identical) into `PERF_A`:

| Step | Time | Result |
|---|---|---|
| Dry run (5.35 M source rows, 5.35 M master rows; `TRADE` in three passes) | 95 s | 5.1 M inserts, 251 000 duplicates, no blockers |
| Execute (5.1 M rows + 5.35 M `DBMERGE_ROW_MAP` rows, one transaction) | 75 s | committed; every row count, orphan (including `FK_TRADE_PARENT`) and sample check passed |
| Peak memory of the whole process | 984 MB | within the 768 MB heap |

Afterwards all 299 799 merged amendment links point at their parent and the deepest chain is 4. A dry run of the same
merge again finds every row a duplicate: a repeated merge changes nothing.

### Safety model

* Dry run is the default and the only thing `/api/merge/plan` does.
* Execution needs: a plan with **no blockers**, "I took a Data Pump backup" ticked, the exact phrase `MERGE INTO <master>` typed, and approval of every *required* schema change.
* **Only the master is ever opened for writing**; sources use `SET TRANSACTION READ ONLY` connections. The master cannot also be a source.
* **Undeclared references block the plan.** After planning, every likely reference from the [undeclared-reference](#undeclared-references) rules is checked against the planned rows. If a row being copied holds the *old* key of a parent row whose key changes in this merge (re-keyed, or matched to a master row with another key), the plan gets a BLOCKER naming the column and example rows, since the copied value would point at the wrong row. The check uses the actual planned values, so a schema full of undeclared references only blocks when a referenced key really changes. To continue: declare the foreign key on the master (the merge then rewrites it), leave the child table out, or, for a false alarm, list `TABLE.COLUMN` under *Columns confirmed NOT to be references* (`acknowledgedReferences` in the plan request), which turns that blocker into a warning. Type + ID pairs cannot be checked and produce a warning whenever keys change.
* Before writing, master row counts must equal those seen at plan time, otherwise the run is refused and you plan again.
* **All row changes run in one transaction**, then validated *before* commit: row counts (master = old master + inserted rows; source rows − duplicates/skips are shown), no new orphan FK rows, sample comparison of inserted rows against the source values. Any error or failed check → `ROLLBACK`.
* Triggers: optionally disabled on the master for the load and **always re-enabled** in a `finally` block (a failure to re-enable is logged loudly). Constraints are never disabled – loading in FK order makes that unnecessary.
* Every action is written to the action log (`merge-<id>-final.txt`) and the final report (`merge-<id>-final.html`).
* **Back up first.** Example: `expdp <user>@<service> schemas=<MASTER_SCHEMA> directory=DATA_PUMP_DIR dumpfile=master_before_merge_%U.dmp logfile=master_before_merge.log`

### Limitations (please read)

* **Oracle commits DDL immediately.** Approved ALTER/CREATE statements cannot be rolled back with the data transaction. If the data step later rolls back, the schema changes stay (the log says so).
* No sequences or triggers are created or advanced. After loading rows with their own keys, advance the master's sequences beyond `MAX(pk)` (the plan warns).
* Only NOT NULL, text length and type conversion are checked while planning. **UNIQUE constraints other than the business key, CHECK constraints** and other DB rules are not pre-validated: the database enforces them during the run and a violation rolls the whole data transaction back (the `MergeIT` rollback test uses this). Example: the business key is the PK and a source row brings a new ID with an e-mail the master already has.
* Planning straight after a schema change (e.g. a run whose approved `CREATE TABLE` committed but whose data step rolled back) can hit `ORA-01466` because reads use `READ ONLY` transactions. Reads retry automatically for about 10 seconds.
* Business-key columns should be natural columns (e-mail, SKU), **not foreign-key columns**: keys are compared on source values before FK rewriting.
* Large tables are merged in one transaction: Oracle keeps undo for every inserted row until the commit, so the undo tablespace must hold the whole merge (and long reads risk `ORA-01555`). See [Large tables](#large-tables) for the streamed path and its limits.
* Hash comparisons of large tables run about six full scans per database. Under heavy write load a long read-only snapshot can hit `ORA-01555` (snapshot too old). Run them on a standby or a quiet period, or raise `UNDO_RETENTION`.
* FK cycles between tables are blocked rather than solved.
* **LOBs.** BLOB, CLOB and NCLOB are read whole, compared byte for byte / character for character, and written through bind variables. `LobMergeIT` covers 1–2 MB BLOBs, a 100 000-character Unicode CLOB, NULLs, empty LOBs (`EMPTY_BLOB()`/`EMPTY_CLOB()` stay empty, not NULL) and FK rewriting on tables with LOBs. Limits:
  * Each LOB is held in memory, plus a hex copy of each BLOB (about twice its size) for comparison. `max-rows-per-table` caps rows, not bytes, so tables of large documents or images can exhaust the heap. They need a streaming/hash-based approach.
  * Phase 2 reports show BLOBs as hex text.
  * BFILE, `LONG`, XMLType and object types are not handled.
* The generated `.sql` is a review copy: `COMMIT` is commented out and it ends in `ROLLBACK`. Values are written as literals, so CLOBs over 4 000 characters and BLOBs over 2 000 bytes produce `ORA-01704` there unless the database uses `MAX_STRING_SIZE=EXTENDED`. The app itself uses bind variables and is not affected.

## Tests

```bash
mvn test      # unit tests (no database): comparator, registry, row matcher, FK graph, planner, script/alter generators, report writers, reference detector, REST API
mvn verify    # also the Testcontainers integration tests (needs Docker): SchemaExtractorIT, MergeIT, LobMergeIT, ReferenceIT, DataHashCompareIT, LargeMergeIT, WebFlowIT
```

* `WebApiTest` (unit, no database) calls every REST endpoint as the UI does, with schema extraction replaced by fixed
  models: connections (never echoing the password, saved ones encrypted), Phase 1 with filters and snapshots, report
  downloads (and path tricks refused), the refusals for data/merge on snapshot comparisons, conflict rules, the
  reference report, error replies (one-line database errors, 404/405/400 instead of stack traces) and that every
  control the page's script looks up exists in the page. Saved connections, reports and rules go to a temporary folder.
* `WebFlowIT` starts the application on a random port against Oracle with the sample schemas. One test walks the
  REST flow, including every way execution must refuse (no backup confirmed, phrase not exact, blockers, snapshot as
  source). The other drives the page in headless Chromium with [Playwright](https://playwright.dev/java/): it adds the
  connections in the form, compares with a filter, runs the reference report and the data comparison with business
  keys ticked in the key table, decides conflicts with *Remember for table*, approves the schema changes, ticks the
  backup box, types the confirmation phrase and executes, then checks the master in the database and that the browser
  logged no errors. Playwright downloads Chromium on first use (about 150 MB, into its own cache).

The integration tests are skipped, not failed, when Testcontainers finds no Docker, so check the output says `Skipped: 0`. With Colima instead of Docker Desktop:

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
mvn verify
```

`LobMergeIT` builds its own `DOC`/`DOC_NOTE` tables (CLOB + BLOB) in code instead of using the sample scripts. `ReferenceIT` builds a small trading-style schema with **no** declared foreign keys and deliberately imperfect data (an orphan, a 1:1 product table, an ambiguous reference, a text column pointing at a number key), plus a second install whose re-keyed legal entity must make the merge refuse to run.

Sample schemas live in `src/test/resources/sample/schema_a.sql` (master) and `schema_b.sql` (source): FK chain `REGION ← CUSTOMER ← ORDERS ← ORDER_ITEM → PRODUCT`, PK collisions (customer 1 is Alice in A, Carol in B), identical duplicates (Bob, Widget), same person with different PK (Alice 1 vs 3), a table only in B (`AUDIT_NOTE`), a wider `EMAIL`, a different `QTY` type, an extra column, a missing index/trigger, and an order status that violates A's check constraint (used to prove rollback).

## Performance test kit (`perf/`)

A synthetic trading-style schema at realistic volume, to test the comparisons where the sample schemas are too small.
It is Calypso-like in shape only: names and data are made up.

* `trading_schema.sql`: 11 tables with only three declared foreign keys (one of them `TRADE.PARENT_TRADE_ID`, a self
  reference: chains of up to 3 amendments on 30 % of trades). The rest of the integrity is undeclared:
  references by name (`TRADE.BOOK_ID`, `BOOK.LE_ID`), a role prefix (`ISSUER_LE_ID`), a 1:1 extension table sharing
  its key (`PRODUCT_BOND`), a type + ID pair (`BO_MESSAGE.OBJECT_ID`/`OBJECT_CLASS`), an ID with no table (`CPTY_ID`).
  It also has views, a package, a function, a synonym, grants, and `BKP_`/`TMP_` leftovers.
* `trading_data.sql N OFFSET`: N trades (ids from OFFSET + 1), 3 keywords and 1 message each, 1 fee per 10, plus 200 000 products and 5 000 legal
  entities, generated inside the database. For N = 1 000 000 that is 5.35 million rows per schema.
* `drift_b.sql N`: makes `PERF_B` differ in countable ways: prices changed on 1 trade in 1 000, 1 in 5 000 deleted
  with its children, 100 new trades, 1 in 2 000 desk keywords changed, a new column, a changed package body, a
  dropped view, a missing grant and an extra backup table.
* `setup.sh [N] [container]` creates `PERF_A`/`PERF_B` (near copies, for comparisons) and `PERF_C` (the same static data
  but N other trades, for merging at scale) and loads them (about 80 s for 1 million trades on the Colima
  container). It needs `ORACLE_PASSWORD` (SYSTEM) and `DBMERGE_PERF_PASSWORD`. Passwords go to the container by
  variable name and reach sqlplus on stdin, never as process arguments.
* `run.sh [out]` times each command-line comparison against them and records peak memory.

Results for 1 million trades (MacBook, Colima VM with 4 CPUs and 6 GB, Oracle Free 23ai, `-Xmx1g`):

| Run | Time | Peak memory | Result |
|---|---|---|---|
| `ddl PERF_A PERF_B` | 3 s | 235 MB | the 3 planted schema changes (plus the backup table and grant without the filter) |
| `refs PERF_A --check-data` (filtered) | 3 s | 230 MB | 9 findings, all real: every planted reference, with 100 % resolution, and the ambiguous `TRADE.PRODUCT_ID` resolved by data (100 % to `PRODUCT_DESC`, 20 % to `PRODUCT_BOND`) |
| `data PERF_A PERF_B` (all tables, hash) | 44 s | 800 MB | exact: `TRADE` 1 000 conflicts / 300 only in one, `TRADE_KEYWORD` 500 / 600, `BO_MESSAGE` 0 / 200. Memory is mostly `PRODUCT_DESC` (200 000 rows, compared in memory) |
| `data --tables TRADE --max-rows 0` | 14 s | 350 MB | as above for `TRADE` |
| `snapshot` + `ddl PERF_B --snapshot` | 3 s each | 235 MB | a 42 KB snapshot; live `PERF_A` vs its snapshot shows 0 differences, `PERF_B` the 3 changes |

Memory stays flat as tables grow: it follows the number of differing rows, not the table size. Time is the
databases' hashing, about 10 s per million rows of a wide table on this container.

Against [reladiff](https://github.com/erezsh/reladiff) 0.6 (hashdiff, the cross-database algorithm) on the same data,
both report exactly the same differences:

| Table | oracle-db-merge | reladiff, 1 thread | reladiff, 4 threads |
|---|---|---|---|
| `TRADE`, 1 M rows, numeric key | 14 s | 48.5 s | 20.6 s |
| `TRADE_KEYWORD`, 3 M rows, two-column key | 28 s | 372.9 s | 158.6 s |
| `BO_MESSAGE`, 1 M rows, numeric key | 11 s | 14.1 s | 8.5 s |

oracle-db-merge times include starting the JVM and reading the schema (about 5 s per run). An earlier build took
59 / 84 / 25 s: it hashed every column separately, queried the databases one after the other and found the report's
sample rows by hashing the table again. Oracle's own statistics (`V$SQL`) showed the time was CPU spent hashing.

## Layout

```
cli/     command-line mode (no web server): argument parsing, exit codes, ddl/data/refs/plan/snapshot/connections
conn/    connection registry, encrypted saved connections, read-only / master-only connections
ddl/     extractor, model, comparator, filters, snapshots, HTML+Excel report
data/    row loader, matcher, compare service, HTML+Excel report
merge/   planner, FK graph, ALTER generator, script renderer, executor, rules, service, report
refs/    undeclared-reference detector, data check, HTML report
web/     REST controllers; static/index.html is the UI
perf/    (repo root) synthetic trading schema, data generator, drift script, timing runner
```
