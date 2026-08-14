package com.auditflow.reporting.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportingControlsCatalogTest {

    @Test
    void loadsAllControlsFromClasspath() {
        List<ControlDocument> documents = new ReportingControlsCatalog().documents();

        assertThat(documents).hasSizeGreaterThanOrEqualTo(9);
        assertThat(documents).extracting(ControlDocument::framework)
                .contains("SOC2", "GDPR", "HIPAA");
        assertThat(documents).anySatisfy(doc -> {
            assertThat(doc.controlId()).isEqualTo("AC-2");
            assertThat(doc.description()).isNotBlank();
        });
    }

    @Test
    void embeddingTextIncludesFrameworkAndEventTypes() {
        ControlDocument doc = new ControlDocument("AC-2", "SOC2", "Account Management",
                "Accounts are managed.", List.of("DATABASE_QUERY", "AUTH_EVENT"));

        assertThat(doc.embeddingText())
                .contains("SOC2")
                .contains("AC-2")
                .contains("Account Management")
                .contains("DATABASE_QUERY, AUTH_EVENT");
    }
}
