package com.dbmerge.merge;

import com.dbmerge.merge.MergeModel.PlannedRow;
import com.dbmerge.merge.MergeModel.RowAction;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.AbstractList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The planned rows of one large table, kept in a file instead of memory. Rows are appended while planning, then the
 * list is read-only: iterating streams the file, and {@link #size()} and {@link #count} are known without reading it.
 * Random access ({@link #get}) reads from the start and is only meant for tests and small lookups.
 *
 * <p>The file holds business data (it is the plan), so it is created readable by the owner only, next to the reports,
 * and deleted with {@link #delete()} when the plan is no longer needed (and at JVM exit). It is read back with a
 * deserialization filter that accepts only the plan's own types and JDK value classes.
 */
final class SpooledRows extends AbstractList<PlannedRow> {

    private static final ObjectInputFilter FILTER = ObjectInputFilter.Config.createFilter(
            "com.dbmerge.merge.MergeModel$*;java.lang.*;java.math.*;java.sql.Timestamp;java.time.*;java.util.*;[B;!*");
    private static final int RESET_EVERY = 500; // keeps the stream's back-reference table small

    private final Path file;
    private ObjectOutputStream out;
    private int written;
    private final Map<RowAction, Long> counts = new EnumMap<>(RowAction.class);

    SpooledRows(Path dir, String name) {
        try {
            Files.createDirectories(dir);
            this.file = Files.createTempFile(dir, name + "-", ".plan");
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            }
            file.toFile().deleteOnExit();
            this.out = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(file), 1 << 16));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the plan file in " + dir, e);
        }
    }

    Path file() { return file; }

    /** Appends a row; only before {@link #finish()}. */
    void append(PlannedRow r) {
        if (out == null) throw new IllegalStateException("plan file already finished");
        try {
            out.writeObject(r);
            if (++written % RESET_EVERY == 0) out.reset();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the plan file " + file, e);
        }
        counts.merge(r.action(), 1L, Long::sum);
    }

    /** Closes the file for writing; the list can be read from then on. */
    SpooledRows finish() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException e) {
                throw new UncheckedIOException("Could not write the plan file " + file, e);
            }
            out = null;
        }
        return this;
    }

    long count(RowAction a) {
        return counts.getOrDefault(a, 0L);
    }

    void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // deleted at JVM exit at the latest
        }
    }

    @Override
    public int size() {
        return written;
    }

    @Override
    public PlannedRow get(int index) {
        if (index < 0 || index >= written) throw new IndexOutOfBoundsException(index);
        Iterator<PlannedRow> it = iterator();
        for (int i = 0; i < index; i++) it.next();
        return it.next();
    }

    @Override
    public Iterator<PlannedRow> iterator() {
        if (out != null) throw new IllegalStateException("plan file still being written");
        if (written == 0) return java.util.Collections.emptyIterator();
        ObjectInputStream in;
        try {
            in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16));
            in.setObjectInputFilter(FILTER);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the plan file " + file, e);
        }
        return new Iterator<>() {
            private int read;

            @Override
            public boolean hasNext() {
                if (read < written) return true;
                close();
                return false;
            }

            @Override
            public PlannedRow next() {
                if (!hasNext()) throw new NoSuchElementException();
                try {
                    PlannedRow r = (PlannedRow) in.readObject();
                    if (++read == written) close();
                    return r;
                } catch (EOFException e) {
                    close();
                    throw new IllegalStateException("The plan file " + file + " is shorter than expected", e);
                } catch (IOException | ClassNotFoundException e) {
                    close();
                    throw new IllegalStateException("Could not read the plan file " + file, e);
                }
            }

            private void close() {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        };
    }
}
