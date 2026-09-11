package com.auditflow.common.ratelimit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientKeyResolverTest {

    private static final String REMOTE = "10.0.0.5";

    @Test
    void withNoTrustedHeaderTheSocketAddressIsTheClient() {
        ClientKeyResolver resolver = new ClientKeyResolver(null);

        assertThat(resolver.trustedHeader()).isNull();
        assertThat(resolver.resolve("203.0.113.7", REMOTE)).isEqualTo(REMOTE);
        assertThat(new ClientKeyResolver("  ").resolve("203.0.113.7", REMOTE)).isEqualTo(REMOTE);
    }

    @Test
    void aConfiguredHeaderIsUsedWhenItCarriesOneAddress() {
        ClientKeyResolver resolver = new ClientKeyResolver("X-Client-IP");

        assertThat(resolver.trustedHeader()).isEqualTo("X-Client-IP");
        assertThat(resolver.resolve("203.0.113.7", REMOTE)).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve("  203.0.113.7  ", REMOTE)).isEqualTo("203.0.113.7");
    }

    @Test
    void aMissingOrEmptyHeaderFallsBackToTheSocket() {
        ClientKeyResolver resolver = new ClientKeyResolver("X-Client-IP");

        assertThat(resolver.resolve(null, REMOTE)).isEqualTo(REMOTE);
        assertThat(resolver.resolve("", REMOTE)).isEqualTo(REMOTE);
        assertThat(resolver.resolve("   ", REMOTE)).isEqualTo(REMOTE);
    }

    @Test
    void aCommaListIsRefusedBecauseSomethingAppendedToIt() {
        // X-Forwarded-For shaped. Every hop appends, so the leading entry is
        // whatever the client sent: rotate it and you are never limited,
        // forge somebody else's and they are limited instead.
        ClientKeyResolver resolver = new ClientKeyResolver("X-Client-IP");

        assertThat(resolver.resolve("203.0.113.7, 10.0.0.9", REMOTE)).isEqualTo(REMOTE);
        assertThat(resolver.resolve("evil, 203.0.113.7", REMOTE)).isEqualTo(REMOTE);
    }
}
