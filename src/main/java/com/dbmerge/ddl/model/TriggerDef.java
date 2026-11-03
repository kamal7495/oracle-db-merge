package com.dbmerge.ddl.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record TriggerDef(
        String name,
        String triggerType,      // e.g. BEFORE EACH ROW
        String event,            // e.g. INSERT OR UPDATE
        String status,           // ENABLED / DISABLED
        String whenClause,
        String body) {           // whitespace-normalised PL/SQL body

    public static String normalizeBody(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }

    /** Short fingerprint of everything except status; shown in reports instead of the full body. */
    public String definitionHash() {
        String all = triggerType + "|" + event + "|" + normalizeBody(whenClause) + "|" + body;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(all.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 5);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String summary() {
        return triggerType + " " + event + " [" + definitionHash() + "]";
    }
}
