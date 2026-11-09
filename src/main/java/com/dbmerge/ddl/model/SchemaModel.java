package com.dbmerge.ddl.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything extracted from one database. {@code ddl} holds DBMS_METADATA text keyed by
 * "TABLE:NAME", "INDEX:NAME", "SEQUENCE:NAME" or "TRIGGER:NAME"; it is informational and is not used for
 * the structural comparison (which works on the parsed model above).
 *
 * <p>{@code codeObjects} (views, materialized views, PL/SQL) is keyed by {@link CodeObjectDef#key()},
 * {@code synonyms} by synonym name and {@code grants} by {@link GrantDef#key()}. Models built with the
 * original seven-argument constructor have none of them.
 */
public record SchemaModel(
        String nickname,
        String owner,
        Map<String, TableDef> tables,
        Map<String, SequenceDef> sequences,
        Map<String, String> ddl,
        List<String> warnings,
        Instant extractedAt,
        Map<String, CodeObjectDef> codeObjects,
        Map<String, SynonymDef> synonyms,
        Map<String, GrantDef> grants) {

    public SchemaModel {
        codeObjects = codeObjects == null ? Map.of() : codeObjects;
        synonyms = synonyms == null ? Map.of() : synonyms;
        grants = grants == null ? Map.of() : grants;
    }

    public SchemaModel(String nickname, String owner, Map<String, TableDef> tables, Map<String, SequenceDef> sequences,
                       Map<String, String> ddl, List<String> warnings, Instant extractedAt) {
        this(nickname, owner, tables, sequences, ddl, warnings, extractedAt, Map.of(), Map.of(), Map.of());
    }

    public int columnCount() {
        return tables.values().stream().mapToInt(t -> t.columns().size()).sum();
    }

    /** Views and materialized views. */
    public int viewCount() {
        return (int) codeObjects.values().stream().filter(o -> o.type().endsWith("VIEW")).count();
    }

    /** Packages, package bodies, procedures, functions, types and type bodies. */
    public int plsqlCount() {
        return codeObjects.size() - viewCount();
    }
}
