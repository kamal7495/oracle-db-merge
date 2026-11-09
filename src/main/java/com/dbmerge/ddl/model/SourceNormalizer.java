package com.dbmerge.ddl.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns view / PL/SQL source into a canonical form so that only real differences count. Outside string literals
 * and quoted identifiers it:
 * <ul>
 *   <li>removes {@code --} and {@code /* *}{@code /} comments,</li>
 *   <li>upper-cases (unquoted identifiers and keywords are case-insensitive in Oracle),</li>
 *   <li>unquotes identifiers that do not need quotes ({@code "TRADE"} = {@code TRADE}),</li>
 *   <li>drops the schema's own name as a prefix ({@code SAMPLE_A.TRADE} = {@code TRADE}), so two databases with
 *       different schema names compare equal,</li>
 *   <li>collapses whitespace, keeping a space only where removing it would join two tokens,</li>
 *   <li>strips a leading {@code CREATE [OR REPLACE] [[NON]EDITIONABLE] [FORCE]} header.</li>
 * </ul>
 * Line endings, trailing whitespace, blank lines and line breaks themselves do not matter. String literals
 * ({@code '...'}, {@code q'[...]'}) are kept exactly. Each canonical line remembers its original line number, so a
 * difference can be reported where it is in the source.
 */
public final class SourceNormalizer {

    /** One non-empty canonical line and its 1-based line number in the original source. */
    public record Line(int number, String text) { }

    /** The first line where two sources differ; a text is {@code "(end)"} where one source is shorter. */
    public record FirstDifference(int lineA, String textA, int lineB, String textB) { }

    public static final int EXCERPT_CHARS = 80;
    private static final Pattern PLAIN_IDENT = Pattern.compile("^[A-Z][A-Z0-9_$#]*$");
    private static final Pattern HEADER = Pattern.compile(
            "^CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:(?:NON)?EDITIONABLE\\s+)?(?:FORCE\\s+)?(?:NO\\s+FORCE\\s+)?");

    private SourceNormalizer() { }

    public static List<Line> normalize(String source, String owner) {
        List<Line> out = new ArrayList<>();
        if (source == null || source.isEmpty()) return out;
        String s = source.replace("\r\n", "\n").replace('\r', '\n');
        Pattern ownerPrefix = owner == null || owner.isBlank() ? null
                : Pattern.compile("(?<![A-Z0-9_$#])" + Pattern.quote(owner.toUpperCase(Locale.ROOT)) + "\\s*\\.\\s*");

        StringBuilder line = new StringBuilder();  // canonical text of the current line
        StringBuilder code = new StringBuilder();  // pending code (outside literals) not yet flushed into line
        boolean space = false;                     // whitespace seen since the last emitted character
        int lineNo = 1;
        int startLine = 1;                         // line on which the current canonical line started
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\n') {
                flush(code, line, ownerPrefix);
                finishLine(out, line, startLine);
                lineNo++;
                startLine = lineNo;
                space = false;
                i++;
            } else if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                while (i < n && s.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                for (int k = i; k < end; k++) {
                    if (s.charAt(k) == '\n') {
                        flush(code, line, ownerPrefix);
                        finishLine(out, line, startLine);
                        lineNo++;
                        startLine = lineNo;
                    }
                }
                space = true;
                i = end;
            } else if (Character.isWhitespace(c)) {
                space = true;
                i++;
            } else if (c == '\'' || ((c == 'q' || c == 'Q') && i + 2 < n && s.charAt(i + 1) == '\''
                    && (space || !isWord(prev(code, line))))) {
                // String literal, kept exactly. q'X...X' uses a custom delimiter (bracket pairs close with their mate).
                int start = i;
                int end;
                if (c == '\'') {
                    end = i + 1;
                    while (end < n) {
                        if (s.charAt(end) == '\'') {
                            if (end + 1 < n && s.charAt(end + 1) == '\'') { end += 2; continue; }
                            break;
                        }
                        end++;
                    }
                } else {
                    char open = s.charAt(i + 2);
                    char close = switch (open) { case '[' -> ']'; case '(' -> ')'; case '{' -> '}'; case '<' -> '>'; default -> open; };
                    int at = s.indexOf(close + "'", i + 3);
                    end = at < 0 ? n - 1 : at + 1;
                }
                end = Math.min(end, n - 1);
                emitSpace(code, line, space, '\'');
                space = false;
                flush(code, line, ownerPrefix);
                String literal = s.substring(start, end + 1);
                if (literal.charAt(0) != '\'') literal = "Q" + literal.substring(1);
                lineNo += count(literal, '\n');
                line.append(literal.replace('\n', ' '));
                i = end + 1;
            } else if (c == '"') {
                int end = s.indexOf('"', i + 1);
                if (end < 0) end = n - 1;
                String ident = s.substring(i + 1, end);
                String emitted = PLAIN_IDENT.matcher(ident).matches() ? ident : '"' + ident + '"';
                emitSpace(code, line, space, emitted.charAt(0));
                space = false;
                code.append(emitted);
                i = end + 1;
            } else {
                char u = Character.toUpperCase(c);
                emitSpace(code, line, space, u);
                space = false;
                code.append(u);
                i++;
            }
        }
        flush(code, line, ownerPrefix);
        finishLine(out, line, startLine);

        if (!out.isEmpty()) {
            Line first = out.get(0);
            String stripped = HEADER.matcher(first.text()).replaceFirst("");
            if (stripped.isEmpty()) out.remove(0);
            else out.set(0, new Line(first.number(), stripped));
        }
        return out;
    }

    /** All lines as one string, joined the same way whitespace inside a line is collapsed. */
    public static String joined(List<Line> lines) {
        StringBuilder b = new StringBuilder();
        for (Line l : lines) {
            if (b.length() > 0 && needsSpace(b.charAt(b.length() - 1), l.text().charAt(0))) b.append(' ');
            b.append(l.text());
        }
        return b.toString();
    }

    public static boolean sameSource(List<Line> a, List<Line> b) {
        return joined(a).equals(joined(b));
    }

    /** First differing canonical line, or null when the sources are the same. */
    public static FirstDifference firstDifference(List<Line> a, List<Line> b) {
        if (sameSource(a, b)) return null;
        int k = 0;
        while (k < a.size() && k < b.size() && a.get(k).text().equals(b.get(k).text())) k++;
        return new FirstDifference(
                k < a.size() ? a.get(k).number() : lastLine(a), k < a.size() ? excerpt(a.get(k).text()) : "(end)",
                k < b.size() ? b.get(k).number() : lastLine(b), k < b.size() ? excerpt(b.get(k).text()) : "(end)");
    }

    public static String excerpt(String s) {
        return s.length() <= EXCERPT_CHARS ? s : s.substring(0, EXCERPT_CHARS) + "…";
    }

    public static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 5);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int lastLine(List<Line> l) {
        return l.isEmpty() ? 0 : l.get(l.size() - 1).number();
    }

    private static void finishLine(List<Line> out, StringBuilder line, int number) {
        if (line.length() > 0) out.add(new Line(number, line.toString()));
        line.setLength(0);
    }

    /** Moves pending code into the line, dropping the schema's own name used as a prefix. */
    private static void flush(StringBuilder code, StringBuilder line, Pattern ownerPrefix) {
        if (code.length() == 0) return;
        String text = code.toString();
        if (ownerPrefix != null) {
            Matcher m = ownerPrefix.matcher(text);
            text = m.replaceAll("");
        }
        line.append(text);
        code.setLength(0);
    }

    private static char prev(StringBuilder code, StringBuilder line) {
        if (code.length() > 0) return code.charAt(code.length() - 1);
        return line.length() > 0 ? line.charAt(line.length() - 1) : ' ';
    }

    private static void emitSpace(StringBuilder code, StringBuilder line, boolean space, char next) {
        if (!space) return;
        char p = prev(code, line);
        if (p != ' ' && needsSpace(p, next)) code.append(' ');
    }

    /** A space is kept only where removing it would merge two tokens (two words, or two operator characters). */
    static boolean needsSpace(char a, char b) {
        if (isWord(a) && isWord(b)) return true;
        return isOperator(a) && isOperator(b);
    }

    private static boolean isWord(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#' || c == '\'' || c == '"';
    }

    private static boolean isOperator(char c) {
        return "+-*/<>=!|:.%@^~&?".indexOf(c) >= 0;
    }

    private static int count(String s, char c) {
        int k = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) k++;
        return k;
    }
}
