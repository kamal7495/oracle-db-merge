package com.dbmerge.merge;

import static com.dbmerge.ddl.compare.Fixtures.number;
import static com.dbmerge.ddl.compare.Fixtures.table;
import static com.dbmerge.ddl.compare.Fixtures.varchar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.dbmerge.ddl.model.ColumnDef;
import com.dbmerge.ddl.model.TableDef;
import org.junit.jupiter.api.Test;

class EmptyLobsTest {

    private static ColumnDef lob(String name, int pos, String type) {
        return new ColumnDef(name, pos, type, null, null, null, null, true, null, false, false);
    }

    private static final TableDef DOC = table("DOC")
            .col(number("ID", 1, 10, 0, false), varchar("TITLE", 2, 50, true), lob("BODY", 3, "CLOB"),
                 lob("NBODY", 4, "NCLOB"), lob("CONTENT", 5, "BLOB"), new ColumnDef("HASH", 6, "RAW", 16, null, null, null, true, null, false, false))
            .build();

    @Test
    void emptyLobValuesBecomeEmptyLobFunctions() {
        assertEquals("EMPTY_CLOB()", EmptyLobs.expression(DOC, "BODY", ""));
        assertEquals("EMPTY_CLOB()", EmptyLobs.expression(DOC, "NBODY", ""));
        assertEquals("EMPTY_BLOB()", EmptyLobs.expression(DOC, "CONTENT", new byte[0]));
    }

    @Test
    void everythingElseIsBoundNormally() {
        assertNull(EmptyLobs.expression(DOC, "BODY", "text"));
        assertNull(EmptyLobs.expression(DOC, "BODY", null), "NULL stays NULL");
        assertNull(EmptyLobs.expression(DOC, "CONTENT", new byte[] {1}));
        assertNull(EmptyLobs.expression(DOC, "CONTENT", null));
        assertNull(EmptyLobs.expression(DOC, "TITLE", ""), "VARCHAR2: '' is NULL in Oracle anyway");
        assertNull(EmptyLobs.expression(DOC, "HASH", new byte[0]), "RAW: an empty value is NULL in Oracle anyway");
        assertNull(EmptyLobs.expression(DOC, "MISSING", ""));
        assertNull(EmptyLobs.expression(null, "BODY", ""));
    }
}
