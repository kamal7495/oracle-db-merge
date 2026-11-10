package com.dbmerge.check;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.ddl.Dictionary;
import com.dbmerge.merge.MergeModel.Issue;
import com.dbmerge.merge.MergeModel.Level;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PreflightRulesTest {

    @Test
    void systemPrivilegesThatWriteElsewhereAreFlagged() {
        for (String p : List.of("INSERT ANY TABLE", "UPDATE ANY TABLE", "DELETE ANY TABLE", "DROP ANY TABLE", "ALTER ANY TABLE",
                "CREATE ANY TABLE", "EXECUTE ANY PROCEDURE", "LOCK ANY TABLE", "FLASHBACK ANY TABLE", "GRANT ANY PRIVILEGE",
                "GRANT ANY OBJECT PRIVILEGE", "ALTER SYSTEM", "BECOME USER", "SYSDBA", "CREATE ANY TRIGGER")) {
            assertTrue(Preflight.writeSystemPrivilege(p), p);
        }
        for (String p : List.of("CREATE SESSION", "SELECT ANY TABLE", "READ ANY TABLE", "SELECT ANY DICTIONARY", "SELECT ANY SEQUENCE",
                "DEBUG ANY PROCEDURE", "DEBUG CONNECT ANY", "CREATE TABLE", "CREATE PROCEDURE", "UNLIMITED TABLESPACE")) {
            assertFalse(Preflight.writeSystemPrivilege(p), p);
        }
    }

    @Test
    void defaultGrantsOnTheUserItselfAreNotWrites() {
        for (String p : List.of("SELECT", "READ", "INHERIT PRIVILEGES", "DEBUG")) assertTrue(Preflight.READ_OBJECT_PRIVS.contains(p), p);
        for (String p : List.of("INSERT", "UPDATE", "DELETE", "ALTER", "INDEX", "REFERENCES", "EXECUTE")) {
            assertFalse(Preflight.READ_OBJECT_PRIVS.contains(p), p);
        }
    }

    @Test
    void dbaDictionaryRewritesEveryAllView() {
        Dictionary dba = new Dictionary(true, "RO", null);
        assertEquals("SELECT t.table_name FROM dba_tables t WHERE NOT EXISTS (SELECT 1 FROM dba_mviews m WHERE m.owner = t.owner)",
                dba.sql("SELECT t.table_name FROM all_tables t WHERE NOT EXISTS (SELECT 1 FROM ALL_MVIEWS m WHERE m.owner = t.owner)"));
        assertEquals("SELECT DISTINCT table_name FROM dba_tab_privs WHERE owner = ? AND grantee <> ?",
                dba.sql("SELECT DISTINCT table_name FROM all_tab_privs WHERE table_schema = ? AND grantee <> ?"));
        assertEquals("SELECT * FROM dba_tab_cols", dba.sql("SELECT * FROM all_tab_cols"));
        assertEquals("SELECT table_schema FROM x_all_tables", dba.sql("SELECT table_schema FROM x_all_tables"), "only whole words");
        Dictionary all = new Dictionary(false, "OWNER", null);
        assertEquals("SELECT * FROM all_tables", all.sql("SELECT * FROM all_tables"));
    }

    @Test
    void scrubDropsLiteralsButKeepsOracleCodes() {
        assertEquals("Value '…' cannot be converted to AMOUNT (NUMBER(10,2))", ShareSummary.scrub("Value 'ABC-123' cannot be converted to AMOUNT (NUMBER(10,2))"));
        assertEquals("3 row(s) e.g. # and #", ShareSummary.scrub("3 row(s) e.g. 1000001 and 99999"));
        assertEquals("ORA-00942: table or view does not exist", ShareSummary.scrub("ORA-00942: table or view does not exist"));
        assertEquals("column \"T\".\"C\" kept", ShareSummary.scrub("column \"T\".\"C\" kept"), "identifiers stay");
    }

    @Test
    void errorsAreReducedToClassAndOracleCodes() {
        SQLException e = new SQLException("ORA-01722: invalid number for 'secret' at ORA-06512 line 3");
        assertEquals("SQLException ORA-01722, ORA-06512", ShareSummary.safeError(e));
        assertEquals("IllegalStateException", ShareSummary.safeError(new IllegalStateException("value 'secret' broke")));
    }

    @Test
    void issueKindsDoNotNeedTheMessage() {
        assertEquals("orphan rows", ShareSummary.issueKind(new Issue(Level.BLOCKER, "T",
                "4 row(s) reference a parent row that does not exist (e.g. 'k1')")));
        assertEquals("value does not fit the master column", ShareSummary.issueKind(new Issue(Level.BLOCKER, "T", "Value 'x' cannot be converted to A")));
        assertEquals("primary key collision", ShareSummary.issueKind(new Issue(Level.BLOCKER, "T", "Primary key collision for k from B")));
        assertEquals("other", ShareSummary.issueKind(new Issue(Level.INFO, "T", "Something new")));
    }

    @Test
    void sizesAndDurationsRead() {
        assertEquals("1.5 GB", Estimate.bytes(1_610_612_736L));
        assertEquals("300 B", Estimate.bytes(300));
        assertEquals("45 s", Estimate.duration(45));
        assertEquals("6 min", Estimate.duration(360));
        assertEquals("2.0 h", Estimate.duration(7200));
    }
}
