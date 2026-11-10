package com.dbmerge.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parsed command line: {@code cli <command> [options] [nicknames...]}. Parsing is strict: unknown options,
 * missing values, repeated single-value options and malformed values are usage errors (exit code 2).
 *
 * <p>Passwords are never accepted on the command line, because arguments end up in shell history and in
 * {@code ps} output. Anything that looks like a password option, or a {@code user/password@host} connect string,
 * is refused without echoing its value.
 */
public final class CliArgs {

    public enum Command { CONNECTIONS, DDL, DATA, REFS, PLAN, SNAPSHOT, PREFLIGHT, BUNDLE, HELP }

    /** Option definition: {@code valued} options take a value; {@code repeatable} ones may be given more than once. */
    record Opt(String name, boolean valued, boolean repeatable) { }

    private static final Pattern NICKNAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_\\-]{0,29}$");
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z][A-Za-z0-9_$#]{0,127}$");
    private static final Pattern ENV_VAR = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,127}$");

    private static final List<Opt> COMMON = List.of(
            new Opt("out", true, false), new Opt("json", false, false), new Opt("store-dir", true, false),
            new Opt("profile", true, false), new Opt("config", true, false), new Opt("password-env", true, true),
            new Opt("help", false, false));

    /** --include / --exclude name patterns, and --ignore difference kinds where findings are compared. */
    private static List<Opt> withFilter(boolean ignore, Opt... opts) {
        List<Opt> out = new java.util.ArrayList<>(List.of(opts));
        out.add(new Opt("include", true, true));
        out.add(new Opt("exclude", true, true));
        if (ignore) out.add(new Opt("ignore", true, true));
        return List.copyOf(out);
    }

    private static final Map<Command, List<Opt>> SPECIFIC = Map.ofEntries(
            Map.entry(Command.CONNECTIONS, List.of(new Opt("test", false, false))),
            Map.entry(Command.DDL, withFilter(true, new Opt("fail-on", true, false), new Opt("no-ddl-text", false, false),
                    new Opt("snapshot", true, true))),
            Map.entry(Command.SNAPSHOT, withFilter(false, new Opt("file", true, false))),
            Map.entry(Command.DATA, withFilter(true, new Opt("tables", true, true), new Opt("key", true, true),
                    new Opt("allow-uncompared", false, false), new Opt("max-rows", true, false))),
            Map.entry(Command.REFS, withFilter(false, new Opt("check-data", false, false), new Opt("timeout", true, false),
                    new Opt("max-checks", true, false), new Opt("fail-on", true, false))),
            Map.entry(Command.PLAN, withFilter(true, new Opt("master", true, false), new Opt("key", true, true), new Opt("ack", true, true),
                    new Opt("orphans", true, false), new Opt("sequence", true, true), new Opt("rules", true, false),
                    new Opt("fail-on-warnings", false, false))),
            Map.entry(Command.PREFLIGHT, List.of(new Opt("master", true, false), new Opt("max-rows", true, false))),
            Map.entry(Command.BUNDLE, withFilter(true, new Opt("master", true, false), new Opt("key", true, true), new Opt("ack", true, true),
                    new Opt("orphans", true, false), new Opt("sequence", true, true), new Opt("rules", true, false),
                    new Opt("tables", true, true), new Opt("max-rows", true, false), new Opt("no-check-data", false, false),
                    new Opt("skip-data", false, false), new Opt("skip-plan", false, false),
                    new Opt("allow-write-access", false, false), new Opt("allow-incomplete", false, false))),
            Map.entry(Command.HELP, List.of()));

    private final Command command;
    private final Map<String, List<String>> options;
    private final List<String> positional;

    private CliArgs(Command command, Map<String, List<String>> options, List<String> positional) {
        this.command = command;
        this.options = options;
        this.positional = positional;
    }

    public Command command() { return command; }
    public List<String> positional() { return positional; }
    public boolean has(String option) { return options.containsKey(option); }
    public boolean help() { return command == Command.HELP || has("help"); }

    public String value(String option) {
        List<String> v = options.get(option);
        return v == null ? null : v.get(0);
    }

    public List<String> values(String option) {
        return options.getOrDefault(option, List.of());
    }

    // ------------------------------------------------------------------ parsing

    public static CliArgs parse(String[] args) {
        if (args.length == 0) throw new UsageException("No command given.");
        refusePasswords(args);
        Command cmd = command(args[0]);
        Map<String, Opt> allowed = new LinkedHashMap<>();
        for (Opt o : COMMON) allowed.put(o.name(), o);
        for (Opt o : SPECIFIC.get(cmd)) allowed.put(o.name(), o);

        Map<String, List<String>> opts = new LinkedHashMap<>();
        List<String> pos = new ArrayList<>();
        boolean endOfOptions = false;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if (endOfOptions || !a.startsWith("-")) {
                pos.add(a);
                continue;
            }
            if (a.equals("--")) {
                endOfOptions = true;
                continue;
            }
            if (a.equals("-h")) a = "--help";
            if (!a.startsWith("--")) throw new UsageException("Unknown option " + a + ". Use --help.");
            String name = a.substring(2);
            String value = null;
            int eq = name.indexOf('=');
            if (eq >= 0) {
                value = name.substring(eq + 1);
                name = name.substring(0, eq);
            }
            Opt o = allowed.get(name);
            if (o == null) throw new UsageException("Unknown option --" + name + " for '" + cmd.name().toLowerCase(Locale.ROOT) + "'. Use --help.");
            if (o.valued()) {
                if (value == null) {
                    if (i + 1 >= args.length || args[i + 1].startsWith("--")) throw new UsageException("--" + name + " needs a value.");
                    value = args[++i];
                }
                if (value.isBlank()) throw new UsageException("--" + name + " needs a value.");
            } else if (value != null) {
                throw new UsageException("--" + name + " does not take a value.");
            }
            List<String> list = opts.computeIfAbsent(name, k -> new ArrayList<>());
            if (!list.isEmpty() && !o.repeatable()) throw new UsageException("--" + name + " given more than once.");
            list.add(o.valued() ? value : "true");
        }
        CliArgs parsed = new CliArgs(cmd, opts, List.copyOf(pos));
        if (!parsed.help()) parsed.validate();
        return parsed;
    }

    private static Command command(String s) {
        if (s.equals("--help") || s.equals("-h")) return Command.HELP;
        try {
            return Command.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new UsageException("Unknown command '" + s + "'. Commands: connections, ddl, data, refs, plan, snapshot, preflight, bundle, help.");
        }
    }

    /** Refuses anything that would put a password on the command line. Never echoes the offending value. */
    static void refusePasswords(String[] args) {
        for (String a : args) {
            String lower = a.toLowerCase(Locale.ROOT);
            String name = lower.startsWith("-") ? lower.replaceFirst("^-+", "").split("=", 2)[0] : null;
            if (name != null && !name.equals("password-env")
                    && (name.equals("p") || name.contains("password") || name.contains("passwd") || name.equals("pw") || name.equals("pwd")
                        || name.contains("secret"))) {
                throw new UsageException("Passwords are not accepted on the command line (they end up in shell history and"
                        + " process lists). Save the connection with its password in the UI, or put the password in an"
                        + " environment variable and pass --password-env NICKNAME=VARIABLE.");
            }
            if (name == null && a.contains("/") && a.contains("@")) {
                throw new UsageException("An argument looks like a user/password@host connect string. Use a connection"
                        + " nickname instead, with --password-env NICKNAME=VARIABLE if it has no saved password.");
            }
        }
    }

    // --------------------------------------------------------------- validation

    private void validate() {
        for (String n : positional) checkNickname(n);
        if (new LinkedHashSet<>(positional).size() != positional.size()) throw new UsageException("A connection is listed twice.");
        passwordEnv(); // validates
        switch (command) {
            case CONNECTIONS -> {
                if (!positional.isEmpty()) throw new UsageException("'connections' takes no connection names.");
            }
            case DDL -> {
                filter();
                if (positional.size() + values("snapshot").size() < 2) {
                    throw new UsageException("'ddl' needs at least two connections or snapshots (--snapshot FILE).");
                }
                ddlThreshold();
            }
            case SNAPSHOT -> {
                filter();
                if (positional.size() != 1) throw new UsageException("'snapshot' needs exactly one connection.");
            }
            case DATA -> {
                filter();
                if (positional.size() < 2) throw new UsageException("'data' needs at least two connections.");
                tables();
                keys();
                intOption("max-rows", 200_000, 0, Integer.MAX_VALUE);
            }
            case REFS -> {
                filter();
                if (positional.size() != 1) throw new UsageException("'refs' needs exactly one connection.");
                refsThreshold();
                intOption("timeout", 60, 1, 3_600);
                intOption("max-checks", 1_000, 1, 100_000);
                if (!has("check-data") && (has("timeout") || has("max-checks"))) {
                    throw new UsageException("--timeout and --max-checks only apply with --check-data.");
                }
            }
            case PLAN -> {
                filter();
                String master = value("master");
                if (master == null) throw new UsageException("'plan' needs --master NICKNAME.");
                checkNickname(master);
                if (positional.isEmpty()) throw new UsageException("'plan' needs at least one source connection.");
                if (positional.contains(master)) throw new UsageException("The master cannot also be a source.");
                keys();
                acknowledged();
                sequences();
                orphans();
            }
            case PREFLIGHT -> {
                if (positional.isEmpty() && value("master") == null) throw new UsageException("'preflight' needs at least one connection.");
                if (value("master") != null) {
                    checkNickname(value("master"));
                    if (positional.contains(value("master"))) throw new UsageException("The master is listed twice.");
                }
                intOption("max-rows", 200_000, 0, Integer.MAX_VALUE);
            }
            case BUNDLE -> {
                filter();
                String master = value("master");
                if (master == null) throw new UsageException("'bundle' needs --master NICKNAME.");
                checkNickname(master);
                if (positional.isEmpty()) throw new UsageException("'bundle' needs at least one source connection.");
                if (positional.contains(master)) throw new UsageException("The master cannot also be a source.");
                keys();
                tables();
                acknowledged();
                sequences();
                orphans();
                intOption("max-rows", 200_000, 0, Integer.MAX_VALUE);
                if (has("skip-data") && has("tables")) throw new UsageException("--tables only applies to the data comparison, which --skip-data turns off.");
            }
            case HELP -> { }
        }
    }

    private static void checkNickname(String n) {
        if (!NICKNAME.matcher(n).matches()) throw new UsageException("'" + n + "' is not a valid connection nickname.");
    }

    private static String ident(String s, String what) {
        String t = s.trim();
        if (!IDENT.matcher(t).matches()) throw new UsageException("'" + s + "' is not a valid " + what + " name.");
        return t.toUpperCase(Locale.ROOT);
    }

    /** {@code --password-env NICK=VAR}: nickname to environment variable name. */
    public Map<String, String> passwordEnv() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String v : values("password-env")) {
            String[] kv = v.split("=", 2);
            if (kv.length != 2) throw new UsageException("--password-env takes NICKNAME=VARIABLE.");
            checkNickname(kv[0]);
            if (!ENV_VAR.matcher(kv[1]).matches()) throw new UsageException("'" + kv[1] + "' is not a valid environment variable name.");
            if (out.put(kv[0], kv[1]) != null) throw new UsageException("--password-env given twice for " + kv[0] + ".");
        }
        return out;
    }

    /** {@code --key TABLE=COL1,COL2}: business key per table. */
    public Map<String, List<String>> keys() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String v : values("key")) {
            String[] kv = v.split("=", 2);
            if (kv.length != 2 || kv[1].isBlank()) throw new UsageException("--key takes TABLE=COLUMN[,COLUMN...].");
            String table = ident(kv[0], "table");
            List<String> cols = new ArrayList<>();
            for (String c : kv[1].split(",")) cols.add(ident(c, "column"));
            if (new LinkedHashSet<>(cols).size() != cols.size()) throw new UsageException("--key lists a column twice for " + table + ".");
            if (out.put(table, List.copyOf(cols)) != null) throw new UsageException("--key given twice for " + table + ".");
        }
        return out;
    }

    /** {@code --tables T1,T2} (repeatable). Empty means all tables. */
    public Set<String> tables() {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values("tables")) for (String t : v.split(",")) out.add(ident(t, "table"));
        return out;
    }

    /** {@code --ack TABLE.COLUMN}: columns confirmed not to be references. */
    public Set<String> acknowledged() {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values("ack")) {
            String[] tc = v.split("\\.", 2);
            if (tc.length != 2) throw new UsageException("--ack takes TABLE.COLUMN.");
            out.add(ident(tc[0], "table") + "." + ident(tc[1], "column"));
        }
        return out;
    }

    /** {@code --sequence TABLE=SEQUENCE}: sequence used to re-key colliding rows. */
    public Map<String, String> sequences() {
        Map<String, String> out = new LinkedHashMap<>();
        for (String v : values("sequence")) {
            String[] kv = v.split("=", 2);
            if (kv.length != 2) throw new UsageException("--sequence takes TABLE=SEQUENCE.");
            String table = ident(kv[0], "table");
            if (out.put(table, ident(kv[1], "sequence")) != null) throw new UsageException("--sequence given twice for " + table + ".");
        }
        return out;
    }

    public String orphans() {
        String v = value("orphans");
        if (v == null) return "FAIL";
        String u = v.toUpperCase(Locale.ROOT);
        if (!u.equals("FAIL") && !u.equals("SKIP")) throw new UsageException("--orphans must be 'fail' or 'skip'.");
        return u;
    }

    /** DDL threshold: CRITICAL, HIGH (default), MEDIUM, LOW or NEVER. */
    public ExitCodes.Threshold ddlThreshold() {
        return threshold("HIGH", Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "NEVER"));
    }

    /** Reference threshold: HIGH (default), MEDIUM, LOW or NEVER. */
    public ExitCodes.Threshold refsThreshold() {
        return threshold("HIGH", Set.of("HIGH", "MEDIUM", "LOW", "NEVER"));
    }

    private ExitCodes.Threshold threshold(String dflt, Set<String> allowed) {
        String v = value("fail-on");
        String u = v == null ? dflt : v.toUpperCase(Locale.ROOT);
        if (!allowed.contains(u)) {
            throw new UsageException("--fail-on must be one of " + String.join(", ", allowed.stream().sorted().toList()) + ".");
        }
        return ExitCodes.Threshold.valueOf(u);
    }

    /** The object filter from --include, --exclude and --ignore (empty when none given). */
    public com.dbmerge.ddl.compare.CompareFilter filter() {
        try {
            return com.dbmerge.ddl.compare.CompareFilter.of(values("include"), values("exclude"), values("ignore"));
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }
    }

    public int intOption(String option, int dflt, int min, int max) {
        String v = value(option);
        if (v == null) return dflt;
        int n;
        try {
            n = Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new UsageException("--" + option + " must be a whole number.");
        }
        if (n < min || n > max) throw new UsageException("--" + option + " must be between " + min + " and " + max + ".");
        return n;
    }
}
