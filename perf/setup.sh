#!/usr/bin/env bash
# Creates PERF_A and PERF_B in a local Oracle container and fills them with synthetic trading data.
#
#   ORACLE_PASSWORD=...  DBMERGE_PERF_PASSWORD=...  perf/setup.sh [TRADES] [CONTAINER]
#
# TRADES defaults to 1000000, CONTAINER to oracle-dev. Passwords are passed to the container as environment
# variables (by name, never on a command line) and fed to sqlplus on stdin. Existing PERF_A/PERF_B are dropped.
set -euo pipefail
TRADES="${1:-1000000}"
CONTAINER="${2:-oracle-dev}"
: "${ORACLE_PASSWORD:?set ORACLE_PASSWORD (SYSTEM password of the container)}"
: "${DBMERGE_PERF_PASSWORD:?set DBMERGE_PERF_PASSWORD (password for PERF_A and PERF_B)}"
[[ "$TRADES" =~ ^[0-9]+$ ]] || { echo "TRADES must be a number" >&2; exit 2; }
[[ "$DBMERGE_PERF_PASSWORD" =~ ^[A-Za-z0-9_]+$ ]] || { echo "DBMERGE_PERF_PASSWORD: letters, digits and _ only" >&2; exit 2; }
HERE="$(cd "$(dirname "$0")" && pwd)"

docker exec "$CONTAINER" mkdir -p /tmp/dbmerge-perf
for f in trading_schema.sql trading_data.sql drift_b.sql; do docker cp "$HERE/$f" "$CONTAINER:/tmp/dbmerge-perf/$f"; done

sql() { # sql <user-var-name or SYSTEM> ; script on stdin
  docker exec -i -e ORACLE_PASSWORD -e DBMERGE_PERF_PASSWORD -e WHO="$1" "$CONTAINER" bash -c '
    if [ "$WHO" = SYSTEM ]; then PW="$ORACLE_PASSWORD"; else PW="$DBMERGE_PERF_PASSWORD"; fi
    { echo "CONNECT $WHO/\"$PW\"@localhost:1521/FREEPDB1"; cat; } | sqlplus -s /nolog'
}

echo "== users"
# The password is expanded inside the container and reaches sqlplus on stdin, never as a process argument.
docker exec -i -e ORACLE_PASSWORD -e DBMERGE_PERF_PASSWORD "$CONTAINER" bash -c '
  { echo "CONNECT SYSTEM/\"$ORACLE_PASSWORD\"@localhost:1521/FREEPDB1"
    echo "WHENEVER SQLERROR CONTINUE"
    echo "DROP USER PERF_A CASCADE;"; echo "DROP USER PERF_B CASCADE;"; echo "DROP ROLE DBM_PERF_READER;"
    echo "WHENEVER SQLERROR EXIT FAILURE"
    echo "CREATE ROLE DBM_PERF_READER;"
    for u in PERF_A PERF_B; do
      echo "CREATE USER $u IDENTIFIED BY \"$DBMERGE_PERF_PASSWORD\" QUOTA UNLIMITED ON USERS;"
      echo "GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE PROCEDURE, CREATE SEQUENCE, CREATE SYNONYM TO $u;"
      echo "GRANT DBM_PERF_READER TO $u WITH ADMIN OPTION;"
    done
    echo "EXIT"; } | sqlplus -s /nolog'

for u in PERF_A PERF_B; do
  echo "== $u: schema"
  sql "$u" < "$HERE/trading_schema.sql"
  echo "== $u: data ($TRADES trades)"
  start=$SECONDS
  sql "$u" <<SQL
@/tmp/dbmerge-perf/trading_data.sql $TRADES
SQL
  echo "   loaded in $((SECONDS - start)) s"
done

echo "== grants (PERF_B lacks the one on BO_MESSAGE)"
sql PERF_A <<SQL
WHENEVER SQLERROR EXIT FAILURE
GRANT SELECT ON TRADE TO DBM_PERF_READER;
GRANT SELECT ON V_TRADE_SUMMARY TO DBM_PERF_READER;
GRANT SELECT ON BO_MESSAGE TO DBM_PERF_READER;
EXIT
SQL
sql PERF_B <<SQL
WHENEVER SQLERROR EXIT FAILURE
GRANT SELECT ON TRADE TO DBM_PERF_READER;
GRANT SELECT ON V_TRADE_SUMMARY TO DBM_PERF_READER;
EXIT
SQL

echo "== PERF_B: drift"
sql PERF_B <<SQL
@/tmp/dbmerge-perf/drift_b.sql $TRADES
SQL

echo "== row counts"
sql PERF_A <<SQL
SET PAGESIZE 100 LINESIZE 120 FEEDBACK OFF
COLUMN table_name FORMAT A20
SELECT 'PERF_A' db, table_name, num_rows FROM user_tables WHERE num_rows > 0 ORDER BY num_rows DESC;
EXIT
SQL
echo "done"
