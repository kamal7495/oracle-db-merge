package com.dbmerge.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CliOutputTest {

    @Test
    void aTableMissingFromADatabaseIsShownAsMissingNotMinusOne() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("DB_A", -1L);
        counts.put("DB_B", 3L);
        assertEquals("{DB_A=missing, DB_B=3}", Cli.rowText(counts));
    }
}
