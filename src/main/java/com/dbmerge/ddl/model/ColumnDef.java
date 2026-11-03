package com.dbmerge.ddl.model;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One column as read from ALL_TAB_COLS. */
public record ColumnDef(
        String name,
        int position,
        String dataType,      // e.g. VARCHAR2, NUMBER, TIMESTAMP(6) WITH TIME ZONE
        Integer length,       // char length for character types, byte length for RAW
        Integer precision,
        Integer scale,
        String charUsed,      // 'B' (bytes) or 'C' (chars) for character types
        boolean nullable,
        String defaultValue,  // normalised; null when none (identity columns carry no default)
        boolean identity,
        boolean virtual) {

    private static final Pattern FRACTION = Pattern.compile("\\((\\d+)\\)");

    /** Type name without size, e.g. "TIMESTAMP WITH TIME ZONE". */
    public String baseType() {
        return dataType.replaceAll("\\(\\d+\\)", "").trim().toUpperCase(Locale.ROOT);
    }

    public boolean isCharType() {
        String b = baseType();
        return b.equals("VARCHAR2") || b.equals("NVARCHAR2") || b.equals("CHAR") || b.equals("NCHAR");
    }

    /** Size part of the type: "120 CHAR", "10,2", "6", "" ... Compared separately from the type name. */
    public String sizeSpec() {
        String b = baseType();
        if (isCharType()) {
            String sem = "C".equalsIgnoreCase(charUsed) ? " CHAR" : "B".equalsIgnoreCase(charUsed) ? " BYTE" : "";
            return (length == null ? "" : length) + sem;
        }
        if (b.equals("NUMBER") || b.equals("FLOAT")) {
            if (precision == null && scale == null) return "";
            return (precision == null ? "*" : precision.toString()) + (scale == null ? "" : "," + scale);
        }
        if (b.equals("RAW")) return length == null ? "" : length.toString();
        if (b.startsWith("TIMESTAMP") || b.startsWith("INTERVAL")) {
            Matcher m = FRACTION.matcher(dataType);
            StringBuilder sb = new StringBuilder();
            while (m.find()) { if (sb.length() > 0) sb.append(','); sb.append(m.group(1)); }
            return sb.toString();
        }
        return "";
    }

    /** Human-readable type, e.g. VARCHAR2(120 CHAR), NUMBER(10,2), DATE. */
    public String displayType() {
        String b = baseType();
        if (isCharType() || b.equals("NUMBER") || b.equals("FLOAT") || b.equals("RAW")) {
            String s = sizeSpec();
            return s.isEmpty() ? b : b + "(" + s + ")";
        }
        return dataType;
    }

    /** Normalised default for comparison: IDENTITY, the expression, or "(none)". */
    public String defaultDisplay() {
        if (identity) return "IDENTITY";
        if (defaultValue == null || defaultValue.isBlank()) return "(none)";
        return (virtual ? "VIRTUAL AS " : "") + defaultValue;
    }

    /** One-cell summary used in the side-by-side matrix. */
    public String summary() {
        StringBuilder sb = new StringBuilder(displayType());
        if (!nullable) sb.append(" NOT NULL");
        String d = defaultDisplay();
        if (!d.equals("(none)")) sb.append(identity ? " " : " DEFAULT ").append(d);
        return sb.toString();
    }
}
