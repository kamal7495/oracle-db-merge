package com.dbmerge.merge;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persists remembered conflict rules as JSON. Contains table names, business keys and chosen values; never credentials. */
public class RuleStore {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private Rules rules;

    public RuleStore(Path file) {
        this.file = file;
        this.rules = load();
    }

    private Rules load() {
        try {
            if (Files.isRegularFile(file)) return mapper.readValue(Files.readAllBytes(file), Rules.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read rules file " + file, e);
        }
        return new Rules();
    }

    public synchronized Rules get() { return rules; }

    public synchronized void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), rules);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write rules file " + file, e);
        }
    }

    public synchronized void clear() {
        rules = new Rules();
        save();
    }
}
