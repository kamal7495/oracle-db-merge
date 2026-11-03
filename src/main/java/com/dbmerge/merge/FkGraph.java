package com.dbmerge.merge;

import com.dbmerge.ddl.model.ConstraintDef;
import com.dbmerge.ddl.model.TableDef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Foreign-key dependency graph over the tables being merged. Parents are loaded before children (Kahn's algorithm,
 * alphabetical tie-break so the order is deterministic). Tables that cannot be ordered because they sit in a cycle
 * are reported; self-references are reported separately because they are resolved by ordering rows, not tables.
 */
public final class FkGraph {

    public record Edge(String child, String parent, String constraint) { }

    private final List<String> order;
    private final List<Set<String>> cycles;
    private final List<Edge> cycleEdges;
    private final List<Edge> selfEdges;

    private FkGraph(List<String> order, List<Set<String>> cycles, List<Edge> cycleEdges, List<Edge> selfEdges) {
        this.order = order;
        this.cycles = cycles;
        this.cycleEdges = cycleEdges;
        this.selfEdges = selfEdges;
    }

    /** Tables in load order (cycle members are appended at the end, alphabetically). */
    public List<String> order() { return order; }

    /** Groups of tables that depend on each other; empty when the graph is acyclic. */
    public List<Set<String>> cycles() { return cycles; }

    /** Foreign keys between different tables inside a cycle: these must be disabled or deferred during the load. */
    public List<Edge> cycleEdges() { return cycleEdges; }

    /** Foreign keys from a table to itself. */
    public List<Edge> selfEdges() { return selfEdges; }

    public static FkGraph build(Map<String, TableDef> tables) {
        Map<String, Set<String>> parents = new TreeMap<>();
        List<Edge> self = new ArrayList<>();
        List<Edge> all = new ArrayList<>();
        for (String t : tables.keySet()) parents.put(t, new TreeSet<>());
        for (TableDef t : tables.values()) {
            for (ConstraintDef c : t.constraints()) {
                if (!c.type().equals("R") || c.refTable() == null || !tables.containsKey(c.refTable())) continue;
                Edge e = new Edge(t.name(), c.refTable(), c.name());
                if (t.name().equals(c.refTable())) { self.add(e); continue; }
                parents.get(t.name()).add(c.refTable());
                all.add(e);
            }
        }

        Map<String, Set<String>> remaining = new LinkedHashMap<>();
        for (var e : parents.entrySet()) remaining.put(e.getKey(), new TreeSet<>(e.getValue()));
        List<String> order = new ArrayList<>();
        while (true) {
            List<String> ready = remaining.entrySet().stream().filter(e -> e.getValue().isEmpty()).map(Map.Entry::getKey).sorted().toList();
            if (ready.isEmpty()) break;
            for (String r : ready) {
                order.add(r);
                remaining.remove(r);
                remaining.values().forEach(s -> s.remove(r));
            }
        }

        List<Set<String>> cycles = new ArrayList<>();
        List<Edge> cycleEdges = new ArrayList<>();
        if (!remaining.isEmpty()) {
            // Everything left is in a cycle or depends on one. Group into strongly connected pieces by reachability.
            Set<String> left = new TreeSet<>(remaining.keySet());
            Set<String> assigned = new LinkedHashSet<>();
            for (String start : left) {
                if (assigned.contains(start)) continue;
                Set<String> comp = new TreeSet<>();
                for (String other : left) {
                    if (reaches(parents, start, other, left) && reaches(parents, other, start, left)) comp.add(other);
                }
                if (comp.size() > 1) { cycles.add(comp); assigned.addAll(comp); }
            }
            for (Edge e : all) {
                for (Set<String> c : cycles) {
                    if (c.contains(e.child()) && c.contains(e.parent())) cycleEdges.add(e);
                }
            }
            order.addAll(left);
        }
        return new FkGraph(order, cycles, cycleEdges, self);
    }

    private static boolean reaches(Map<String, Set<String>> parents, String from, String to, Set<String> within) {
        if (from.equals(to)) return true;
        Set<String> seen = new LinkedHashSet<>();
        List<String> stack = new ArrayList<>(List.of(from));
        while (!stack.isEmpty()) {
            String cur = stack.remove(stack.size() - 1);
            for (String p : parents.getOrDefault(cur, Set.of())) {
                if (!within.contains(p)) continue;
                if (p.equals(to)) return true;
                if (seen.add(p)) stack.add(p);
            }
        }
        return false;
    }
}
