package com.dbmerge.refs;

import static com.dbmerge.ddl.compare.Fixtures.fk;
import static com.dbmerge.ddl.compare.Fixtures.index;
import static com.dbmerge.ddl.compare.Fixtures.model;
import static com.dbmerge.ddl.compare.Fixtures.number;
import static com.dbmerge.ddl.compare.Fixtures.pk;
import static com.dbmerge.ddl.compare.Fixtures.table;
import static com.dbmerge.ddl.compare.Fixtures.varchar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.ddl.model.ColumnDef;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.refs.RefModel.Candidate;
import com.dbmerge.refs.RefModel.Confidence;
import com.dbmerge.refs.RefModel.Kind;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A Calypso-like schema where almost no relationship is declared, plus a few conventional tables. */
class ReferenceDetectorTest {

    private static ColumnDef date(String name, int pos) {
        return new ColumnDef(name, pos, "DATE", 7, null, null, null, true, null, false, false);
    }

    private static ColumnDef clob(String name, int pos) {
        return new ColumnDef(name, pos, "CLOB", 4000, null, null, null, true, null, false, false);
    }

    static final SchemaModel SCHEMA = model("TRADING",
            table("TRADE").col(number("TRADE_ID", 1, 10, 0, false), number("PRODUCT_ID", 2, 10, 0, false), number("BOOK_ID", 3, 10, 0, false),
                    number("PARENT_TRADE_ID", 4, 10, 0, true), number("CPTY_ID", 5, 10, 0, true), date("TRADE_DATE", 6),
                    varchar("STATUS", 7, 20, false), number("VERSION_NUM", 8, 10, 0, false))
                    .cons(pk("PK_TRADE", false, "TRADE_ID")).idx(index("IX_TRADE_BOOK", false, "BOOK_ID")).build(),
            table("PRODUCT_DESC").col(number("PRODUCT_ID", 1, 10, 0, false), varchar("PRODUCT_TYPE", 2, 32, false))
                    .cons(pk("PK_PRODUCT_DESC", false, "PRODUCT_ID")).build(),
            table("PRODUCT_BOND").col(number("PRODUCT_ID", 1, 10, 0, false), number("COUPON", 2, 12, 6, true))
                    .cons(pk("PK_PRODUCT_BOND", false, "PRODUCT_ID")).build(),
            table("BOOK").col(number("BOOK_ID", 1, 10, 0, false), number("LEGAL_ENTITY_ID", 2, 10, 0, false), varchar("BOOK_NAME", 3, 64, false))
                    .cons(pk("PK_BOOK", false, "BOOK_ID")).build(),
            table("LEGAL_ENTITY").col(number("LEGAL_ENTITY_ID", 1, 10, 0, false), varchar("SHORT_NAME", 2, 64, false))
                    .cons(pk("PK_LE", false, "LEGAL_ENTITY_ID")).build(),
            table("TRADE_KEYWORD").col(number("TRADE_ID", 1, 10, 0, false), varchar("KEYWORD_NAME", 2, 64, false), varchar("KEYWORD_VALUE", 3, 255, true))
                    .cons(pk("PK_TRADE_KW", false, "TRADE_ID", "KEYWORD_NAME")).build(),
            table("BO_MESSAGE").col(number("MESSAGE_ID", 1, 10, 0, false), number("OBJECT_ID", 2, 10, 0, true), varchar("OBJECT_CLASS", 3, 64, true),
                    varchar("TRADE_ID", 4, 32, true), number("LINKED_ID", 5, 10, 0, true), clob("BODY_ID", 6))
                    .cons(pk("PK_BO_MESSAGE", false, "MESSAGE_ID")).build(),
            table("CUSTOMER").col(number("ID", 1, 10, 0, false), varchar("NAME", 2, 100, false)).cons(pk("PK_CUSTOMER", false, "ID")).build(),
            table("ORDERS").col(number("ID", 1, 10, 0, false), number("CUSTOMER_ID", 2, 10, 0, false)).cons(pk("PK_ORDERS", false, "ID")).build(),
            table("ORDER_ITEM").col(number("ID", 1, 10, 0, false), number("ORDER_ID", 2, 10, 0, false))
                    .cons(pk("PK_ORDER_ITEM", false, "ID"), fk("FK_ITEM_ORDER", false, "ORDER_ID", "ORDERS", "ID")).build(),
            table("INVOICE").col(number("ID", 1, 10, 0, false), number("ORDER_ID", 2, 10, 0, false)).cons(pk("PK_INVOICE", false, "ID")).build());

    static Map<String, Candidate> found() {
        Map<String, Candidate> m = new LinkedHashMap<>();
        for (Candidate c : new ReferenceDetector().detect(SCHEMA)) m.put(c.qualifiedColumn(), c);
        return m;
    }

    @Test
    void sameNameAsAnotherTablesPrimaryKeyIsHigh() {
        Map<String, Candidate> f = found();
        Candidate kw = f.get("TRADE_KEYWORD.TRADE_ID");
        assertEquals(Kind.LIKELY_FK, kw.kind());
        assertEquals(Confidence.HIGH, kw.confidence());
        assertEquals(List.of("TRADE"), kw.parentTables());
        assertEquals("TRADE_ID", kw.parentColumn());
        assertEquals(List.of("BOOK"), f.get("TRADE.BOOK_ID").parentTables());
        assertTrue(f.get("TRADE.BOOK_ID").indexed());
        assertTrue(kw.indexed(), "leads the composite primary key");
        assertEquals(List.of("LEGAL_ENTITY"), f.get("BOOK.LEGAL_ENTITY_ID").parentTables());
    }

    @Test
    void ambiguousParentsAreListedAndDowngraded() {
        Candidate p = found().get("TRADE.PRODUCT_ID");
        assertEquals(Confidence.MEDIUM, p.confidence());
        assertEquals(List.of("PRODUCT_BOND", "PRODUCT_DESC"), p.parentTables());
        assertTrue(p.notes().get(0).startsWith("Ambiguous"));
    }

    @Test
    void sharedPrimaryKeysAreReportedWithoutGuessingTheDirection() {
        Map<String, Candidate> f = found();
        Candidate bond = f.get("PRODUCT_BOND.PRODUCT_ID");
        assertEquals(Kind.SHARED_PRIMARY_KEY, bond.kind());
        assertEquals(List.of("PRODUCT_DESC"), bond.parentTables());
        assertEquals(List.of("PRODUCT_BOND"), f.get("PRODUCT_DESC.PRODUCT_ID").parentTables());
        assertTrue(bond.rule().startsWith("shares primary key"));
    }

    @Test
    void tableNamePlusSuffixAlsoMatchesPluralTables() {
        Map<String, Candidate> f = found();
        Candidate cust = f.get("ORDERS.CUSTOMER_ID");
        assertEquals(Confidence.HIGH, cust.confidence());
        assertEquals(List.of("CUSTOMER"), cust.parentTables());
        assertEquals("ID", cust.parentColumn());
        Candidate inv = f.get("INVOICE.ORDER_ID");
        assertEquals(Confidence.MEDIUM, inv.confidence(), "plural table name is weaker evidence");
        assertEquals(List.of("ORDERS"), inv.parentTables());
    }

    @Test
    void roleprefixedColumnsAndSelfReferences() {
        Candidate parent = found().get("TRADE.PARENT_TRADE_ID");
        assertEquals(Kind.LIKELY_FK, parent.kind());
        assertEquals(Confidence.MEDIUM, parent.confidence());
        assertEquals(List.of("TRADE"), parent.parentTables());
        assertTrue(parent.notes().stream().anyMatch(n -> n.startsWith("Self-reference")));
    }

    @Test
    void typeMismatchLowersConfidence() {
        Candidate t = found().get("BO_MESSAGE.TRADE_ID");
        assertEquals(Confidence.MEDIUM, t.confidence(), "VARCHAR2 child of a NUMBER key");
        assertTrue(t.notes().stream().anyMatch(n -> n.startsWith("Type differs")));
    }

    @Test
    void typePlusIdPairsAndUnmatchedIds() {
        Map<String, Candidate> f = found();
        assertEquals(Kind.POLYMORPHIC, f.get("BO_MESSAGE.OBJECT_ID").kind());
        assertTrue(f.get("BO_MESSAGE.OBJECT_ID").rule().contains("OBJECT_CLASS"));
        assertEquals(Kind.UNRESOLVED_ID, f.get("BO_MESSAGE.LINKED_ID").kind());
        assertEquals(Kind.UNRESOLVED_ID, f.get("TRADE.CPTY_ID").kind());
        assertEquals(Confidence.LOW, f.get("TRADE.CPTY_ID").confidence());
    }

    @Test
    void skipsDeclaredKeysOwnKeysAndNonKeyTypes() {
        Map<String, Candidate> f = found();
        assertNull(f.get("ORDER_ITEM.ORDER_ID"), "declared foreign key");
        assertNull(f.get("TRADE.TRADE_ID"), "the table's own primary key, not shared");
        assertNull(f.get("CUSTOMER.ID"));
        assertNull(f.get("TRADE.TRADE_DATE"), "dates cannot hold keys");
        assertNull(f.get("BO_MESSAGE.BODY_ID"), "LOBs cannot hold keys");
        assertNull(f.get("TRADE.STATUS"));
        assertNull(f.get("TRADE.VERSION_NUM"), "_NUM with no matching table is not flagged on its own");
        assertNull(f.get("PRODUCT_DESC.PRODUCT_TYPE"));
        assertNull(f.get("BO_MESSAGE.MESSAGE_ID"));
    }

    @Test
    void highestConfidenceComesFirst() {
        List<Candidate> all = new ReferenceDetector().detect(SCHEMA);
        for (int i = 1; i < all.size(); i++) {
            assertTrue(all.get(i - 1).confidence().compareTo(all.get(i).confidence()) <= 0, "sorted by confidence");
        }
    }

    @Test
    void aUniqueNaturalKeyIsNotAnUnresolvedReference() {
        var withUnique = table("DEAL").col(number("DEAL_ID", 1, 10, 0, false), varchar("EXTERNAL_REF", 2, 40, false),
                        varchar("CPTY_REF", 3, 40, true))
                .cons(pk("PK_DEAL", false, "DEAL_ID"), com.dbmerge.ddl.compare.Fixtures.unique("UQ_DEAL_EXT", false, "EXTERNAL_REF")).build();
        Map<String, Candidate> f = new java.util.HashMap<>();
        for (Candidate c : new ReferenceDetector().detect(model("M", withUnique))) f.put(c.qualifiedColumn(), c);
        assertNull(f.get("DEAL.EXTERNAL_REF"), "unique on its own: a key of DEAL, not a reference");
        assertEquals(Kind.UNRESOLVED_ID, f.get("DEAL.CPTY_REF").kind(), "a non-unique _REF column is still reported");
    }
}
