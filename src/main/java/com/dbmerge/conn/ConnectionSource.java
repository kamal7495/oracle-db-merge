package com.dbmerge.conn;

import java.sql.Connection;
import java.sql.SQLException;

/** What the extractor needs from the registry; keeps the extraction code free of framework dependencies. */
public interface ConnectionSource {

    /** A read-only connection (explicit READ ONLY transaction, never committed). Caller closes it. */
    Connection openReadOnly(String nickname) throws SQLException;

    /** A read-write connection (autocommit off) used ONLY for the chosen master by the merge executor. Caller closes it. */
    Connection openWritable(String nickname) throws SQLException;

    /** Upper-case schema (owner) whose objects are extracted for this connection. */
    String schemaOf(String nickname);
}
