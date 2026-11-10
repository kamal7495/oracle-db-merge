-- Read-only access for oracle-db-merge to one schema. Run as a DBA on the COPY you will compare (never production),
-- once per schema, and again after tables are added (grants are per object).
--
--   sqlplus / as sysdba   (or a DBA account), connected to the right PDB, then:
--   CREATE USER DBMERGE_RO IDENTIFIED BY "<a strong password>";      -- once; or use any existing read-only user
--   @docs/readonly_user.sql CALYPSO DBMERGE_RO
--
-- &1 = the schema to compare (e.g. CALYPSO), &2 = the read-only user. What it grants, and why:
--   CREATE SESSION          to log in
--   READ on every table, view and materialized view of &1
--                           to read rows; READ (unlike SELECT) cannot lock rows (no SELECT ... FOR UPDATE)
--   SELECT on its sequences to compare sequence settings
--   SELECT_CATALOG_ROLE     to read the DBA_* dictionary views and DBMS_METADATA. Without it Oracle hides the schema's
--                           PL/SQL, triggers and grants from another user, and a comparison would miss differences in
--                           them. It shows the metadata (not the rows) of every schema in the database.
-- Nothing here can change data or structure. 'cli preflight' checks exactly that before anything else runs.
WHENEVER SQLERROR EXIT FAILURE
SET VERIFY OFF SERVEROUTPUT ON
DEFINE SCHEMA = &1
DEFINE RO_USER = &2

GRANT CREATE SESSION TO &RO_USER;
GRANT SELECT_CATALOG_ROLE TO &RO_USER;

DECLARE
  n PLS_INTEGER := 0;
BEGIN
  FOR o IN (SELECT object_name, object_type FROM dba_objects
             WHERE owner = UPPER('&SCHEMA')
               AND object_type IN ('TABLE', 'VIEW', 'MATERIALIZED VIEW', 'SEQUENCE')
               AND object_name NOT LIKE 'BIN$%'
               AND generated = 'N'
               AND NOT (object_type = 'TABLE' AND EXISTS (SELECT 1 FROM dba_tables t WHERE t.owner = UPPER('&SCHEMA')
                          AND t.table_name = object_name AND (t.nested = 'YES' OR t.secondary = 'Y' OR t.iot_type = 'IOT_OVERFLOW')))) LOOP
    EXECUTE IMMEDIATE 'GRANT ' || CASE o.object_type WHEN 'SEQUENCE' THEN 'SELECT' ELSE 'READ' END
      || ' ON "' || UPPER('&SCHEMA') || '"."' || o.object_name || '" TO &RO_USER';
    n := n + 1;
  END LOOP;
  DBMS_OUTPUT.PUT_LINE(n || ' object grant(s) to &RO_USER on &SCHEMA.');
END;
/

-- Optional: lets the data comparison hash LOB columns (CLOB/BLOB) of large tables. Without it those tables are
-- reported as not compared. DBMS_CRYPTO only computes hashes.
-- GRANT EXECUTE ON SYS.DBMS_CRYPTO TO &RO_USER;
