package com.dbmerge.merge;

import com.dbmerge.merge.MergeModel.ConflictAction;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembered conflict decisions: a rule per table (applies to every conflicting row of that table) and optional
 * overrides per business key. Plain getters/setters so it can be saved as JSON without secrets.
 */
public class Rules {

    public static class TableRule {
        private ConflictAction action;
        private String dateColumn;   // for NEWEST

        public TableRule() { }
        public TableRule(ConflictAction action, String dateColumn) { this.action = action; this.dateColumn = dateColumn; }
        public ConflictAction getAction() { return action; }
        public void setAction(ConflictAction action) { this.action = action; }
        public String getDateColumn() { return dateColumn; }
        public void setDateColumn(String dateColumn) { this.dateColumn = dateColumn; }
    }

    public static class RowDecision {
        private ConflictAction action;
        private Map<String, String> manualValues = new LinkedHashMap<>();

        public RowDecision() { }
        public RowDecision(ConflictAction action, Map<String, String> manualValues) {
            this.action = action;
            if (manualValues != null) this.manualValues = new LinkedHashMap<>(manualValues);
        }
        public ConflictAction getAction() { return action; }
        public void setAction(ConflictAction action) { this.action = action; }
        public Map<String, String> getManualValues() { return manualValues; }
        public void setManualValues(Map<String, String> manualValues) { this.manualValues = manualValues; }
    }

    private Map<String, TableRule> tables = new LinkedHashMap<>();
    private Map<String, Map<String, RowDecision>> rows = new LinkedHashMap<>();   // table -> business key -> decision

    public Map<String, TableRule> getTables() { return tables; }
    public void setTables(Map<String, TableRule> tables) { this.tables = tables; }
    public Map<String, Map<String, RowDecision>> getRows() { return rows; }
    public void setRows(Map<String, Map<String, RowDecision>> rows) { this.rows = rows; }

    public TableRule tableRule(String table) { return tables.get(table); }

    public RowDecision rowDecision(String table, String businessKey) {
        Map<String, RowDecision> m = rows.get(table);
        return m == null ? null : m.get(businessKey);
    }
}
