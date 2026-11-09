package com.dbmerge.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dbmerge.data.HashCompare.BucketAgg;
import com.dbmerge.data.HashCompare.Cursor;
import com.dbmerge.data.HashCompare.KeyRow;
import com.dbmerge.data.HashCompare.PkRow;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class HashCompareTest {

    /** In-memory stand-in for the database: same contract as OracleHashSource, hashing in Java. */
    static final class FakeSource implements HashCompare.Source {
        final Map<String, List<Map<String, String>>> rows;
        final List<String> key, pk, compared;
        final List<String> drilled = java.util.Collections.synchronizedList(new ArrayList<>());
        final java.util.concurrent.atomic.AtomicInteger fetched = new java.util.concurrent.atomic.AtomicInteger();
        // the comparison queries the databases in parallel, so these are updated from several threads

        FakeSource(Map<String, List<Map<String, String>>> rows, List<String> key, List<String> pk, List<String> compared) {
            this.rows = rows;
            this.key = key;
            this.pk = pk;
            this.compared = compared;
        }

        static String md5(String s) {
            try {
                return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        String kh(Map<String, String> r) { return md5("k" + RowMatcher.keyOf(r, key)); }
        String ph(Map<String, String> r) { return pk.isEmpty() ? "" : md5("p" + RowMatcher.keyOf(r, pk)); }
        String ch(Map<String, String> r) { return md5("c" + RowMatcher.keyOf(r, compared)); }

        static int bucket(String hash, int buckets) { return Math.floorMod(hash.hashCode(), buckets); }

        static BigDecimal part(String hex) { return new BigDecimal(new BigInteger(hex.substring(0, 15), 16)); }

        private List<BucketAgg> aggs(String db, int buckets, boolean byPk) {
            Map<Integer, long[]> counts = new TreeMap<>();
            Map<Integer, Set<String>> distinct = new TreeMap<>();
            Map<Integer, BigDecimal> sums = new TreeMap<>();
            for (Map<String, String> r : rows.getOrDefault(db, List.of())) {
                String h = byPk ? ph(r) : kh(r);
                int b = bucket(h, buckets);
                counts.computeIfAbsent(b, x -> new long[1])[0]++;
                distinct.computeIfAbsent(b, x -> new TreeSet<>()).add(h);
                String rh = byPk ? md5(ph(r) + kh(r)) : md5(kh(r) + ph(r) + ch(r));
                sums.merge(b, part(rh), BigDecimal::add);
            }
            List<BucketAgg> out = new ArrayList<>();
            for (int b : counts.keySet()) out.add(new BucketAgg(b, counts.get(b)[0], distinct.get(b).size(), sums.get(b)));
            return out;
        }

        @Override
        public List<BucketAgg> keyBuckets(String db, int buckets) { return aggs(db, buckets, false); }

        @Override
        public List<BucketAgg> pkBuckets(String db, int buckets) { return aggs(db, buckets, true); }

        private static <T> Cursor<T> of(List<T> list) {
            Iterator<T> it = list.iterator();
            return new Cursor<>() {
                @Override public T next() { return it.hasNext() ? it.next() : null; }
                @Override public void close() { }
            };
        }

        @Override
        public Cursor<KeyRow> keyRows(String db, int buckets, List<Integer> which) {
            drilled.add(db + which);
            List<KeyRow> out = new ArrayList<>();
            for (Map<String, String> r : rows.getOrDefault(db, List.of())) {
                int b = bucket(kh(r), buckets);
                if (which.contains(b)) {
                    out.add(new KeyRow(b, kh(r), RowMatcher.display(RowMatcher.keyOf(r, key), key),
                            pk.isEmpty() ? null : RowMatcher.display(RowMatcher.keyOf(r, pk), pk), ch(r)));
                }
            }
            out.sort(Comparator.comparingInt(KeyRow::bucket));
            return of(out);
        }

        @Override
        public Cursor<PkRow> pkRows(String db, int buckets, List<Integer> which) {
            List<PkRow> out = new ArrayList<>();
            for (Map<String, String> r : rows.getOrDefault(db, List.of())) {
                int b = bucket(ph(r), buckets);
                if (which.contains(b)) {
                    out.add(new PkRow(b, ph(r), RowMatcher.display(RowMatcher.keyOf(r, pk), pk), kh(r),
                            RowMatcher.display(RowMatcher.keyOf(r, key), key)));
                }
            }
            out.sort(Comparator.comparingInt(PkRow::bucket));
            return of(out);
        }

        @Override
        public Map<String, Map<String, String>> rows(String db, Map<String, List<Object>> keys) {
            Collection<String> keyHashes = keys.keySet();
            fetched.addAndGet(keyHashes.size());
            Map<String, Map<String, String>> out = new LinkedHashMap<>();
            for (Map<String, String> r : rows.getOrDefault(db, List.of())) if (keyHashes.contains(kh(r))) out.putIfAbsent(kh(r), r);
            return out;
        }
    }

    private static Map<String, String> row(String id, String code, String name, String amount) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ID", id);
        m.put("CODE", code);
        m.put("NAME", name);
        m.put("AMOUNT", amount);
        return m;
    }

    /** Three databases with overlapping, conflicting, missing and NULL-valued rows; keys unique per database. */
    private static Map<String, List<Map<String, String>>> randomData(long seed) {
        Random rnd = new Random(seed);
        Map<String, List<Map<String, String>>> db = new LinkedHashMap<>();
        for (String d : List.of("A", "B", "C")) db.put(d, new ArrayList<>());
        for (int i = 0; i < 400; i++) {
            String code = "C" + i;
            for (String d : List.of("A", "B", "C")) {
                if (rnd.nextInt(5) == 0) continue;                                    // missing in this db
                String id = String.valueOf(rnd.nextInt(10) == 0 ? i + 1000 * (d.charAt(0) - 'A') : i); // PK divergence
                String name = rnd.nextInt(8) == 0 ? null : "n" + (rnd.nextInt(12) == 0 ? d : "") + i; // conflicts, NULLs
                db.get(d).add(row(id, code, name, String.valueOf(i % 7)));
            }
        }
        // Same PK, different business key: collisions.
        db.get("A").add(row("5000", "X1", "a", "1"));
        db.get("B").add(row("5000", "X2", "b", "1"));
        return db;
    }

    private static void assertSameAsInMemory(Map<String, List<Map<String, String>>> data, List<String> key, List<String> pk,
                                             int buckets) throws Exception {
        boolean keyIsPk = key.equals(pk);
        List<String> compared = new ArrayList<>(List.of("ID", "CODE", "NAME", "AMOUNT"));
        if (!keyIsPk) compared.removeAll(pk);
        List<String> dbs = List.of("A", "B", "C");
        RowMatcher.Output mem = RowMatcher.match(new RowMatcher.Input(dbs, key, pk, compared, data, 10_000));
        FakeSource src = new FakeSource(data, key, keyIsPk ? List.of() : pk, compared);
        RowMatcher.Output hash = HashCompare.run(new HashCompare.Input(dbs, compared, keyIsPk, !pk.isEmpty(), buckets, 10_000), src);

        String ctx = "key=" + key + " buckets=" + buckets;
        assertEquals(mem.onlyInCounts(), hash.onlyInCounts(), ctx);
        assertEquals(mem.identicalCount(), hash.identicalCount(), ctx);
        assertEquals(mem.conflictCount(), hash.conflictCount(), ctx);
        assertEquals(mem.pkCollisionCount(), hash.pkCollisionCount(), ctx);
        assertEquals(mem.pkDivergenceCount(), hash.pkDivergenceCount(), ctx);
        assertTrue(mem.conflictCount() > 0 && mem.onlyInCounts().values().stream().anyMatch(n -> n > 0), "data exercises the paths");

        // Same conflicting keys with the same differing columns, and the full rows attached.
        Map<String, List<String>> m = mem.conflicts().stream().collect(Collectors.toMap(DataModel.Conflict::key, DataModel.Conflict::differingColumns));
        Map<String, List<String>> h = hash.conflicts().stream().collect(Collectors.toMap(DataModel.Conflict::key, DataModel.Conflict::differingColumns));
        assertEquals(m, h, ctx);
        for (DataModel.OnlyRow o : hash.onlyRows()) assertTrue(!o.row().isEmpty(), "sample rows are fetched in full");
        assertEquals(mem.pkCollisions().stream().map(DataModel.PkCollision::pk).collect(Collectors.toSet()),
                hash.pkCollisions().stream().map(DataModel.PkCollision::pk).collect(Collectors.toSet()), ctx);
        assertEquals(mem.pkDivergences().stream().map(DataModel.PkDivergence::key).collect(Collectors.toSet()),
                hash.pkDivergences().stream().map(DataModel.PkDivergence::key).collect(Collectors.toSet()), ctx);
    }

    @Test
    void matchesTheInMemoryComparisonOnAPrimaryKey() throws Exception {
        for (int buckets : new int[] {1, 7, 64, 5000}) {
            assertSameAsInMemory(randomData(1), List.of("ID"), List.of("ID"), buckets);
        }
    }

    @Test
    void matchesTheInMemoryComparisonOnABusinessKey() throws Exception {
        for (int buckets : new int[] {1, 7, 64, 5000}) {
            for (long seed : new long[] {2, 3}) {
                assertSameAsInMemory(randomData(seed), List.of("CODE"), List.of("ID"), buckets);
            }
        }
    }

    @Test
    void identicalBucketsAreNeverDrilledIntoOrFetched() throws Exception {
        Map<String, List<Map<String, String>>> data = new LinkedHashMap<>();
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < 500; i++) rows.add(row(String.valueOf(i), "C" + i, "n" + i, "1"));
        data.put("A", rows);
        data.put("B", new ArrayList<>(rows));
        List<String> all = List.of("ID", "CODE", "NAME", "AMOUNT");
        FakeSource src = new FakeSource(data, List.of("ID"), List.of(), all);
        RowMatcher.Output o = HashCompare.run(new HashCompare.Input(List.of("A", "B"), all, true, true, 20, 100), src);
        assertEquals(500, o.identicalCount());
        assertTrue(src.drilled.isEmpty(), "nothing to drill into: " + src.drilled);
        assertEquals(0, src.fetched.get());

        // One changed row: only its bucket is read, and only that row is fetched in full.
        data.get("B").set(42, row("42", "C42", "changed", "1"));
        src = new FakeSource(data, List.of("ID"), List.of(), all);
        o = HashCompare.run(new HashCompare.Input(List.of("A", "B"), all, true, true, 20, 100), src);
        assertEquals(499, o.identicalCount());
        assertEquals(1, o.conflictCount());
        assertEquals(List.of("NAME"), o.conflicts().get(0).differingColumns());
        assertEquals(2, src.drilled.size(), "one bucket list per database");
        assertTrue(src.drilled.stream().sorted().toList().get(0).matches("A\\[\\d+]"), src.drilled.toString());
        assertTrue(src.drilled.stream().anyMatch(d -> d.startsWith("B")), src.drilled.toString());
        assertEquals(2, src.fetched.get(), "the conflicting row from each database");
    }

    @Test
    void samplesAreCappedButCountsStayExact() throws Exception {
        Map<String, List<Map<String, String>>> data = new LinkedHashMap<>();
        List<Map<String, String>> a = new ArrayList<>();
        for (int i = 0; i < 300; i++) a.add(row(String.valueOf(i), "C" + i, "n" + i, "1"));
        data.put("A", a);
        data.put("B", new ArrayList<>());
        List<String> all = List.of("ID", "CODE", "NAME", "AMOUNT");
        FakeSource src = new FakeSource(data, List.of("ID"), List.of(), all);
        RowMatcher.Output o = HashCompare.run(new HashCompare.Input(List.of("A", "B"), all, true, true, 9, 25), src);
        assertEquals(300L, o.onlyInCounts().get("A"));
        assertEquals(25, o.onlyRows().size());
        assertEquals(25, src.fetched.get());
    }

    @Test
    void duplicateBusinessKeysAreReported() throws Exception {
        Map<String, List<Map<String, String>>> data = new LinkedHashMap<>();
        data.put("A", List.of(row("1", "SAME", "x", "1"), row("2", "SAME", "x", "1")));
        data.put("B", List.of(row("1", "SAME", "x", "1")));
        FakeSource src = new FakeSource(data, List.of("CODE"), List.of("ID"), List.of("CODE", "NAME", "AMOUNT"));
        RowMatcher.Output o = HashCompare.run(new HashCompare.Input(List.of("A", "B"), List.of("CODE", "NAME", "AMOUNT"),
                false, true, 4, 10), src);
        assertTrue(o.warnings().stream().anyMatch(w -> w.startsWith("A: the chosen business key is not unique")), o.warnings().toString());
    }

    @Test
    void bucketCountFollowsTableSize() {
        assertEquals(1, HashCompare.bucketsFor(0, 10_000));
        assertEquals(1, HashCompare.bucketsFor(10_000, 10_000));
        assertEquals(2, HashCompare.bucketsFor(10_001, 10_000));
        assertEquals(1_000, HashCompare.bucketsFor(10_000_000, 10_000));
        assertEquals(1_000_000, HashCompare.bucketsFor(Long.MAX_VALUE, 10_000));
    }

    @Test
    void databasesAreQueriedAtTheSameTime() throws Exception {
        java.util.concurrent.CountDownLatch both = new java.util.concurrent.CountDownLatch(2);
        Map<String, Integer> out = HashCompare.perDb(List.of("A", "B"), db -> {
            both.countDown();
            // each call waits for the other one: only possible when they run in parallel
            try {
                if (!both.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new java.sql.SQLException("ran one after the other");
            } catch (InterruptedException ie) {
                throw new java.sql.SQLException(ie);
            }
            return db.length();
        });
        assertEquals(List.of("A", "B"), List.copyOf(out.keySet()), "results keep the database order");
        java.sql.SQLException e = org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class,
                () -> HashCompare.perDb(List.of("A", "B"), db -> { if (db.equals("B")) throw new java.sql.SQLException("B down"); return 1; }));
        assertEquals("B down", e.getMessage());
    }
}
