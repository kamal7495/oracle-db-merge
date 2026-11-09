package com.dbmerge.data;

import com.dbmerge.data.DataModel.Conflict;
import com.dbmerge.data.DataModel.IdenticalRow;
import com.dbmerge.data.DataModel.OnlyRow;
import com.dbmerge.data.DataModel.PkCollision;
import com.dbmerge.data.DataModel.PkDivergence;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Data comparison for tables too large to load: the databases compute hashes, and only what differs is fetched.
 * No JDBC here (see {@link OracleHashSource}), so the logic is unit-testable against a fake source.
 *
 * <ol>
 *   <li>Every row gets a key hash (business key) and a data hash (compared columns). Rows are spread over
 *       {@code buckets} buckets by key hash, so a given key lands in the same bucket in every database.</li>
 *   <li>Each database returns one aggregate per bucket: rows, distinct keys and the sum of a row hash that covers
 *       key, primary key and data. A bucket with the same aggregate everywhere holds the same rows everywhere; its
 *       keys are all identical and are counted without being fetched.</li>
 *   <li>The other buckets are streamed from every database at once, ordered by bucket, and classified one bucket
 *       at a time (key hash, data hash and the key/PK values only), so memory is one bucket per database.</li>
 *   <li>Only the sample rows shown in the report (at most {@code sampleLimit} per category) are fetched in full.</li>
 * </ol>
 * When the business key is not the primary key, a second pass buckets rows by primary-key hash to find PK
 * collisions (same PK, different business key) the same way.
 *
 * <p>Results have the same shape and exact counts as {@link RowMatcher}. Two differences: identical rows in
 * matching buckets are counted but not listed, and for a non-unique business key the counts follow the distinct keys.
 */
public final class HashCompare {

    private HashCompare() { }

    /** Aggregate of one bucket in one database. */
    public record BucketAgg(int bucket, long rows, long distinctKeys, BigDecimal hashSum) {
        boolean sameAs(BucketAgg o) {
            return o != null && rows == o.rows && distinctKeys == o.distinctKeys && hashSum.compareTo(o.hashSum) == 0;
        }
    }

    /** One row of a differing bucket: hashes plus the display form of its business key and primary key. */
    public record KeyRow(int bucket, String keyHash, String keyDisplay, String pkDisplay, String dataHash, List<Object> rawKey) {
        public KeyRow(int bucket, String keyHash, String keyDisplay, String pkDisplay, String dataHash) {
            this(bucket, keyHash, keyDisplay, pkDisplay, dataHash, null);
        }
    }

    /** One row of a differing primary-key bucket. */
    public record PkRow(int bucket, String pkHash, String pkDisplay, String keyHash, String keyDisplay, List<Object> rawKey) {
        public PkRow(int bucket, String pkHash, String pkDisplay, String keyHash, String keyDisplay) {
            this(bucket, pkHash, pkDisplay, keyHash, keyDisplay, null);
        }
    }

    /** Forward-only cursor; {@link #next()} returns null at the end. */
    public interface Cursor<T> extends AutoCloseable {
        T next() throws SQLException;

        @Override
        void close() throws SQLException;
    }

    /** What each database must answer. Bucket lists are ascending; cursors must return rows ordered by bucket. */
    public interface Source {
        List<BucketAgg> keyBuckets(String db, int buckets) throws SQLException;

        Cursor<KeyRow> keyRows(String db, int buckets, List<Integer> which) throws SQLException;

        List<BucketAgg> pkBuckets(String db, int buckets) throws SQLException;

        Cursor<PkRow> pkRows(String db, int buckets, List<Integer> which) throws SQLException;

        /**
         * Full rows (all compared columns) for the given keys: key hash -> row. Each key hash comes with the row's raw
         * business-key values as read by {@link #keyRows}/{@link #pkRows} (null when unknown), so a source can look
         * rows up by key instead of hashing the whole table.
         */
        Map<String, Map<String, String>> rows(String db, Map<String, List<Object>> keys) throws SQLException;
    }

    public record Input(List<String> databases, List<String> comparedColumns, boolean keyIsPk, boolean hasPk,
                        int buckets, int sampleLimit) { }

    /** Bucket count for a table: about {@code rowsPerBucket} rows each. */
    public static int bucketsFor(long maxRows, int rowsPerBucket) {
        int per = Math.max(1, rowsPerBucket);
        long b = maxRows / per + (maxRows % per == 0 ? 0 : 1); // ceiling without overflow
        return (int) Math.max(1, Math.min(b, 1_000_000));
    }

    public static RowMatcher.Output run(Input in, Source src) throws SQLException {
        List<String> dbs = in.databases();
        List<String> warnings = new ArrayList<>();
        Map<String, Long> onlyCounts = new LinkedHashMap<>();
        for (String db : dbs) onlyCounts.put(db, 0L);

        // ---- pass 1: business key
        Map<String, Map<Integer, BucketAgg>> aggs = new LinkedHashMap<>();
        perDb(dbs, db -> index(src.keyBuckets(db, in.buckets()))).forEach(aggs::put);
        long identical = 0;
        Map<String, Long> duplicates = new LinkedHashMap<>();
        List<Integer> dirty = new ArrayList<>();
        for (int b : allBuckets(aggs)) {
            if (clean(aggs, b)) identical += aggs.get(dbs.get(0)).get(b).distinctKeys();
            else dirty.add(b);
        }
        for (String db : dbs) {
            long d = aggs.get(db).values().stream().mapToLong(a -> a.rows() - a.distinctKeys()).sum();
            if (d > 0) duplicates.put(db, d);
        }

        long conflicts = 0, divergences = 0;
        List<IdenticalRow> identicalRows = new ArrayList<>();
        List<PkDivergence> divergenceRows = new ArrayList<>();
        List<KeyRow> onlySamples = new ArrayList<>();
        List<String> onlySampleDbs = new ArrayList<>();
        List<Map<String, KeyRow>> conflictSamples = new ArrayList<>();
        if (!dirty.isEmpty()) {
            Map<String, Peeking<KeyRow>> cursors = new LinkedHashMap<>();
            try {
                openAll(dbs, db -> new Peeking<>(src.keyRows(db, in.buckets(), dirty), KeyRow::bucket), cursors);
                for (int b : dirty) {
                    Map<String, Map<String, KeyRow>> byKey = new LinkedHashMap<>();
                    for (String db : dbs) {
                        for (KeyRow r : cursors.get(db).take(b)) {
                            byKey.computeIfAbsent(r.keyHash(), k -> new LinkedHashMap<>()).putIfAbsent(db, r);
                        }
                    }
                    for (Map<String, KeyRow> perDb : byKey.values()) {
                        KeyRow any = perDb.values().iterator().next();
                        if (perDb.size() == 1) {
                            String db = perDb.keySet().iterator().next();
                            onlyCounts.merge(db, 1L, Long::sum);
                            if (onlySamples.size() < in.sampleLimit()) {
                                onlySamples.add(any);
                                onlySampleDbs.add(db);
                            }
                            continue;
                        }
                        Set<String> data = new LinkedHashSet<>();
                        for (KeyRow r : perDb.values()) data.add(r.dataHash());
                        if (data.size() == 1) {
                            identical++;
                            if (identicalRows.size() < in.sampleLimit()) {
                                identicalRows.add(new IdenticalRow(any.keyDisplay(), new ArrayList<>(perDb.keySet())));
                            }
                        } else {
                            conflicts++;
                            if (conflictSamples.size() < in.sampleLimit()) conflictSamples.add(perDb);
                        }
                        if (!in.keyIsPk() && in.hasPk()) {
                            Map<String, String> pkByDb = new LinkedHashMap<>();
                            for (var e : perDb.entrySet()) pkByDb.put(e.getKey(), e.getValue().pkDisplay());
                            if (new LinkedHashSet<>(pkByDb.values()).size() > 1) {
                                divergences++;
                                if (divergenceRows.size() < in.sampleLimit()) {
                                    divergenceRows.add(new PkDivergence(any.keyDisplay(), pkByDb));
                                }
                            }
                        }
                    }
                }
            } finally {
                closeAll(cursors.values());
            }
        }

        // ---- pass 2: primary key (only when the business key is something else)
        long collisions = 0;
        List<Map<String, PkRow>> collisionSamples = new ArrayList<>();
        if (!in.keyIsPk() && in.hasPk()) {
            Map<String, Map<Integer, BucketAgg>> pkAggs = new LinkedHashMap<>();
            perDb(dbs, db -> index(src.pkBuckets(db, in.buckets()))).forEach(pkAggs::put);
            List<Integer> pkDirty = new ArrayList<>();
            for (int b : allBuckets(pkAggs)) if (!clean(pkAggs, b)) pkDirty.add(b);
            if (!pkDirty.isEmpty()) {
                Map<String, Peeking<PkRow>> cursors = new LinkedHashMap<>();
                try {
                    openAll(dbs, db -> new Peeking<>(src.pkRows(db, in.buckets(), pkDirty), PkRow::bucket), cursors);
                    for (int b : pkDirty) {
                        Map<String, Map<String, PkRow>> byPk = new LinkedHashMap<>();
                        for (String db : dbs) {
                            for (PkRow r : cursors.get(db).take(b)) {
                                byPk.computeIfAbsent(r.pkHash(), k -> new LinkedHashMap<>()).putIfAbsent(db, r);
                            }
                        }
                        for (Map<String, PkRow> perDb : byPk.values()) {
                            if (perDb.size() < 2) continue;
                            Set<String> keys = new LinkedHashSet<>();
                            for (PkRow r : perDb.values()) keys.add(r.keyDisplay());
                            if (keys.size() > 1) {
                                collisions++;
                                if (collisionSamples.size() < in.sampleLimit()) collisionSamples.add(perDb);
                            }
                        }
                    }
                } finally {
                    closeAll(cursors.values());
                }
            }
        }

        // ---- full rows for the samples only
        Map<String, Map<String, List<Object>>> wanted = new LinkedHashMap<>();
        for (String db : dbs) wanted.put(db, new TreeMap<>());
        for (int i = 0; i < onlySamples.size(); i++) {
            KeyRow r = onlySamples.get(i);
            wanted.get(onlySampleDbs.get(i)).put(r.keyHash(), r.rawKey());
        }
        for (Map<String, KeyRow> c : conflictSamples) c.forEach((db, r) -> wanted.get(db).put(r.keyHash(), r.rawKey()));
        for (Map<String, PkRow> c : collisionSamples) c.forEach((db, r) -> wanted.get(db).put(r.keyHash(), r.rawKey()));
        Map<String, Map<String, Map<String, String>>> full = new HashMap<>(
                perDb(dbs, db -> wanted.get(db).isEmpty() ? Map.of() : src.rows(db, wanted.get(db))));

        List<OnlyRow> onlyRows = new ArrayList<>();
        for (int i = 0; i < onlySamples.size(); i++) {
            String db = onlySampleDbs.get(i);
            KeyRow r = onlySamples.get(i);
            onlyRows.add(new OnlyRow(db, r.keyDisplay(), full.get(db).getOrDefault(r.keyHash(), Map.of())));
        }
        List<Conflict> conflictRows = new ArrayList<>();
        for (Map<String, KeyRow> c : conflictSamples) {
            Map<String, Map<String, String>> rowsByDb = new LinkedHashMap<>();
            c.forEach((db, r) -> rowsByDb.put(db, full.get(db).getOrDefault(r.keyHash(), Map.of())));
            List<String> differing = new ArrayList<>();
            for (String col : in.comparedColumns()) {
                Set<String> distinct = new LinkedHashSet<>();
                for (var row : rowsByDb.values()) distinct.add(Objects.toString(row.get(col), "\u0000NULL"));
                if (distinct.size() > 1) differing.add(col);
            }
            // Equal as read by JDBC but not as hashed (e.g. float formatting, trailing CHAR blanks across sizes).
            if (differing.isEmpty()) differing.add("(differs only in the hashed form)");
            conflictRows.add(new Conflict(c.values().iterator().next().keyDisplay(), new ArrayList<>(c.keySet()), rowsByDb, differing));
        }
        List<PkCollision> collisionRows = new ArrayList<>();
        for (Map<String, PkRow> c : collisionSamples) {
            Map<String, String> keyByDb = new LinkedHashMap<>();
            Map<String, Map<String, String>> rowsByDb = new LinkedHashMap<>();
            c.forEach((db, r) -> {
                keyByDb.put(db, r.keyDisplay());
                rowsByDb.put(db, full.get(db).getOrDefault(r.keyHash(), Map.of()));
            });
            collisionRows.add(new PkCollision(c.values().iterator().next().pkDisplay(), keyByDb, rowsByDb));
        }

        duplicates.forEach((db, n) -> warnings.add(db + ": the chosen business key is not unique - " + n
                + " row(s) repeat a key value that another row already has (counts use the distinct keys). "
                + "Choose a column set that identifies a row uniquely."));
        if (in.keyIsPk() && conflicts > 0) {
            warnings.add("The primary key is the business key and " + conflicts + " key value(s) exist in several databases with different data. "
                    + "Either these are the same record edited differently, or unrelated records that happen to share an id. "
                    + "If ids were assigned independently per database, choose a natural business key (e.g. e-mail, SKU, code) and re-run.");
        }
        return new RowMatcher.Output(onlyCounts, identical, conflicts, collisions, divergences,
                onlyRows, identicalRows, conflictRows, collisionRows, divergenceRows, warnings);
    }

    @FunctionalInterface
    interface PerDb<T> {
        T apply(String db) throws SQLException;
    }

    /**
     * Runs {@code f} for every database at the same time. Each database has its own connection, so the queries run
     * in parallel on the servers while each still reads one consistent snapshot. Results keep the database order.
     */
    static <T> Map<String, T> perDb(List<String> dbs, PerDb<T> f) throws SQLException {
        Map<String, T> out = new LinkedHashMap<>();
        if (dbs.size() == 1) {
            out.put(dbs.get(0), f.apply(dbs.get(0)));
            return out;
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(dbs.size(), r -> {
            Thread t = new Thread(r, "dbmerge-hash");
            t.setDaemon(true);
            return t;
        });
        try {
            Map<String, java.util.concurrent.Future<T>> futures = new LinkedHashMap<>();
            for (String db : dbs) futures.put(db, pool.submit(() -> f.apply(db)));
            SQLException first = null;
            for (var e : futures.entrySet()) {
                try {
                    out.put(e.getKey(), e.getValue().get());
                } catch (java.util.concurrent.ExecutionException ex) {
                    Throwable c = ex.getCause();
                    if (c instanceof SQLException se) { if (first == null) first = se; }
                    else if (c instanceof RuntimeException re) throw re;
                    else throw new IllegalStateException(c);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("Interrupted while comparing", ex);
                }
            }
            if (first != null) throw first;
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Opens one cursor per database in parallel (each query sorts before its first row); closes them all on failure. */
    private static <T> void openAll(List<String> dbs, PerDb<Peeking<T>> open, Map<String, Peeking<T>> into) throws SQLException {
        Map<String, Peeking<T>> opened = new LinkedHashMap<>();
        try {
            // keep successfully opened cursors even if another database fails, so the caller closes them
            perDb(dbs, db -> {
                Peeking<T> p = open.apply(db);
                synchronized (opened) { opened.put(db, p); }
                return p;
            });
        } finally {
            for (String db : dbs) if (opened.containsKey(db)) into.put(db, opened.get(db));
        }
    }

    private static Map<Integer, BucketAgg> index(List<BucketAgg> list) {
        Map<Integer, BucketAgg> m = new HashMap<>();
        for (BucketAgg a : list) m.put(a.bucket(), a);
        return m;
    }

    private static TreeSet<Integer> allBuckets(Map<String, Map<Integer, BucketAgg>> aggs) {
        TreeSet<Integer> all = new TreeSet<>();
        for (Map<Integer, BucketAgg> m : aggs.values()) all.addAll(m.keySet());
        return all;
    }

    /** Same aggregate in every database (a bucket missing anywhere is not clean). */
    private static boolean clean(Map<String, Map<Integer, BucketAgg>> aggs, int b) {
        BucketAgg first = null;
        for (Map<Integer, BucketAgg> m : aggs.values()) {
            BucketAgg a = m.get(b);
            if (a == null) return false;
            if (first == null) first = a;
            else if (!first.sameAs(a)) return false;
        }
        return true;
    }

    private static void closeAll(Collection<? extends Peeking<?>> cursors) throws SQLException {
        SQLException first = null;
        for (Peeking<?> p : cursors) {
            try {
                p.cursor.close();
            } catch (SQLException e) {
                if (first == null) first = e;
            }
        }
        if (first != null) throw first;
    }

    /** Reads a bucket-ordered cursor one bucket at a time. */
    private static final class Peeking<T> {
        private final Cursor<T> cursor;
        private final java.util.function.ToIntFunction<T> bucketOf;
        private T head;
        private boolean started;

        Peeking(Cursor<T> cursor, java.util.function.ToIntFunction<T> bucketOf) {
            this.cursor = cursor;
            this.bucketOf = bucketOf;
        }

        /** All rows of bucket {@code b}; rows of lower buckets (not asked for) are skipped. */
        List<T> take(int b) throws SQLException {
            if (!started) {
                head = cursor.next();
                started = true;
            }
            while (head != null && bucketOf.applyAsInt(head) < b) head = cursor.next();
            List<T> out = new ArrayList<>();
            while (head != null && bucketOf.applyAsInt(head) == b) {
                out.add(head);
                head = cursor.next();
            }
            return out;
        }
    }
}
