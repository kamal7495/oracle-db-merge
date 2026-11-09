package com.dbmerge.merge;

import com.dbmerge.merge.MergeModel.PlannedRow;
import com.dbmerge.merge.MergeModel.RowAction;
import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/** Planned rows kept in plan files: counts known without reading them, files deleted when the plan is done. */
interface PlanRows extends List<PlannedRow> {

    long count(RowAction action);

    void delete();

    /**
     * Several plan files read one after the other: a self-referencing table's rows by depth, parents first, so the
     * executor always writes a parent before its children.
     */
    final class Chained extends AbstractList<PlannedRow> implements PlanRows {
        private final List<SpooledRows> parts;

        Chained(List<SpooledRows> parts) {
            this.parts = List.copyOf(parts);
        }

        @Override
        public long count(RowAction action) {
            long n = 0;
            for (SpooledRows p : parts) n += p.count(action);
            return n;
        }

        @Override
        public void delete() {
            parts.forEach(SpooledRows::delete);
        }

        @Override
        public int size() {
            int n = 0;
            for (SpooledRows p : parts) n += p.size();
            return n;
        }

        @Override
        public PlannedRow get(int index) {
            int i = index;
            for (SpooledRows p : parts) {
                if (i < p.size()) return p.get(i);
                i -= p.size();
            }
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        public Iterator<PlannedRow> iterator() {
            return new Iterator<>() {
                private int part;
                private Iterator<PlannedRow> current = java.util.Collections.emptyIterator();

                @Override
                public boolean hasNext() {
                    while (!current.hasNext() && part < parts.size()) current = parts.get(part++).iterator();
                    return current.hasNext();
                }

                @Override
                public PlannedRow next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    return current.next();
                }
            };
        }
    }
}
