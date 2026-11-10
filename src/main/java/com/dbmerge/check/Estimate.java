package com.dbmerge.check;

import com.dbmerge.check.Preflight.Category;
import com.dbmerge.check.Preflight.Check;
import com.dbmerge.check.Preflight.ConnectionReport;
import com.dbmerge.check.Preflight.Status;
import com.dbmerge.check.Preflight.TableSize;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rough time and disk needs of a report bundle, from the table statistics of each connection and the rates measured
 * with the perf kit on a laptop (README, "Performance test kit"). A real server may be several times faster or
 * slower; the point is the order of magnitude and whether the disk is big enough.
 *
 * @param dataSeconds     data comparison: tables one after another, databases hashed in parallel
 * @param planSeconds     merge dry run: every row of the master and the sources is read once, in key order
 * @param planFileBytes   plan files for tables above the streaming threshold, if every source row had to be written
 * @param freeBytes       usable space where the bundle is written (-1 if unknown)
 */
public record Estimate(long dataSeconds, long planSeconds, long planFileBytes, long freeBytes, List<Check> checks) {

    /** Seconds per million rows of the largest copy of a table (hash comparison, both databases at once). */
    static final double DATA_SECONDS_PER_MILLION = 10;
    /** Seconds per million rows read by the planner (master plus sources). */
    static final double PLAN_SECONDS_PER_MILLION = 9;
    /** Bytes of plan file per planned row of a streamed table. */
    static final long PLAN_BYTES_PER_ROW = 300;
    /** Fixed cost per run: JVM start and reading the schemas. */
    static final long OVERHEAD_SECONDS = 10;

    public static Estimate of(ConnectionReport master, List<ConnectionReport> sources, int streamAbove, Path outDir) {
        Map<String, Long> largest = new HashMap<>();
        long planRows = 0;
        for (ConnectionReport r : all(master, sources)) {
            for (TableSize t : r.allTables()) {
                if (t.temporary() || t.rows() == null) continue;
                largest.merge(t.table(), t.rows(), Math::max);
                planRows += t.rows();
            }
        }
        long dataRows = largest.values().stream().mapToLong(Long::longValue).sum();
        long spoolRows = 0;
        for (ConnectionReport r : sources) {
            for (TableSize t : r.allTables()) {
                if (!t.temporary() && t.rows() != null && largest.getOrDefault(t.table(), 0L) > streamAbove) spoolRows += t.rows();
            }
        }
        long free = -1;
        try {
            Path p = outDir.toAbsolutePath();
            while (p != null && !Files.exists(p)) p = p.getParent();
            if (p != null) free = Files.getFileStore(p).getUsableSpace();
        } catch (IOException e) {
            free = -1;
        }
        long planBytes = spoolRows * PLAN_BYTES_PER_ROW;
        Estimate e = new Estimate(OVERHEAD_SECONDS + Math.round(dataRows / 1e6 * DATA_SECONDS_PER_MILLION),
                OVERHEAD_SECONDS + Math.round(planRows / 1e6 * PLAN_SECONDS_PER_MILLION), planBytes, free, List.of());
        Check disk;
        if (free < 0) {
            disk = new Check("Disk space for plan files", Category.CAPACITY, Status.WARN, "Free space at " + outDir + " is unknown; the dry run may"
                    + " need up to " + bytes(planBytes) + ".");
        } else if (planBytes > free * 0.8) {
            disk = new Check("Disk space for plan files", Category.CAPACITY, Status.FAIL, "The dry run may need up to " + bytes(planBytes)
                    + " for plan files but only " + bytes(free) + " is free at " + outDir + ". Free space, write elsewhere (--out), or skip the dry run (--skip-plan).");
        } else {
            disk = new Check("Disk space for plan files", Category.CAPACITY, Status.OK, "Up to " + bytes(planBytes) + " needed, " + bytes(free) + " free.");
        }
        return new Estimate(e.dataSeconds, e.planSeconds, planBytes, free, List.of(disk));
    }

    private static List<ConnectionReport> all(ConnectionReport master, List<ConnectionReport> sources) {
        java.util.ArrayList<ConnectionReport> out = new java.util.ArrayList<>();
        out.add(master);
        out.addAll(sources);
        return out;
    }

    public static String bytes(long b) {
        if (b < 1024) return b + " B";
        double v = b;
        String[] units = {"KB", "MB", "GB", "TB"};
        int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < units.length - 1);
        return String.format(java.util.Locale.ROOT, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[i]);
    }

    public static String duration(long seconds) {
        if (seconds < 90) return seconds + " s";
        if (seconds < 5400) return Math.round(seconds / 60.0) + " min";
        return String.format(java.util.Locale.ROOT, "%.1f h", seconds / 3600.0);
    }
}
