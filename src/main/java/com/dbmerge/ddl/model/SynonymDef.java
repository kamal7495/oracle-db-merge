package com.dbmerge.ddl.model;

/**
 * A private synonym owned by the schema. {@code targetOwner} is {@link #OWN_SCHEMA} when the synonym points into
 * the schema itself, so two databases that use different schema names still compare equal.
 */
public record SynonymDef(String name, String targetOwner, String targetName, String dbLink) {

    public static final String OWN_SCHEMA = "(own schema)";

    public String target() {
        return (targetOwner == null ? "" : targetOwner + ".") + targetName + (dbLink == null ? "" : "@" + dbLink);
    }
}
