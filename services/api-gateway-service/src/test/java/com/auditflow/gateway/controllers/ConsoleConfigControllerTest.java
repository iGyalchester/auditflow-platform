package com.auditflow.gateway.controllers;

import com.auditflow.gateway.security.AuthProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleConfigControllerTest {

    @Test
    void openModePublishesOnlyTheFlag() {
        ConsoleConfigController.ConsoleConfig config = new ConsoleConfigController(
                new AuthProperties(false, "https://issuer", "", "client", "https://ui")).config();
        assertThat(config.authEnabled()).isFalse();
        assertThat(config.issuerUri()).isNull();
        assertThat(config.clientId()).isNull();
    }

    @Test
    void enforcedModePublishesWhereToSignIn() {
        ConsoleConfigController.ConsoleConfig config = new ConsoleConfigController(
                new AuthProperties(true, "https://issuer", "", "client", "https://ui")).config();
        assertThat(config.authEnabled()).isTrue();
        assertThat(config.issuerUri()).isEqualTo("https://issuer");
        assertThat(config.clientId()).isEqualTo("client");
        assertThat(config.hostedUiDomain()).isEqualTo("https://ui");
    }
}
