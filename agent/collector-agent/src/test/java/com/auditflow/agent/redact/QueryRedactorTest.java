package com.auditflow.agent.redact;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryRedactorTest {

    @Test
    void stripsStringLiteralsIncludingPii() {
        String redacted = QueryRedactor.redact(
                "SELECT * FROM user_account WHERE email = 'boris@gmail.com' AND phone = '224-249-0942'");

        assertThat(redacted)
                .doesNotContain("boris@gmail.com")
                .doesNotContain("224")
                .isEqualTo("SELECT * FROM user_account WHERE email = ? AND phone = ?");
    }

    @Test
    void handlesEscapedQuotesInsideLiterals() {
        assertThat(QueryRedactor.redact("SELECT 1 FROM t WHERE name = 'O''Brien' OR name = 'a\\'b'"))
                .doesNotContain("Brien")
                .isEqualTo("SELECT ? FROM t WHERE name = ? OR name = ?");
    }

    @Test
    void stripsNumericLiteralsButNotIdentifiers() {
        assertThat(QueryRedactor.redact("SELECT c1 FROM table2 WHERE id = 42 AND score > 3.14"))
                .isEqualTo("SELECT c1 FROM table2 WHERE id = ? AND score > ?");
    }

    @Test
    void collapsesWhitespaceAndCapsLength() {
        assertThat(QueryRedactor.redact("SELECT *\n   FROM   t")).isEqualTo("SELECT * FROM t");
        String longSql = "SELECT " + "x, ".repeat(400) + "y FROM t";
        assertThat(QueryRedactor.redact(longSql).length()).isLessThanOrEqualTo(501);
    }
}
