package com.dbmerge.ddl.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SourceNormalizerTest {

    private static boolean same(String a, String ownerA, String b, String ownerB) {
        return SourceNormalizer.sameSource(SourceNormalizer.normalize(a, ownerA), SourceNormalizer.normalize(b, ownerB));
    }

    @Test
    void whitespaceLineEndingsCaseAndCommentsDoNotCount() {
        String a = "PACKAGE BODY pkg AS\n  PROCEDURE p(x IN NUMBER) IS\n  BEGIN\n    x := x + 1; -- bump\n  END;\nEND;\n\n\n";
        String b = "package body PKG as\r\n/* reformatted */\r\nprocedure P ( x in number ) is begin x:=x+1;\r\nend; END;   \r\n";
        assertTrue(same(a, "A", b, "A"));
    }

    @Test
    void ownSchemaPrefixAndQuotesDoNotCount() {
        assertTrue(same("SELECT t.id FROM \"SAMPLE_A\".\"TRADE\" t", "SAMPLE_A", "select t.ID from sample_b.trade t", "SAMPLE_B"));
        assertTrue(same("CREATE OR REPLACE EDITIONABLE PACKAGE p AS END;", "A", "PACKAGE P AS END;", "A"));
        // Another schema's prefix is a real difference.
        assertFalse(same("SELECT * FROM OTHER.TRADE", "SAMPLE_A", "SELECT * FROM TRADE", "SAMPLE_A"));
    }

    @Test
    void stringLiteralsAreKeptExactly() {
        assertFalse(same("x := 'Hello';", "A", "x := 'HELLO';", "A"), "case inside a literal matters");
        assertFalse(same("x := 'a  b';", "A", "x := 'a b';", "A"), "spaces inside a literal matter");
        assertTrue(same("x := 'it''s -- not a comment';", "A", "X := 'it''s -- not a comment' ;", "A"));
        assertTrue(same("x := q'[don't /* keep */]';", "A", "x := Q'[don't /* keep */]';", "A"));
        assertFalse(same("x := q'[a]';", "A", "x := q'[b]';", "A"));
    }

    @Test
    void quotedMixedCaseIdentifiersStayQuoted() {
        assertFalse(same("SELECT \"MixedCase\" FROM t", "A", "SELECT MIXEDCASE FROM t", "A"));
        assertTrue(same("SELECT \"NAME\" FROM t", "A", "SELECT name FROM t", "A"));
    }

    @Test
    void spacesThatSeparateTokensAreKept() {
        assertFalse(same("SELECT a b FROM t", "A", "SELECT ab FROM t", "A"));
        assertFalse(same("x := - -1;", "A", "x := --1;", "A"), "'- -' is not a comment, '--' is");
    }

    @Test
    void firstDifferenceReportsOriginalLineNumbers() {
        List<SourceNormalizer.Line> a = SourceNormalizer.normalize("BEGIN\n  -- note\n\n  x := 1;\nEND;", "A");
        List<SourceNormalizer.Line> b = SourceNormalizer.normalize("BEGIN\n  x := 2;\nEND;", "A");
        SourceNormalizer.FirstDifference d = SourceNormalizer.firstDifference(a, b);
        assertNotNull(d);
        assertEquals(4, d.lineA());
        assertEquals("X:=1;", d.textA());
        assertEquals(2, d.lineB());
        assertEquals("X:=2;", d.textB());
        assertNull(SourceNormalizer.firstDifference(a, a));
    }

    @Test
    void excerptsAreCapped() {
        String longLine = "x := " + "a + ".repeat(100) + "1;";
        SourceNormalizer.FirstDifference d = SourceNormalizer.firstDifference(
                SourceNormalizer.normalize(longLine, "A"), SourceNormalizer.normalize("x := 0;", "A"));
        assertTrue(d.textA().length() <= SourceNormalizer.EXCERPT_CHARS + 1, d.textA());
    }
}
