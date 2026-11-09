package com.dbmerge.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.ddl.model.ColumnDef;
import com.dbmerge.ddl.model.TableDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OracleHashSourceTest {

    private static ColumnDef col(String name, String type) {
        return new ColumnDef(name, 1, type, 30, null, null, "B", true, null, false, false);
    }

    @Test
    void piecesUseNlsIndependentFormatsAndMarkNulls() {
        assertTrue(OracleHashSource.piece(col("AMT", "NUMBER"), false, false).contains("'TM9', 'NLS_NUMERIC_CHARACTERS=''.,'''"));
        assertTrue(OracleHashSource.piece(col("D", "DATE"), false, false).contains("'SYYYY-MM-DD HH24:MI:SS'"));
        assertTrue(OracleHashSource.piece(col("TS", "TIMESTAMP(6) WITH TIME ZONE"), false, false).contains("SYS_EXTRACT_UTC"));
        String text = OracleHashSource.piece(col("NAME", "VARCHAR2"), false, false);
        assertTrue(text.startsWith("CASE WHEN t.\"NAME\" IS NULL THEN 'N'"), text);
        assertTrue(OracleHashSource.piece(col("NAME", "VARCHAR2"), false, true).contains("UTL_I18N.STRING_TO_RAW"));
    }

    @Test
    void lobsNeedDbmsCryptoAndSomeTypesAreNeverHashed() {
        assertNull(OracleHashSource.piece(col("DOC", "CLOB"), false, false));
        String lob = OracleHashSource.piece(col("DOC", "BLOB"), true, false);
        assertTrue(lob.contains("DBMS_CRYPTO.HASH") && lob.contains("THEN 'E'"), lob);
        for (String t : List.of("LONG", "LONG RAW", "XMLTYPE", "BFILE", "UROWID", "SDO_GEOMETRY")) {
            assertNull(OracleHashSource.piece(col("X", t), true, false), t);
        }
        assertTrue(OracleHashSource.isLong(col("X", "LONG RAW")));
    }

    @Test
    void identifiersAreQuoted() {
        String p = OracleHashSource.piece(col("we\"ird", "VARCHAR2"), false, false);
        assertTrue(p.contains("t.\"we\"\"ird\""), p);
    }

    @Test
    void manyColumnsAreHashedInGroupsBelowTheVarcharLimit() {
        List<String> pieces = new ArrayList<>();
        for (int i = 0; i < 250; i++) pieces.add("P" + i);
        String sql = OracleHashSource.combine(pieces);
        assertEquals(4, sql.split("STANDARD_HASH", -1).length - 1, "three groups of up to 100, then one over the groups");
        assertEquals("'0'", OracleHashSource.combine(List.of()));
        assertEquals("RAWTOHEX(STANDARD_HASH(P1, 'MD5'))", OracleHashSource.combine(List.of("P1")));
    }

    @Test
    void generatedQueriesFilterBucketsAndBindOnlyKeyHashes() {
        Map<String, ColumnDef> cols = new LinkedHashMap<>();
        cols.put("ID", col("ID", "NUMBER"));
        cols.put("NAME", col("NAME", "VARCHAR2"));
        cols.put("NOTES", col("NOTES", "LONG"));
        TableDef def = new TableDef("T", cols, List.of(), List.of(), List.of());
        OracleHashSource s = new OracleHashSource(Map.of(), Map.of("A", "OWNER"), Map.of("A", def), "T", List.of("ID"),
                List.of(), List.of("ID", "NAME"), List.of("ID", "NAME"), new OracleHashSource.Options(false, false, 60));

        String agg = s.keyBucketsSql("A", 50);
        assertTrue(agg.contains("ORA_HASH(DBM$KH, 49)") && agg.contains("FROM \"OWNER\".\"T\" t") && agg.contains("GROUP BY DBM$B"), agg);
        String rows = s.rowsSql("A", "DBM$KH", 50, List.of(3, 9));
        assertTrue(rows.endsWith("WHERE ORA_HASH(x.DBM$KH, 49) IN (3, 9) ORDER BY 1"), rows);
        List<Integer> many = new ArrayList<>();
        for (int i = 0; i < 1001; i++) many.add(i);
        assertFalse(s.rowsSql("A", "DBM$KH", 2000, many).contains("WHERE"), "long bucket lists are skipped in Java instead");
        String sample = s.sampleSql("A", 3);
        assertTrue(sample.endsWith("IN (?, ?, ?)") && !sample.contains("NOTES"), sample);
    }

    private static ColumnDef col(String name, String type, int len) {
        return new ColumnDef(name, 1, type, len, null, null, "B", true, null, false, false);
    }

    private static TableDef table(ColumnDef... cs) {
        Map<String, ColumnDef> cols = new LinkedHashMap<>();
        for (ColumnDef c : cs) cols.put(c.name(), c);
        return new TableDef("T", cols, List.of(), List.of(), List.of());
    }

    private static int count(String s, String what) {
        return s.split(java.util.regex.Pattern.quote(what), -1).length - 1;
    }

    @Test
    void shortColumnsAreWrittenInlineAndHashedOncePerRow() {
        TableDef def = table(col("ID", "NUMBER"), col("CCY", "VARCHAR2", 3), col("TRADE_DATE", "DATE"),
                col("COMMENTS", "VARCHAR2", 2000), col("DESCR", "NVARCHAR2", 20));
        OracleHashSource s = new OracleHashSource(Map.of(), Map.of("A", "O"), Map.of("A", def), "T", List.of("ID"), List.of(),
                List.of("CCY", "TRADE_DATE", "COMMENTS", "DESCR"), List.of("ID"), new OracleHashSource.Options(false, false, 60));
        String inner = s.inner("A");
        // key: 1 MD5; compared: 1 MD5 over the group + 1 each for the long and the national-character column
        assertEquals(4, count(inner, "STANDARD_HASH"), inner);
        assertTrue(inner.contains("'V' || TO_CHAR(LENGTH(t.\"CCY\")) || ':' || t.\"CCY\""), inner);
        assertTrue(inner.contains("'H' || RAWTOHEX(STANDARD_HASH(t.\"COMMENTS\""), "long text is hashed on its own: " + inner);
        assertTrue(inner.contains("STANDARD_HASH(t.\"DESCR\""), "NVARCHAR2 would turn the group into NVARCHAR2: " + inner);
        assertEquals(Integer.MAX_VALUE, OracleHashSource.inlineBytes(col("X", "VARCHAR2", 30), true), "AL32UTF8 bytes are hashed");
        assertEquals(120, OracleHashSource.inlineBytes(new ColumnDef("X", 1, "VARCHAR2", 30, null, null, "C", true, null, false, false), false));
    }

    @Test
    void everyDatabaseBuildsTheSameShapeEvenWhenColumnSizesDiffer() {
        // B widened NAME beyond the inline limit: both must then hash it on its own, or equal rows would hash differently.
        TableDef a = table(col("ID", "NUMBER"), col("NAME", "VARCHAR2", 30));
        TableDef b = table(col("ID", "NUMBER"), col("NAME", "VARCHAR2", 400));
        OracleHashSource s = new OracleHashSource(Map.of(), Map.of("A", "O", "B", "O"), Map.of("A", a, "B", b), "T",
                List.of("ID"), List.of(), List.of("NAME"), List.of("ID"), new OracleHashSource.Options(false, false, 60));
        assertEquals(s.inner("A"), s.inner("B"));
        assertTrue(s.inner("A").contains("'H' || RAWTOHEX(STANDARD_HASH(t.\"NAME\""));
    }

    @Test
    void wideRowsAreSplitIntoGroupsUnderTheVarchar2Limit() {
        List<ColumnDef> cs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 40; i++) { cs.add(col("C" + i, "VARCHAR2", 200)); names.add("C" + i); }
        cs.add(0, col("ID", "NUMBER"));
        OracleHashSource s = new OracleHashSource(Map.of(), Map.of("A", "O"), Map.of("A", table(cs.toArray(ColumnDef[]::new))), "T",
                List.of("ID"), List.of(), names, List.of("ID"), new OracleHashSource.Options(false, false, 60));
        // 40 x (200 + 8) bytes = 8320 > 3800 per group: three groups, plus one MD5 over them, plus the key
        assertEquals(5, count(s.inner("A"), "STANDARD_HASH"), s.inner("A"));
    }

    @Test
    void samplesAreLookedUpByKeyValues() {
        TableDef def = table(col("TRADE_ID", "NUMBER"), col("KEYWORD", "VARCHAR2", 32), col("VAL", "VARCHAR2", 100));
        OracleHashSource one = new OracleHashSource(Map.of(), Map.of("A", "O"), Map.of("A", def), "T", List.of("TRADE_ID"), List.of(),
                List.of("VAL"), List.of("TRADE_ID", "VAL"), new OracleHashSource.Options(false, false, 60));
        assertTrue(one.sampleByKeySql("A", 2).endsWith("WHERE t.\"TRADE_ID\" IN (?, ?)"), one.sampleByKeySql("A", 2));
        OracleHashSource two = new OracleHashSource(Map.of(), Map.of("A", "O"), Map.of("A", def), "T", List.of("TRADE_ID", "KEYWORD"),
                List.of(), List.of("VAL"), List.of("TRADE_ID", "KEYWORD", "VAL"), new OracleHashSource.Options(false, false, 60));
        assertTrue(two.sampleByKeySql("A", 2).endsWith("WHERE (t.\"TRADE_ID\", t.\"KEYWORD\") IN ((?, ?), (?, ?))"),
                two.sampleByKeySql("A", 2));
    }
}
