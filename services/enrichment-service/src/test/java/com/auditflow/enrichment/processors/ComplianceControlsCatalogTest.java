package com.auditflow.enrichment.processors;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.model.ComplianceControl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplianceControlsCatalogTest {

    private final ComplianceControlsCatalog catalog = new ComplianceControlsCatalog();

    @Test
    void loadsAllThreeFrameworksFromYaml() {
        assertThat(catalog.controlsFor(EventType.DATA_EXPORT))
                .extracting(ComplianceControl::getFramework)
                .contains("SOC2", "GDPR", "HIPAA");
    }

    @Test
    void mapsDataExportToExpectedControls() {
        assertThat(catalog.controlsFor(EventType.DATA_EXPORT))
                .extracting(ComplianceControl::getControlId)
                .containsExactlyInAnyOrder("AU-2", "Art-30", "164.312(b)");
    }

    @Test
    void controlsCarryNamesAndDescriptions() {
        assertThat(catalog.controlsFor(EventType.PERMISSION_CHANGE))
                .allSatisfy(control -> {
                    assertThat(control.getName()).isNotBlank();
                    assertThat(control.getDescription()).isNotBlank();
                });
    }

    @Test
    void failsFastWhenNoControlFilesFound() {
        assertThatThrownBy(() -> new ComplianceControlsCatalog("classpath*:no-such-dir/*.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No compliance control files");
    }
}
