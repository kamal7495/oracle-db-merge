package com.dbmerge.merge;

import static com.dbmerge.util.Sql.columnList;
import static com.dbmerge.util.Sql.quote;

import com.dbmerge.ddl.model.ColumnDef;
import com.dbmerge.ddl.model.ConstraintDef;
import com.dbmerge.ddl.model.IndexDef;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.TableDef;
import com.dbmerge.merge.MergeModel.AlterKind;
import com.dbmerge.merge.MergeModel.AlterStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Proposes the schema changes the master needs so that source data fits: audit/mapping table, missing tables, missing
 * columns, and widening of too-short columns. Nothing is ever dropped or narrowed. Oracle commits DDL immediately, so
 * every statement is shown for approval and none is run during a dry run.
 */
public final class AlterScriptGenerator {

    public static final String AUDIT_TABLE = "DBMERGE_ROW_MAP";

    public record Result(List<AlterStatement> alters, Map<String, TableDef> effective) { }

    private AlterScriptGenerator() { }

    public static Result generate(SchemaModel master, List<SchemaModel> sources) {
        List<AlterStatement> alters = new ArrayList<>();
        Map<String, TableDef> effective = new TreeMap<>();
        for (TableDef t : master.tables().values()) {
            effective.put(t.name(), new TableDef(t.name(), new LinkedHashMap<>(t.columns()),
                    new ArrayList<>(t.constraints()), new ArrayList<>(t.indexes()), new ArrayList<>(t.triggers())));
        }

        if (!master.tables().containsKey(AUDIT_TABLE)) {
            alters.add(new AlterStatement("audit-table", AlterKind.CREATE_AUDIT_TABLE, AUDIT_TABLE, auditTableSql(),
                    "Audit and key-mapping table: records source_db, table, old key, new key and action for every merged row.", true));
        }

        List<AlterStatement> foreignKeys = new ArrayList<>();
        for (SchemaModel src : sources) {
            for (TableDef st : src.tables().values()) {
                if (st.name().equals(AUDIT_TABLE)) continue;
                TableDef mt = effective.get(st.name());
                if (mt == null) {
                    alters.add(new AlterStatement("create-table:" + st.name(), AlterKind.CREATE_TABLE, st.name(), createTableSql(st),
                            "Table exists in " + src.nickname() + " but not in the master.", true));
                    for (ConstraintDef c : st.constraints()) {
                        if (c.type().equals("R")) foreignKeys.add(foreignKeySql(st.name(), c));
                    }
                    for (IndexDef i : st.indexes()) {
                        if (i.columns().stream().anyMatch(c -> c.contains("<expression>"))) continue;
                        alters.add(new AlterStatement("create-index:" + st.name() + ":" + i.signature(), AlterKind.CREATE_INDEX, st.name(),
                                "CREATE " + (i.unique() ? "UNIQUE " : "") + "INDEX " + quote(i.generatedName() ? "IX_" + st.name() + "_" + Math.abs(i.signature().hashCode() % 10000) : i.name())
                                        + " ON " + quote(st.name()) + " (" + indexColumns(i) + ")",
                                "Index of the new table in " + src.nickname() + ".", false));
                    }
                    effective.put(st.name(), new TableDef(st.name(), new LinkedHashMap<>(st.columns()),
                            new ArrayList<>(st.constraints()), new ArrayList<>(st.indexes()), new ArrayList<>(st.triggers())));
                    continue;
                }
                for (ColumnDef sc : st.columns().values()) {
                    ColumnDef mc = mt.columns().get(sc.name());
                    if (mc == null) {
                        boolean keepNotNull = !sc.nullable() && sc.defaultValue() != null;
                        String sql = "ALTER TABLE " + quote(st.name()) + " ADD (" + quote(sc.name()) + " " + sc.displayType()
                                + (sc.defaultValue() != null && !sc.identity() && !sc.virtual() ? " DEFAULT " + sc.defaultValue() : "")
                                + (keepNotNull ? " NOT NULL" : "") + ")";
                        alters.add(new AlterStatement("add-column:" + st.name() + "." + sc.name(), AlterKind.ADD_COLUMN, st.name(), sql,
                                "Column exists in " + src.nickname() + " but not in the master."
                                        + (!sc.nullable() && !keepNotNull ? " Added as nullable (source is NOT NULL without a default)." : ""), true));
                        ColumnDef added = new ColumnDef(sc.name(), mt.columns().size() + 1, sc.dataType(), sc.length(), sc.precision(),
                                sc.scale(), sc.charUsed(), !keepNotNull, sc.defaultValue(), false, false);
                        mt.columns().put(sc.name(), added);
                    } else if (widens(mc, sc)) {
                        String widened = widenedType(mc, sc);
                        alters.add(new AlterStatement("widen-column:" + st.name() + "." + sc.name(), AlterKind.WIDEN_COLUMN, st.name(),
                                "ALTER TABLE " + quote(st.name()) + " MODIFY (" + quote(sc.name()) + " " + widened + ")",
                                src.nickname() + " has a wider " + sc.name() + " (" + sc.displayType() + ") than the master (" + mc.displayType() + ").", true));
                        mt.columns().put(sc.name(), new ColumnDef(mc.name(), mc.position(), mc.dataType(),
                                sc.isCharType() ? sc.length() : mc.length(), sc.isCharType() ? mc.precision() : sc.precision(), mc.scale(),
                                mc.charUsed(), mc.nullable(), mc.defaultValue(), mc.identity(), mc.virtual()));
                    }
                }
            }
        }
        alters.addAll(foreignKeys);
        // de-duplicate by id (first source wins)
        Map<String, AlterStatement> unique = new LinkedHashMap<>();
        for (AlterStatement a : alters) unique.putIfAbsent(a.id(), a);
        return new Result(new ArrayList<>(unique.values()), effective);
    }

    static boolean widens(ColumnDef master, ColumnDef source) {
        if (!master.baseType().equals(source.baseType())) return false;
        if (master.isCharType()) {
            return master.length() != null && source.length() != null && source.length() > master.length()
                    && java.util.Objects.equals(master.charUsed(), source.charUsed());
        }
        if (master.baseType().equals("NUMBER") && master.precision() != null && source.precision() != null) {
            return java.util.Objects.equals(master.scale(), source.scale()) && source.precision() > master.precision();
        }
        return false;
    }

    private static String widenedType(ColumnDef master, ColumnDef source) {
        if (master.isCharType()) {
            String sem = "C".equalsIgnoreCase(master.charUsed()) ? " CHAR" : " BYTE";
            return master.baseType() + "(" + source.length() + sem + ")";
        }
        return "NUMBER(" + source.precision() + (master.scale() == null ? "" : "," + master.scale()) + ")";
    }

    static String createTableSql(TableDef t) {
        StringBuilder sb = new StringBuilder("CREATE TABLE " + quote(t.name()) + " (\n");
        List<String> parts = new ArrayList<>();
        for (ColumnDef c : t.columns().values()) {
            if (c.virtual()) continue;
            String def = "    " + quote(c.name()) + " " + c.displayType();
            if (c.identity()) def += " GENERATED BY DEFAULT AS IDENTITY";
            else if (c.defaultValue() != null) def += " DEFAULT " + c.defaultValue();
            if (!c.nullable()) def += " NOT NULL";
            parts.add(def);
        }
        for (ConstraintDef c : t.constraints()) {
            String name = c.generatedName() ? "" : "CONSTRAINT " + quote(c.name()) + " ";
            switch (c.type()) {
                case "P" -> parts.add("    " + name + "PRIMARY KEY (" + columnList(c.columns()) + ")");
                case "U" -> parts.add("    " + name + "UNIQUE (" + columnList(c.columns()) + ")");
                case "C" -> {
                    if (c.condition() != null && !c.condition().toUpperCase().matches("^\"?[A-Z0-9_$#]+\"?\\s+IS\\s+NOT\\s+NULL$")) {
                        parts.add("    " + name + "CHECK (" + c.condition() + ")");
                    }
                }
                default -> { /* foreign keys are added separately once all parent tables exist */ }
            }
        }
        sb.append(String.join(",\n", parts)).append("\n)");
        return sb.toString();
    }

    private static AlterStatement foreignKeySql(String table, ConstraintDef c) {
        String name = c.generatedName() ? "" : "CONSTRAINT " + quote(c.name()) + " ";
        String sql = "ALTER TABLE " + quote(table) + " ADD " + name + "FOREIGN KEY (" + columnList(c.columns()) + ") REFERENCES "
                + quote(c.refTable()) + " (" + columnList(c.refColumns()) + ")"
                + (c.deleteRule() != null && !c.deleteRule().equals("NO ACTION") ? " ON DELETE " + c.deleteRule() : "");
        return new AlterStatement("add-fk:" + table + ":" + c.signature(), AlterKind.ADD_FOREIGN_KEY, table, sql,
                "Foreign key of a newly created table.", false);
    }

    private static String indexColumns(IndexDef i) {
        List<String> cols = new ArrayList<>();
        for (String c : i.columns()) cols.add(c.endsWith(" DESC") ? quote(c.substring(0, c.length() - 5)) + " DESC" : quote(c));
        return String.join(", ", cols);
    }

    static String auditTableSql() {
        return "CREATE TABLE " + quote(AUDIT_TABLE) + " (\n"
                + "    RUN_ID        VARCHAR2(16)   NOT NULL,\n"
                + "    SOURCE_DB     VARCHAR2(30)   NOT NULL,\n"
                + "    TABLE_NAME    VARCHAR2(128)  NOT NULL,\n"
                + "    OLD_PK        VARCHAR2(400),\n"
                + "    NEW_PK        VARCHAR2(400),\n"
                + "    BUSINESS_KEY  VARCHAR2(1000),\n"
                + "    ACTION        VARCHAR2(24)   NOT NULL,\n"
                + "    MERGED_AT     TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL\n"
                + ")";
    }
}
