# First run against a real database

How to point oracle-db-merge at a real schema (for example a Calypso or Murex copy) for the first time without
risk, and what to send back. Nothing in these steps writes to any database: `preflight` reads only the dictionary,
and `bundle` reads in READ ONLY transactions and runs the merge as a dry run. There is no command-line command that
executes a merge.

## 1. Pick the databases

* Use **copies**: a UAT instance or a restore of a backup. Never production for a first run. The data
  comparison makes the database hash every row, which is real CPU and I/O on that server.
* You need at least two schemas: a *master* (the one a merge would go into) and one or more *sources*. For a
  comparison only, any of them can be the master.
* Note each database's name (`SELECT SYS_CONTEXT('USERENV','DB_NAME') FROM dual`). The check prints it, so you
  can confirm you reached the right one.

## 2. Create a read-only user (a DBA does this, on each copy)

```sql
CREATE USER DBMERGE_RO IDENTIFIED BY "<a strong password>";
@docs/readonly_user.sql CALYPSO DBMERGE_RO      -- once per schema to compare
```

It grants `CREATE SESSION`, `READ` on every table and view of the schema, `SELECT` on its sequences, and
`SELECT_CATALOG_ROLE`. The role matters: without it Oracle hides the schema's PL/SQL, triggers and grants from
another user, and two copies would both look as if they had none, so real differences would be hidden.
`EXECUTE ON SYS.DBMS_CRYPTO` is optional (commented out in the script); it lets large tables with CLOB/BLOB
columns be compared too.

Do not use the schema owner's login. The check refuses it, because that login can change every table.

## 3. Register the connections

Either in the web UI (Connections, with *Save*; the password is stored encrypted), or in a small configuration file
without passwords:

```yaml
# calypso.yml
dbmerge:
  preload:
    - {nickname: UAT1, host: uat1-db, port: 1521, service-name: CALYPSO_UAT1, username: DBMERGE_RO, schema: CALYPSO}
    - {nickname: UAT2, host: uat2-db, port: 1521, service-name: CALYPSO_UAT2, username: DBMERGE_RO, schema: CALYPSO}
```

The password then comes from an environment variable (never from the command line):
`--config calypso.yml --password-env UAT1=RO_PW --password-env UAT2=RO_PW`. The commands below leave these options out.

## 4. Run the read-only check

```
java -jar oracle-db-merge.jar cli preflight --master UAT1 UAT2
```

It takes a few seconds and scans no table. For each connection it shows the database and schema it reached, then:

| Check | FAIL means | Fix |
|---|---|---|
| Login is not the schema owner | you connected as the owner | use the read-only user |
| No system privilege that writes to other schemas | e.g. `UPDATE ANY TABLE`, `DBA` | a user without them |
| No write grants on the schema | `INSERT`/`UPDATE`/`DELETE`/`ALTER` on its objects | revoke them, or a fresh user |
| Whole schema visible | no `SELECT_CATALOG_ROLE` | grant it (step 2) |
| Every table readable | tables added after the grants | rerun `readonly_user.sql` |
| Disk space for plan files | the dry run may not fit | `--out` on a bigger disk, or `--skip-plan` |

WARN lines are information: tables without a key, missing statistics (sizes then unknown), no `DBMS_CRYPTO`.
The estimates at the end are based on a laptop benchmark. On a real server they show the order of magnitude, not the exact time.

## 5. Run the bundle

```
java -Xmx2g -jar oracle-db-merge.jar cli bundle --master UAT1 UAT2 --exclude 'BKP_*,TMP_*'
```

It repeats the check and stops if anything FAILs. Otherwise it writes `reports/bundle-<timestamp>/`. That contains the schema
comparison, the undeclared-reference report (with value counts), the data comparison and a merge dry run. A step
that fails is recorded and the others still run. Useful options:

* `--exclude PATTERNS` / `--include PATTERNS`: leave out backup and scratch tables, or look at a subset first.
* `--key TABLE=COLUMNS`: business keys for tables matched by something other than their primary key (in a merge
  between copies with independent id sequences, almost every table).
* `--skip-data`, `--skip-plan`: for a quick first look at a very large schema.
* `--no-check-data`: skip counting unresolved values for undeclared references.

## 6. Read the results, and what to send

```
bundle-<timestamp>/
  README.txt
  share/     summary.md, summary.json   counts, check results, schema object names, schema differences
  private/   full reports: data samples, merge script with row values, PL/SQL source, run.log
```

* **`share/`** has no row values, keys, sample rows, host names or passwords. It is built from counts and names,
  never by copying report text. It still names your tables, columns and PL/SQL objects and describes schema
  differences, so read `summary.md` before it leaves your organisation.
* **`private/`** is for you: it shows real rows. Keep it in-house. The files are readable only by you.

Send `share/summary.md` (or `summary.json`) back if you want help reading the results or finding what to fix next. The most
useful parts are the step times next to the estimates, the schema differences, the undeclared references with how
many values resolve, and the dry run's blockers per table.

## 7. Afterwards

`DROP USER DBMERGE_RO;` on each copy when you are done. The bundle folder can be deleted like any other folder.
