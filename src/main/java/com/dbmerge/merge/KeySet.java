package com.dbmerge.merge;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A set of canonical keys ({@link Canon#key}) that stays small for large tables: whole-number keys, by far the most
 * common primary keys, are kept as primitive longs (8-16 bytes each instead of ~60 for a String); anything else falls
 * back to a {@link HashSet}. A million numeric keys take about 16 MB.
 */
final class KeySet {

    private static final Pattern LONG = Pattern.compile("-?(0|[1-9][0-9]{0,17})");

    private final LongSet longs = new LongSet();
    private final Set<String> strings = new HashSet<>();

    boolean add(String key) {
        Long l = asLong(key);
        return l != null ? longs.add(l) : strings.add(key);
    }

    boolean contains(String key) {
        Long l = asLong(key);
        return l != null ? longs.contains(l) : strings.contains(key);
    }

    boolean remove(String key) {
        Long l = asLong(key);
        return l != null ? longs.remove(l) : strings.remove(key);
    }

    long size() {
        return longs.size() + strings.size();
    }

    boolean isEmpty() {
        return size() == 0;
    }

    KeySet copy() {
        KeySet k = new KeySet();
        k.longs.addAll(longs);
        k.strings.addAll(strings);
        return k;
    }

    /** Canonical whole numbers only: "12" but not "012", "1.0" or "1E3", so the long round-trips to the same key. */
    static Long asLong(String key) {
        if (key == null || key.length() > 19 || !LONG.matcher(key).matches()) return null;
        return Long.parseLong(key);
    }

    /** Open-addressing hash set of longs. */
    static final class LongSet {
        private static final long EMPTY = Long.MIN_VALUE;
        private long[] table = new long[16];
        private boolean hasEmptyValue; // Long.MIN_VALUE itself, which marks free slots
        private int size;

        LongSet() {
            Arrays.fill(table, EMPTY);
        }

        int size() {
            return size + (hasEmptyValue ? 1 : 0);
        }

        boolean add(long v) {
            if (v == EMPTY) {
                boolean was = hasEmptyValue;
                hasEmptyValue = true;
                return !was;
            }
            if ((size + 1) * 2 > table.length) grow();
            int i = slot(v, table.length);
            while (table[i] != EMPTY) {
                if (table[i] == v) return false;
                i = (i + 1) & (table.length - 1);
            }
            table[i] = v;
            size++;
            return true;
        }

        boolean contains(long v) {
            if (v == EMPTY) return hasEmptyValue;
            int i = slot(v, table.length);
            while (table[i] != EMPTY) {
                if (table[i] == v) return true;
                i = (i + 1) & (table.length - 1);
            }
            return false;
        }

        boolean remove(long v) {
            if (v == EMPTY) {
                boolean was = hasEmptyValue;
                hasEmptyValue = false;
                return was;
            }
            int i = slot(v, table.length);
            while (table[i] != EMPTY) {
                if (table[i] == v) {
                    // backward-shift deletion keeps every probe chain intact
                    int j = i;
                    while (true) {
                        j = (j + 1) & (table.length - 1);
                        if (table[j] == EMPTY) break;
                        int home = slot(table[j], table.length);
                        boolean between = i <= j ? (i < home && home <= j) : (i < home || home <= j);
                        if (!between) {
                            table[i] = table[j];
                            i = j;
                        }
                    }
                    table[i] = EMPTY;
                    size--;
                    return true;
                }
                i = (i + 1) & (table.length - 1);
            }
            return false;
        }

        void addAll(LongSet other) {
            if (other.hasEmptyValue) add(EMPTY);
            for (long v : other.table) if (v != EMPTY) add(v);
        }

        private void grow() {
            long[] old = table;
            table = new long[old.length * 2];
            Arrays.fill(table, EMPTY);
            size = 0;
            for (long v : old) if (v != EMPTY) add(v);
        }

        private static int slot(long v, int len) {
            long h = v * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32)) & (len - 1);
        }
    }
}
