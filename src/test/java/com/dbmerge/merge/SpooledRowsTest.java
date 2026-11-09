package com.dbmerge.merge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.dbmerge.merge.MergeModel.FkRef;
import com.dbmerge.merge.MergeModel.PlannedRow;
import com.dbmerge.merge.MergeModel.RowAction;
import java.io.ObjectOutputStream;
import java.math.BigDecimal;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpooledRowsTest {

    @TempDir Path dir;

    private static PlannedRow row(int i) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("ID", new BigDecimal(i));
        v.put("NAME", "row " + i);
        v.put("AT", Timestamp.valueOf("2024-01-01 08:00:00.123456"));
        v.put("TZ", OffsetDateTime.of(2024, 1, 1, 9, 0, 0, 0, ZoneOffset.ofHours(2)));
        v.put("BLOB", new byte[] {1, 2, (byte) i});
        v.put("BOOK_ID", new FkRef("BOOK", "S", String.valueOf(i % 7), "BOOK_ID"));
        v.put("NOTES", null);
        RowAction a = i % 3 == 0 ? RowAction.INSERT_REMAPPED : RowAction.INSERT;
        return new PlannedRow("S", a, String.valueOf(i), "ID=" + i, v, List.of(new BigDecimal(i)), List.of(), i % 3 == 0 ? "re-keyed" : null);
    }

    @Test
    void rowsRoundTripWithEveryValueTypeAndCountsAreKept() {
        SpooledRows s = new SpooledRows(dir, "TRADE");
        for (int i = 0; i < 2_345; i++) s.append(row(i));
        s.finish();
        assertEquals(2_345, s.size());
        assertEquals(782, s.count(RowAction.INSERT_REMAPPED));
        assertEquals(1_563, s.count(RowAction.INSERT));
        List<PlannedRow> back = new ArrayList<>();
        s.forEach(back::add);
        assertEquals(2_345, back.size());
        for (int i : new int[] {0, 1, 499, 500, 501, 2_344}) {
            PlannedRow r = back.get(i), e = row(i);
            assertEquals(e.action(), r.action());
            assertEquals(e.values().get("ID"), r.values().get("ID"));
            assertEquals(e.values().get("TZ"), r.values().get("TZ"));
            assertEquals(e.values().get("BOOK_ID"), r.values().get("BOOK_ID"));
            assertArrayEquals((byte[]) e.values().get("BLOB"), (byte[]) r.values().get("BLOB"));
            assertTrue(r.values().containsKey("NOTES") && r.values().get("NOTES") == null);
            assertEquals(e.masterPk(), r.masterPk());
        }
        assertEquals("ID=1000", s.get(1000).businessKey());
        assertEquals(2_345, s.stream().count(), "can be read more than once");
        s.delete();
        assertFalse(Files.exists(s.file()));
    }

    @Test
    void theFileIsOwnerOnlyAndForeignObjectsAreRejected() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        SpooledRows s = new SpooledRows(dir, "T");
        s.append(row(1));
        s.finish();
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(s.file())));

        // a tampered file holding a class outside the plan's types must not be deserialized
        SpooledRows evil = new SpooledRows(dir, "EVIL");
        evil.finish();
        try (ObjectOutputStream o = new ObjectOutputStream(Files.newOutputStream(evil.file()))) {
            o.writeObject(new java.io.File("/etc/passwd"));
        }
        var f = SpooledRows.class.getDeclaredField("written");
        f.setAccessible(true);
        f.setInt(evil, 1);
        assertThrows(IllegalStateException.class, () -> evil.iterator().next());
    }

    @Test
    void cannotBeReadWhileWritingOrWrittenAfterFinishing() {
        SpooledRows s = new SpooledRows(dir, "T");
        s.append(row(1));
        assertThrows(IllegalStateException.class, s::iterator);
        s.finish();
        assertThrows(IllegalStateException.class, () -> s.append(row(2)));
        assertEquals(1, s.size());
    }
}
