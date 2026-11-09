package com.dbmerge.merge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The parent hierarchy of a large self-referencing table, kept as compact arrays: one node per source row (source,
 * key), its parent row in the same source, and the row that must be written before it because it has the same
 * business key in an earlier source. From that it works out which rows are orphans (their parent chain ends in a
 * missing or orphaned row) and each row's depth, so the plan can be written parents first. A cycle is reported
 * instead of a depth. About 40 bytes per row for numeric keys.
 */
final class SelfRefGraph {

    private final Map<String, KeyIndex> bySource = new HashMap<>();
    private int[][] parents = new int[1024][];  // node indices of the parent rows in this source (none: empty)
    private int[] after = new int[1024];       // node written before this one (same business key, earlier source), -1
    private boolean[] brokenParent = new boolean[0]; // a parent key that is neither a row of this source nor in the master
    private final BitSet orphanOther = new BitSet(); // orphan because of another foreign key
    private final List<String[]> parentKeys = new ArrayList<>(); // parent keys per node, resolved in resolve()
    private final List<String> sources = new ArrayList<>();
    private static final int[] NONE = new int[0];
    private int size;
    private int[] depth;
    private BitSet orphan;

    /** Map from a source's keys to node indices: primitive longs for whole-number keys, strings otherwise. */
    private static final class KeyIndex {
        final LongIntMap longs = new LongIntMap();
        final Map<String, Integer> strings = new HashMap<>();

        void put(String key, int node) {
            Long l = KeySet.asLong(key);
            if (l != null) longs.put(l, node); else strings.put(key, node);
        }

        int get(String key) {
            Long l = KeySet.asLong(key);
            if (l != null) return longs.get(l);
            Integer i = strings.get(key);
            return i == null ? -1 : i;
        }
    }

    /** Adds a source row; returns its node. {@code parentKeys}: the keys it references in this table (may be empty). */
    int add(String source, String key, List<String> parentKeyList, int afterNode, boolean orphanByOtherKey) {
        int n = size++;
        if (n == after.length) {
            parents = Arrays.copyOf(parents, n * 2);
            after = Arrays.copyOf(after, n * 2);
        }
        bySource.computeIfAbsent(source, k -> new KeyIndex()).put(key, n);
        parentKeys.add(parentKeyList.isEmpty() ? null : parentKeyList.toArray(String[]::new));
        sources.add(parentKeyList.isEmpty() ? null : source);
        after[n] = afterNode;
        if (orphanByOtherKey) orphanOther.set(n);
        return n;
    }

    int node(String source, String key) {
        KeyIndex k = bySource.get(source);
        return k == null ? -1 : k.get(key);
    }

    int size() { return size; }

    /**
     * Resolves parents and computes orphans and depths. {@code inMaster} says whether a parent key that is not a row
     * of the source exists in the master (then the reference is valid as it stands).
     *
     * @return the nodes of one cycle when there is one (then nothing else is valid), or an empty list
     */
    List<Integer> resolve(java.util.function.Predicate<String> inMaster) {
        brokenParent = new boolean[size];
        for (int n = 0; n < size; n++) {
            String[] pk = parentKeys.get(n);
            if (pk == null) { parents[n] = NONE; continue; }
            int[] found = new int[pk.length];
            int k = 0;
            for (String key : pk) {
                int p = node(sources.get(n), key);
                if (p >= 0) found[k++] = p;
                else if (!inMaster.test(key)) brokenParent[n] = true;
            }
            parents[n] = k == found.length ? found : Arrays.copyOf(found, k);
        }
        parentKeys.clear();
        sources.clear();
        depth = new int[size];
        orphan = new BitSet(size);
        byte[] state = new byte[size]; // 0 new, 1 on the stack, 2 done
        int[] stack = new int[16];
        for (int start = 0; start < size; start++) {
            if (state[start] == 2) continue;
            int top = 0;
            stack[top++] = start;
            while (top > 0) {
                int n = stack[top - 1];
                if (state[n] == 0) {
                    state[n] = 1;
                    for (int i = -1; i < parents[n].length; i++) {
                        int dep = i < 0 ? after[n] : parents[n][i];
                        if (dep < 0) continue;
                        if (state[dep] == 1) return cycleFrom(dep, stack, top);
                        if (state[dep] == 0) {
                            if (top == stack.length) stack = Arrays.copyOf(stack, top * 2);
                            stack[top++] = dep;
                        }
                    }
                    continue;
                }
                top--;
                if (state[n] == 2) continue;
                state[n] = 2;
                boolean o = orphanOther.get(n) || brokenParent[n];
                int d = after[n] >= 0 ? depth[after[n]] : 0;
                for (int p : parents[n]) {
                    o |= orphan.get(p);
                    d = Math.max(d, depth[p] + 1);
                }
                if (o) orphan.set(n);
                depth[n] = d;
            }
        }
        return List.of();
    }

    private static List<Integer> cycleFrom(int dep, int[] stack, int top) {
        List<Integer> cycle = new ArrayList<>();
        for (int i = 0; i < top; i++) if (stack[i] == dep || !cycle.isEmpty()) cycle.add(stack[i]);
        return cycle;
    }

    boolean orphan(int node) { return orphan.get(node); }

    int depth(int node) { return depth[node]; }

    /** Open-addressing long to int map; -1 when absent. */
    static final class LongIntMap {
        private static final long EMPTY = Long.MIN_VALUE;
        private long[] keys = new long[16];
        private int[] values = new int[16];
        private static final int[] NONE = new int[0];
    private int size;
        private int emptyKeyValue = -1;

        LongIntMap() { Arrays.fill(keys, EMPTY); }

        void put(long k, int v) {
            if (k == EMPTY) { emptyKeyValue = v; return; }
            if ((size + 1) * 2 > keys.length) grow();
            int i = slot(k);
            while (keys[i] != EMPTY && keys[i] != k) i = (i + 1) & (keys.length - 1);
            if (keys[i] == EMPTY) size++;
            keys[i] = k;
            values[i] = v;
        }

        int get(long k) {
            if (k == EMPTY) return emptyKeyValue;
            int i = slot(k);
            while (keys[i] != EMPTY) {
                if (keys[i] == k) return values[i];
                i = (i + 1) & (keys.length - 1);
            }
            return -1;
        }

        private int slot(long k) {
            long h = k * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32)) & (keys.length - 1);
        }

        private void grow() {
            long[] ok = keys;
            int[] ov = values;
            keys = new long[ok.length * 2];
            values = new int[ok.length * 2];
            Arrays.fill(keys, EMPTY);
            size = 0;
            for (int i = 0; i < ok.length; i++) if (ok[i] != EMPTY) put(ok[i], ov[i]);
        }
    }
}
