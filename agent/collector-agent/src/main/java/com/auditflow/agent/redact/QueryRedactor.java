package com.auditflow.agent.redact;

/**
 * Strips data out of SQL before it leaves the database host. A query log
 * is full of PII - emails in WHERE clauses, phone numbers in INSERTs -
 * and an audit trail must never become the leak it exists to detect. So
 * the agent keeps the *shape* of every statement (which tables, which
 * operations) and replaces every literal with '?', client-side, before
 * anything is published.
 */
public final class QueryRedactor {

    private static final int MAX_LENGTH = 500;

    private QueryRedactor() {
    }

    public static String redact(String sql) {
        if (sql == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                // consume the quoted literal, honouring '' and \' escapes
                char quote = c;
                i++;
                while (i < n) {
                    char q = sql.charAt(i);
                    if (q == '\\' && i + 1 < n) {
                        i += 2;
                        continue;
                    }
                    if (q == quote) {
                        if (i + 1 < n && sql.charAt(i + 1) == quote) {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                out.append('?');
                continue;
            }
            if (Character.isDigit(c) && (out.isEmpty() || !isIdentifierChar(out.charAt(out.length() - 1)))) {
                // a numeric literal (but not the 2 in "table2")
                while (i < n && (Character.isDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
                    i++;
                }
                out.append('?');
                continue;
            }
            out.append(Character.isWhitespace(c) ? ' ' : c);
            i++;
        }
        String collapsed = out.toString().replaceAll(" {2,}", " ").trim();
        return collapsed.length() > MAX_LENGTH ? collapsed.substring(0, MAX_LENGTH) + "…" : collapsed;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
