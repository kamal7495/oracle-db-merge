package com.dbmerge.ddl.model;

import java.util.List;

/**
 * A view, materialized view or stored PL/SQL unit (package, package body, procedure, function, type, type body).
 * {@code lines} is the source after {@link SourceNormalizer} (comments, whitespace, case and own-schema prefixes
 * removed) and is what gets compared. The original text is not kept, so reports only ever show excerpts.
 *
 * @param type    ALL_OBJECTS object type, e.g. "PACKAGE BODY" or "MATERIALIZED VIEW"
 * @param status  VALID / INVALID (null if unknown)
 * @param options extra attributes compared with the source, e.g. the refresh mode of a materialized view
 */
public record CodeObjectDef(
        String type,
        String name,
        String status,
        String options,
        List<SourceNormalizer.Line> lines) {

    public CodeObjectDef {
        lines = List.copyOf(lines);
        options = options == null ? "" : options;
    }

    /** Map key: type and name, e.g. "PACKAGE BODY:PKG_TRADE". */
    public String key() {
        return type + ":" + name;
    }

    public String canonical() {
        return SourceNormalizer.joined(lines);
    }

    public String hash() {
        return SourceNormalizer.hash(canonical() + "|" + options);
    }

    /** One-line summary for the side-by-side matrix: status, size and a short fingerprint. */
    public String summary() {
        return (status == null ? "" : status + ", ") + lines.size() + " lines"
                + (options.isEmpty() ? "" : ", " + options) + " [" + hash() + "]";
    }
}
