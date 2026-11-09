package com.dbmerge.ddl.model;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A schema's structure saved to a JSON file, to compare live databases against later ("has production drifted from
 * the release baseline?"). It holds what the DDL comparison uses: tables with columns, constraints, indexes and
 * triggers, sequences, views, PL/SQL, synonyms and grants. It holds no row data, no passwords and no connection
 * details beyond the original nickname and schema name. DBMS_METADATA text is left out to keep files small.
 *
 * <p>When read back, the snapshot gets a nickname of its own (normally the file name), so a baseline of DB_A can be
 * compared with the live DB_A. A warning in the model records where it came from.
 */
public final class SchemaSnapshot {

    public static final String FORMAT = "dbmerge-schema-snapshot";
    public static final int VERSION = 1;
    private static final Pattern NICKNAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_\\-]{0,29}$");
    private static final long MAX_BYTES = 200L * 1024 * 1024;

    /** The file layout. {@code model.ddl} is always empty. */
    public record File(String format, int version, Instant createdAt, String nickname, String schema, String filter,
                       SchemaModel model) { }

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private SchemaSnapshot() { }

    /** @param filter description of the filter applied before saving, or empty */
    public static String toJson(SchemaModel m, String filter) throws IOException {
        SchemaModel lean = new SchemaModel(m.nickname(), m.owner(), m.tables(), m.sequences(), Map.of(), m.warnings(),
                m.extractedAt(), m.codeObjects(), m.synonyms(), m.grants());
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                new File(FORMAT, VERSION, Instant.now(), m.nickname(), m.owner(), filter == null ? "" : filter, lean));
    }

    public static void write(SchemaModel m, String filter, Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(file, toJson(m, filter));
    }

    public static SchemaModel read(Path file, String nickname) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("Snapshot file not found: " + file);
        if (Files.size(file) > MAX_BYTES) throw new IOException("Snapshot file is larger than 200 MB: " + file);
        return fromJson(Files.readString(file), nickname == null ? nicknameFor(file.getFileName().toString()) : nickname);
    }

    /** Parses a snapshot and renames it to {@code nickname}. */
    public static SchemaModel fromJson(String json, String nickname) throws IOException {
        if (nickname == null || !NICKNAME.matcher(nickname).matches()) {
            throw new IOException("Invalid snapshot name '" + nickname + "' (letters, digits, _ or -, max 30, starting with a letter).");
        }
        File f;
        try {
            f = JSON.readValue(json, File.class);
        } catch (IOException e) {
            throw new IOException("Not a readable schema snapshot: " + firstLine(e.getMessage()));
        }
        if (f == null || !FORMAT.equals(f.format()) || f.model() == null) {
            throw new IOException("Not a schema snapshot (expected \"format\": \"" + FORMAT + "\").");
        }
        if (f.version() != VERSION) throw new IOException("Unsupported snapshot version " + f.version() + " (this build reads " + VERSION + ").");
        SchemaModel m = f.model();
        if (m.owner() == null || m.tables() == null || m.sequences() == null) throw new IOException("Snapshot is missing its schema, tables or sequences.");
        List<String> warnings = new ArrayList<>(m.warnings() == null ? List.of() : m.warnings());
        warnings.add(0, "Snapshot of " + f.nickname() + " (" + f.schema() + ") extracted " + m.extractedAt()
                + (f.filter() == null || f.filter().isEmpty() ? "" : ", filtered: " + f.filter()) + ".");
        return new SchemaModel(nickname, m.owner(), m.tables(), m.sequences(), Map.of(), List.copyOf(warnings),
                m.extractedAt(), m.codeObjects(), m.synonyms(), m.grants());
    }

    /** "baseline-2026.11.json" -> "baseline-2026_11"; prefixed with S_ when it does not start with a letter. */
    public static String nicknameFor(String fileName) {
        String base = fileName.toLowerCase(Locale.ROOT).endsWith(".json") ? fileName.substring(0, fileName.length() - 5) : fileName;
        String n = base.replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (n.isEmpty() || !Character.isLetter(n.charAt(0))) n = "S_" + n;
        return n.length() > 30 ? n.substring(0, 30) : n;
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }
}
