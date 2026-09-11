package com.auditflow.ingestion.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantTokensTest {

    @Test
    void blankSpecIsOpen() {
        assertThat(TenantTokens.parse(null).isOpen()).isTrue();
        assertThat(TenantTokens.parse("").isOpen()).isTrue();
        assertThat(TenantTokens.parse("   ").isOpen()).isTrue();
    }

    @Test
    void eachTokenResolvesToItsOwnTenant() {
        TenantTokens tokens = TenantTokens.parse("resistance=tok-a, acme = tok-b ");

        assertThat(tokens.isOpen()).isFalse();
        assertThat(tokens.tenants()).containsExactly("resistance", "acme");
        assertThat(tokens.resolve("tok-a")).contains("resistance");
        assertThat(tokens.resolve("tok-b")).contains("acme");
    }

    @Test
    void anUnknownOrMissingTokenResolvesToNobody() {
        TenantTokens tokens = TenantTokens.parse("resistance=tok-a");

        assertThat(tokens.resolve("tok-b")).isEmpty();
        assertThat(tokens.resolve("")).isEmpty();
        assertThat(tokens.resolve(null)).isEmpty();
        // a prefix of a real token is not a real token
        assertThat(tokens.resolve("tok-")).isEmpty();
    }

    @Test
    void onlyTheFirstEqualsSplitsSoBase64TokensSurvive() {
        TenantTokens tokens = TenantTokens.parse("acme=YWJjZGVmZw==");

        assertThat(tokens.resolve("YWJjZGVmZw==")).contains("acme");
    }

    @Test
    void malformedConfigurationFailsAtStartupNotAtRequestTime() {
        assertThatThrownBy(() -> TenantTokens.parse("no-separator"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not tenant=token");
        assertThatThrownBy(() -> TenantTokens.parse("=tok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank tenant or token");
        assertThatThrownBy(() -> TenantTokens.parse("acme="))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank tenant or token");
        assertThatThrownBy(() -> TenantTokens.parse("acme=a,acme=b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twice");
    }

    @Test
    void aMalformedEntryNeverPutsTheSecretInTheMessage() {
        assertThatThrownBy(() -> TenantTokens.parse("acme="))
                .hasMessageContaining("acme=")
                .hasMessageNotContaining("s3cret");
        assertThatThrownBy(() -> TenantTokens.parse("=s3cret"))
                .hasMessageNotContaining("s3cret");
    }
}
