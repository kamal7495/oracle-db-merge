# Oracle DB Merge

Compare and merge **N Oracle databases into one master**. Java 17, Maven, `ojdbc11`, Spring Boot 3.3 and a small web UI.

* **Phase 1** – extract DDL from every database, compare all of them against each other, classify each difference by severity, report as HTML and Excel.
* **Phase 2** – per-table row counts, matches and conflicts on a business key you choose (default: primary key), report as HTML and Excel.
* **Phase 3** – merge the others into the master you choose: dependency-ordered, collision-safe, dry-run by default, one transaction, validated before commit.
* **Undeclared references** – for one schema, list the columns that act like foreign keys but have no FOREIGN KEY constraint, optionally checked against the data. Run this before merging a schema whose integrity lives in the application (typical of vendor packages such as trading systems).

> **Status of this build – read this first.**
> `mvn verify` passes: the unit tests plus the Testcontainers integration tests against Oracle Free 23 (`SchemaExtractorIT`, `MergeIT`, `LobMergeIT`, `ReferenceIT`). These cover extraction, the Phase 2 comparison, planning, executing, rolling back and validating merges (including BLOB/CLOB columns), and the undeclared-reference detection with its data check.
> **Not covered by any test:** the REST controllers, the web UI and the Excel report writers. The server starts and serves the UI, but these have not been exercised end to end.
> **Do not point the executor at a real master before a dry run on a copy and a Data Pump backup.**

## Requirements

* JDK 17+, Maven 3.9+
* Network access to your Oracle databases. Oracle 12.2+ (the extractor reads `SEARCH_CONDITION_VC`); tested design target is Oracle 19c/23ai.
* Docker, only for the integration tests (`gvenzl/oracle-free:23-slim-faststart`)
* The account used per database needs `SELECT` on the `ALL_*` dictionary views (granted by default) and, for DDL text, `EXECUTE` on `DBMS_METADATA` (granted to `PUBLIC` by default). For the **master** account also `CREATE TABLE`/`ALTER` privileges in its own schema if you approve schema changes, plus `ALTER TRIGGER` if you use "disable triggers".

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

## Passwords

Held only in memory as `char[]`, wiped when a connection is removed, masked in every `toString`, never logged and never written to disk (not in reports, scripts, rules file or logs). Request logging is off and error messages are not echoed.

## Severity model (Phase 1)

| Severity | Examples |
|---|---|
| CRITICAL | incompatible data-type families (e.g. NUMBER vs VARCHAR2), missing or different primary key |
| HIGH | table missing, length/precision mismatch, missing foreign key / unique constraint, NOT NULL column without default missing |
| MEDIUM | nullability, check constraints, triggers, sequences, same-family type change |
| LOW | names, defaults, enabled flags |

Constraints and indexes are matched by structure (columns, type, referenced table), not by system-generated names. `NOT NULL` check constraints, identity defaults and sequence `LAST_NUMBER` are ignored.

## Phase 2 notes

* Business key defaults to the primary key. If the key is not the PK, same-PK-different-record cases are reported as **PK collisions**; same-key-different-PK as **PK divergence**.
* Rows are compared in memory. Tables above `dbmerge.max-rows-per-table` (default 200 000) are counted but not compared.
* Report sections: only in one DB, identical in several, different in several (column-level diffs), PK collisions.

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
* Everything is processed in memory (`max-rows-per-table`). Large tables need a different approach (staging tables / Data Pump / `MERGE`).
* FK cycles between tables are blocked rather than solved.
* **LOBs.** BLOB, CLOB and NCLOB are read whole, compared byte for byte / character for character, and written through bind variables. `LobMergeIT` covers 1–2 MB BLOBs, a 100 000-character Unicode CLOB, NULLs, empty LOBs (`EMPTY_BLOB()`/`EMPTY_CLOB()` stay empty, not NULL) and FK rewriting on tables with LOBs. Limits:
  * Each LOB is held in memory, plus a hex copy of each BLOB (about twice its size) for comparison. `max-rows-per-table` caps rows, not bytes, so tables of large documents or images can exhaust the heap. They need a streaming/hash-based approach.
  * Phase 2 reports show BLOBs as hex text.
  * BFILE, `LONG`, XMLType and object types are not handled.
* The generated `.sql` is a review copy: `COMMIT` is commented out and it ends in `ROLLBACK`. Values are written as literals, so CLOBs over 4 000 characters and BLOBs over 2 000 bytes produce `ORA-01704` there unless the database uses `MAX_STRING_SIZE=EXTENDED`. The app itself uses bind variables and is not affected.

## Tests

```bash
mvn test      # unit tests (no database): comparator, registry, row matcher, FK graph, planner, script/alter generators, report writers, reference detector
mvn verify    # also the Testcontainers integration tests (needs Docker): SchemaExtractorIT, MergeIT, LobMergeIT, ReferenceIT
```

The integration tests are skipped, not failed, when Testcontainers finds no Docker, so check the output says `Skipped: 0`. With Colima instead of Docker Desktop:

```bash
export DOCKER_HOST=unix://$HOME/.colima/default/docker.sock
export TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock
mvn verify
```

`LobMergeIT` builds its own `DOC`/`DOC_NOTE` tables (CLOB + BLOB) in code instead of using the sample scripts. `ReferenceIT` builds a small trading-style schema with **no** declared foreign keys and deliberately imperfect data (an orphan, a 1:1 product table, an ambiguous reference, a text column pointing at a number key), plus a second install whose re-keyed legal entity must make the merge refuse to run.

Sample schemas live in `src/test/resources/sample/schema_a.sql` (master) and `schema_b.sql` (source): FK chain `REGION ← CUSTOMER ← ORDERS ← ORDER_ITEM → PRODUCT`, PK collisions (customer 1 is Alice in A, Carol in B), identical duplicates (Bob, Widget), same person with different PK (Alice 1 vs 3), a table only in B (`AUDIT_NOTE`), a wider `EMAIL`, a different `QTY` type, an extra column, a missing index/trigger, and an order status that violates A's check constraint (used to prove rollback).

## Layout

```
conn/    connection registry, read-only / master-only connections
ddl/     extractor, model, comparator, HTML+Excel report
data/    row loader, matcher, compare service, HTML+Excel report
merge/   planner, FK graph, ALTER generator, script renderer, executor, rules, service, report
refs/    undeclared-reference detector, data check, HTML report
web/     REST controllers; static/index.html is the UI
```
