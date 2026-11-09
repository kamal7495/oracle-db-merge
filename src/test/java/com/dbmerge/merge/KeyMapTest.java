package com.dbmerge.merge;

import static com.dbmerge.ddl.compare.Fixtures.number;
import static com.dbmerge.ddl.compare.Fixtures.pk;
import static com.dbmerge.ddl.compare.Fixtures.table;
import static com.dbmerge.ddl.compare.Fixtures.varchar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.ddl.model.ColumnDef;
import com.dbmerge.ddl.model.TableDef;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KeyMapTest {

    @Test
    void longSetBehavesLikeAHashSetIncludingDeletes() {
        KeySet.LongSet s = new KeySet.LongSet();
        Set<Long> ref = new HashSet<>();
        Random rnd = new Random(42);
        for (int i = 0; i < 200_000; i++) {
            long v = rnd.nextInt(5_000) - 2_500; // many collisions and re-adds
            if (i % 97 == 0) v = Long.MIN_VALUE;   // the value that marks free slots internally
            switch (rnd.nextInt(3)) {
                case 0 -> assertEquals(ref.add(v), s.add(v));
                case 1 -> assertEquals(ref.remove(v), s.remove(v));
                default -> assertEquals(ref.contains(v), s.contains(v));
            }
        }
        assertEquals(ref.size(), s.size());
        for (long v = -2_600; v < 2_600; v++) assertEquals(ref.contains(v), s.contains(v), "value " + v);
    }

    @Test
    void keySetUsesLongsOnlyForCanonicalWholeNumbers() {
        assertEquals(12L, KeySet.asLong("12"));
        assertEquals(-7L, KeySet.asLong("-7"));
        for (String notLong : List.of("012", "1.5", "1E3", "", "abc", "99999999999999999999", "-0x1")) {
            assertNull(KeySet.asLong(notLong), notLong);
        }
        KeySet k = new KeySet();
        assertTrue(k.add("12"));
        assertTrue(k.add("012"), "a different key, kept as text");
        assertTrue(k.add("A\u0001B"));
        assertFalse(k.add("12"));
        assertTrue(k.contains("A\u0001B") && k.contains("012") && !k.contains("13"));
        KeySet copy = k.copy();
        k.remove("12");
        assertTrue(copy.contains("12") && !k.contains("12"), "copies are independent");
        assertEquals(3, copy.size());
    }

    @Test
    void parseIsTheInverseOfCanonOf() {
        ColumnDef num = number("N", 1, 12, 2, true);
        ColumnDef txt = varchar("T", 2, 20, true);
        ColumnDef ts = new ColumnDef("TS", 3, "TIMESTAMP(6)", null, null, 6, null, true, null, false, false);
        ColumnDef tz = new ColumnDef("TZ", 4, "TIMESTAMP(6) WITH TIME ZONE", null, null, 6, null, true, null, false, false);
        ColumnDef raw = new ColumnDef("R", 5, "RAW", 4, null, null, null, true, null, false, false);
        List<Object[]> cases = List.of(
                new Object[] {new BigDecimal("1.50"), num}, new Object[] {new BigDecimal("-42"), num}, new Object[] {BigDecimal.ZERO, num},
                new Object[] {"Alice ", txt}, new Object[] {Timestamp.valueOf("2024-01-01 08:00:00"), ts},
                new Object[] {Timestamp.valueOf("2024-01-01 08:00:01.123456"), ts},
                new Object[] {OffsetDateTime.of(2024, 1, 1, 9, 0, 0, 0, ZoneOffset.ofHours(1)), tz}, new Object[] {new byte[] {1, (byte) 0xAB}, raw});
        for (Object[] c : cases) {
            String canon = Canon.of(c[0]);
            assertEquals(canon, Canon.of(Canon.parse(canon, (ColumnDef) c[1])), c[0].toString());
        }
        assertNull(Canon.parse("\u0000NULL", num));
    }

    @Test
    void keyMapKeepsOnlyChangedKeysAndRebuildsTheRest() {
        TableDef trade = table("TRADE").col(number("TRADE_ID", 1, 12, 0, false)).cons(pk("PK_TRADE", false, "TRADE_ID")).build();
        TableDef kw = table("KW").col(number("TRADE_ID", 1, 12, 0, false), varchar("NAME", 2, 30, false))
                .cons(pk("PK_KW", false, "TRADE_ID", "NAME")).build();
        KeyMap m = new KeyMap(Map.of("TRADE", trade, "KW", kw));
        assertFalse(m.hasAny("S", "TRADE"));
        for (int i = 1; i <= 1000; i++) m.put("S", "TRADE", String.valueOf(i), Map.of("TRADE_ID", new BigDecimal(i)));
        m.put("S", "TRADE", "7", Map.of("TRADE_ID", new BigDecimal(5007))); // re-keyed
        m.put("S", "TRADE", "8", Map.of());                                  // waits for a sequence value
        assertEquals(2, m.changedCount(), "only the changed keys are stored as maps");
        assertEquals(Map.of("TRADE_ID", new BigDecimal(3)), m.get("S", "TRADE", "3"));
        assertEquals(Map.of("TRADE_ID", new BigDecimal(5007)), m.get("S", "TRADE", "7"));
        assertEquals(Map.of(), m.get("S", "TRADE", "8"));
        assertNull(m.get("S", "TRADE", "1001"));
        assertNull(m.get("OTHER", "TRADE", "3"));
        assertTrue(m.hasAny("S", "TRADE") && !m.hasAny("S", "KW"));
        m.put("S", "TRADE", "7", Map.of("TRADE_ID", new BigDecimal(7)));    // back to its own key: identity again
        assertEquals(1, m.changedCount());

        Map<String, Object> composite = new LinkedHashMap<>();
        composite.put("TRADE_ID", new BigDecimal(9));
        composite.put("NAME", "Desk");
        m.put("S", "KW", "9\u0001Desk", composite);
        assertEquals(composite, m.get("S", "KW", "9\u0001Desk"));
        assertEquals(1, m.changedCount(), "a composite key that did not change is not stored as a map");
    }
}
