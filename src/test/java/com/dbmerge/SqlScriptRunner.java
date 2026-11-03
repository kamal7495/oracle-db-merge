package com.dbmerge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/** Runs a script whose statements are separated by a line containing only "/" (PL/SQL-safe). Test use only. */
public final class SqlScriptRunner {
    private SqlScriptRunner() { }

    public static void run(Connection c, String classpathResource) throws IOException, SQLException {
        try (InputStream in = SqlScriptRunner.class.getResourceAsStream(classpathResource)) {
            if (in == null) throw new IOException("Missing resource " + classpathResource);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder cur = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.trim().equals("/")) {
                    exec(c, cur.toString());
                    cur.setLength(0);
                } else if (!line.trim().startsWith("--")) {
                    cur.append(line).append('\n');
                }
            }
            exec(c, cur.toString());
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        String s = sql.trim();
        if (s.isEmpty()) return;
        // Strip trailing inline comments such as "NOCACHE   -- note" are fine for Oracle; nothing else to do.
        try (Statement st = c.createStatement()) {
            st.execute(s);
        }
    }
}
