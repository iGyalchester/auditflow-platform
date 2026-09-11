package com.auditflow.common.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ComplianceControlsTest {

    @Test
    void roundTripsFrameworkAndId() {
        List<ComplianceControl> controls = List.of(
                ComplianceControl.builder().framework("SOC2").controlId("AC-2").name("Account Management").build(),
                ComplianceControl.builder().framework("GDPR").controlId("Art-30").build());

        String encoded = ComplianceControls.encode(controls);
        assertThat(encoded).isEqualTo("SOC2:AC-2,GDPR:Art-30");
        assertThat(ComplianceControls.decode(encoded)).extracting(ComplianceControl::getFramework, ComplianceControl::getControlId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("SOC2", "AC-2"), org.assertj.core.groups.Tuple.tuple("GDPR", "Art-30"));
    }

    @Test
    void emptyAndMalformedInputsAreHarmless() {
        assertThat(ComplianceControls.encode(null)).isNull();
        assertThat(ComplianceControls.encode(List.of())).isNull();
        assertThat(ComplianceControls.decode(null)).isEmpty();
        assertThat(ComplianceControls.decode("")).isEmpty();
        assertThat(ComplianceControls.decode("junk,:x,y:,SOC2:AC-2")).hasSize(1);
    }
}
