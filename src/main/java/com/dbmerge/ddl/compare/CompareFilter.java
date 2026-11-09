package com.dbmerge.ddl.compare;

import com.dbmerge.ddl.model.ConstraintDef;
import com.dbmerge.ddl.model.SchemaModel;
import com.dbmerge.ddl.model.TableDef;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Which objects to compare and which kinds of difference to ignore.
 *
 * <p>Name patterns are case-insensitive. {@code *} or {@code %} match any run of characters and {@code ?} one
 * character; {@code _} is literal because it appears in almost every Oracle name. An object is kept when it matches
 * an include pattern (or there are none) and no exclude pattern. Patterns apply to tables (with their columns,
 * constraints, indexes and triggers), sequences, views, materialized views, PL/SQL units and synonyms by their own
 * name, and to grants by the name of the object granted on.
 *
 * <p>Ignored kinds are given by {@link DiffKind} name ({@code CONSTRAINT_NAME_MISMATCH}) or by category
 * ({@code GRANT} ignores every grant finding). They drop findings from the result; they never change what is read.
 *
 * <p>Applied when the DDL comparison runs, the filtered models are what the data comparison and the merge plan use,
 * so an excluded table is left out of those too.
 */
public record CompareFilter(List<String> include, List<String> exclude, Set<DiffKind> ignore) {

    public static final CompareFilter NONE = new CompareFilter(List.of(), List.of(), Set.of());

    private static final Pattern VALID_PATTERN = Pattern.compile("^[A-Za-z0-9_$#*%?]{1,128}$");

    public CompareFilter {
        include = normalise(include);
        exclude = normalise(exclude);
        ignore = ignore == null || ignore.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(ignore));
    }

    /** Builds a filter from user input, rejecting malformed patterns and unknown kinds. Null lists mean none. */
    public static CompareFilter of(List<String> include, List<String> exclude, List<String> ignore) {
        Set<DiffKind> kinds = EnumSet.noneOf(DiffKind.class);
        for (String raw : split(ignore)) {
            String k = raw.toUpperCase(Locale.ROOT);
            boolean found = false;
            for (DiffKind d : DiffKind.values()) {
                if (d.name().equals(k) || d.category().equals(k)) {
                    kinds.add(d);
                    found = true;
                }
            }
            if (!found) {
                Set<String> cats = new TreeSet<>();
                for (DiffKind d : DiffKind.values()) cats.add(d.category());
                throw new IllegalArgumentException("Unknown difference kind '" + raw + "'. Use a kind such as "
                        + "CONSTRAINT_NAME_MISMATCH or a category: " + String.join(", ", cats) + ".");
            }
        }
        return new CompareFilter(split(include), split(exclude), kinds);
    }

    public boolean isEmpty() {
        return include.isEmpty() && exclude.isEmpty() && ignore.isEmpty();
    }

    public boolean keeps(String name) {
        if (name == null) return true;
        String n = name.toUpperCase(Locale.ROOT);
        if (!include.isEmpty() && include.stream().noneMatch(p -> matches(p, n))) return false;
        return exclude.stream().noneMatch(p -> matches(p, n));
    }

    /** The model with excluded objects removed, plus a warning for every kept table whose declared parent was removed. */
    public SchemaModel apply(SchemaModel m) {
        if (include.isEmpty() && exclude.isEmpty()) return m;
        Map<String, TableDef> tables = keep(m.tables(), Function.identity());
        List<String> warnings = new ArrayList<>(m.warnings());
        for (TableDef t : tables.values()) {
            for (ConstraintDef c : t.constraints()) {
                if ("R".equals(c.type()) && c.refTable() != null && m.tables().containsKey(c.refTable())
                        && !tables.containsKey(c.refTable())) {
                    warnings.add("Filter keeps " + t.name() + " but excludes its parent " + c.refTable() + " ("
                            + c.name() + "): a merge copies " + t.name() + "." + String.join(",", c.columns())
                            + " unchanged, so those values must already exist in the master's " + c.refTable() + ".");
                }
            }
        }
        return new SchemaModel(m.nickname(), m.owner(), tables, keep(m.sequences(), Function.identity()),
                keepDdl(m.ddl()), List.copyOf(warnings), m.extractedAt(),
                m.codeObjects().entrySet().stream().filter(e -> keeps(e.getValue().name()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new)),
                keep(m.synonyms(), Function.identity()),
                m.grants().entrySet().stream().filter(e -> keeps(e.getValue().objectName()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new)));
    }

    public List<Difference> apply(List<Difference> diffs) {
        if (ignore.isEmpty()) return diffs;
        return diffs.stream().filter(d -> !ignore.contains(d.kind())).toList();
    }

    /** One line for reports, e.g. "exclude BKP_*, TMP_*; ignore CONSTRAINT_NAME_MISMATCH". Empty when no filter. */
    public String describe() {
        List<String> parts = new ArrayList<>();
        if (!include.isEmpty()) parts.add("include " + String.join(", ", include));
        if (!exclude.isEmpty()) parts.add("exclude " + String.join(", ", exclude));
        if (!ignore.isEmpty()) parts.add("ignore " + ignore.stream().map(Enum::name).sorted().collect(Collectors.joining(", ")));
        return String.join("; ", parts);
    }

    private <V> Map<String, V> keep(Map<String, V> byName, Function<V, V> f) {
        Map<String, V> out = new LinkedHashMap<>();
        byName.forEach((k, v) -> { if (keeps(k)) out.put(k, f.apply(v)); });
        return out;
    }

    /** DDL text is keyed "TYPE:NAME"; indexes and triggers belong to tables we can't see here, so only TABLE/SEQUENCE keys are filtered. */
    private Map<String, String> keepDdl(Map<String, String> ddl) {
        Map<String, String> out = new LinkedHashMap<>();
        ddl.forEach((k, v) -> {
            int i = k.indexOf(':');
            String type = i < 0 ? "" : k.substring(0, i);
            String name = i < 0 ? k : k.substring(i + 1);
            if (!(type.equals("TABLE") || type.equals("SEQUENCE")) || keeps(name)) out.put(k, v);
        });
        return out;
    }

    static boolean matches(String pattern, String upperName) {
        StringBuilder re = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*', '%' -> re.append(".*");
                case '?' -> re.append('.');
                default -> re.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return upperName.matches(re.toString());
    }

    private static List<String> normalise(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : patterns) {
            String t = p == null ? "" : p.trim().toUpperCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            if (!VALID_PATTERN.matcher(t).matches()) {
                throw new IllegalArgumentException("Invalid name pattern '" + p + "': use letters, digits, _ $ # and the wildcards * % ?");
            }
            if (!out.contains(t)) out.add(t);
        }
        return List.copyOf(out);
    }

    /** Accepts list entries that themselves hold comma-separated values (UI text fields, repeated CLI options). */
    private static List<String> split(List<String> values) {
        List<String> out = new ArrayList<>();
        if (values == null) return out;
        for (String v : values) {
            if (v == null) continue;
            for (String s : v.split(",")) if (!s.isBlank()) out.add(s.trim());
        }
        return out;
    }
}
