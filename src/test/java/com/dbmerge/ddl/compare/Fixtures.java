package com.dbmerge.ddl.compare;

import com.dbmerge.ddl.model.ColumnDef;
import com.dbmerge.ddl.model.ConstraintDef;
import com.dbmerge.ddl.model.IndexDef;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.SequenceDef;
import com.dbmerge.ddl.model.TableDef;
import com.dbmerge.ddl.model.TriggerDef;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Tiny builders so comparator tests read like schema descriptions. */
public final class Fixtures {
    private Fixtures() { }

    public static ColumnDef varchar(String name, int pos, int len, boolean nullable) {
        return new ColumnDef(name, pos, "VARCHAR2", len, null, null, "C", nullable, null, false, false);
    }

    public static ColumnDef varcharBytes(String name, int pos, int len, boolean nullable) {
        return new ColumnDef(name, pos, "VARCHAR2", len, null, null, "B", nullable, null, false, false);
    }

    public static ColumnDef charCol(String name, int pos, int len, boolean nullable) {
        return new ColumnDef(name, pos, "CHAR", len, null, null, "C", nullable, null, false, false);
    }

    public static ColumnDef number(String name, int pos, Integer p, Integer s, boolean nullable) {
        return new ColumnDef(name, pos, "NUMBER", 22, p, s, null, nullable, null, false, false);
    }

    public static ColumnDef withDefault(ColumnDef c, String dflt) {
        return new ColumnDef(c.name(), c.position(), c.dataType(), c.length(), c.precision(), c.scale(), c.charUsed(),
                c.nullable(), dflt, c.identity(), c.virtual());
    }

    public static ConstraintDef pk(String name, boolean generated, String... cols) {
        return new ConstraintDef(name, "P", List.of(cols), null, null, List.of(), null, "ENABLED", generated);
    }

    public static ConstraintDef unique(String name, boolean generated, String... cols) {
        return new ConstraintDef(name, "U", List.of(cols), null, null, List.of(), null, "ENABLED", generated);
    }

    public static ConstraintDef fk(String name, boolean generated, String col, String refTable, String refCol) {
        return new ConstraintDef(name, "R", List.of(col), null, refTable, List.of(refCol), "NO ACTION", "ENABLED", generated);
    }

    public static ConstraintDef check(String name, boolean generated, String cond) {
        return new ConstraintDef(name, "C", List.of(), cond, null, List.of(), null, "ENABLED", generated);
    }

    public static ConstraintDef disabled(ConstraintDef c) {
        return new ConstraintDef(c.name(), c.type(), c.columns(), c.condition(), c.refTable(), c.refColumns(),
                c.deleteRule(), "DISABLED", c.generatedName());
    }

    public static IndexDef index(String name, boolean unique, String... cols) {
        return new IndexDef(name, unique, "NORMAL", List.of(cols), false);
    }

    public static TriggerDef trigger(String name, String body) {
        return new TriggerDef(name, "BEFORE EACH ROW", "INSERT", "ENABLED", null, body);
    }

    public static SequenceDef sequence(String name, String increment) {
        return new SequenceDef(name, "1", "9999999999999999999999999999", increment, false, false, 20);
    }

    public static TableBuilder table(String name) {
        return new TableBuilder(name);
    }

    public static final class TableBuilder {
        private final String name;
        private final Map<String, ColumnDef> cols = new LinkedHashMap<>();
        private final List<ConstraintDef> cons = new ArrayList<>();
        private final List<IndexDef> idx = new ArrayList<>();
        private final List<TriggerDef> trg = new ArrayList<>();

        TableBuilder(String name) { this.name = name; }

        public TableBuilder col(ColumnDef... cs) { for (ColumnDef c : cs) cols.put(c.name(), c); return this; }
        public TableBuilder cons(ConstraintDef... cs) { cons.addAll(List.of(cs)); return this; }
        public TableBuilder idx(IndexDef... is) { idx.addAll(List.of(is)); return this; }
        public TableBuilder trg(TriggerDef... ts) { trg.addAll(List.of(ts)); return this; }
        public TableDef build() { return new TableDef(name, cols, cons, idx, trg); }
    }

    public static SchemaModel model(String nickname, TableDef... tables) {
        return model(nickname, List.of(), tables);
    }

    public static SchemaModel model(String nickname, List<SequenceDef> seqs, TableDef... tables) {
        Map<String, TableDef> t = new TreeMap<>();
        for (TableDef td : tables) t.put(td.name(), td);
        Map<String, SequenceDef> s = new TreeMap<>();
        for (SequenceDef sd : seqs) s.put(sd.name(), sd);
        return new SchemaModel(nickname, nickname.toUpperCase(), t, s, new LinkedHashMap<>(), new ArrayList<>(), Instant.now());
    }
}
