package com.auditflow.gateway.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeWindowTest {

    private static final Duration WEEK = Duration.ofDays(7);
    private static final Duration YEAR = Duration.ofDays(366);

    @Test
    void defaultsEndToNowAndStartToTheDefaultLengthBefore() {
        TimeWindow window = TimeWindow.resolve(null, null, WEEK, YEAR);
        assertThat(window.length()).isEqualTo(WEEK);
        assertThat(window.to()).isBetween(Instant.now().minusSeconds(5), Instant.now());
    }

    @Test
    void anExplicitEndStillDefaultsTheStart() {
        Instant to = Instant.parse("2026-09-08T00:00:00Z");
        assertThat(TimeWindow.resolve(null, to, WEEK, YEAR)).isEqualTo(new TimeWindow(to.minus(WEEK), to));
    }

    @Test
    void invertedOrEmptyWindowsAre400() {
        Instant t = Instant.parse("2026-09-08T00:00:00Z");
        assertThatThrownBy(() -> TimeWindow.resolve(t, t, WEEK, YEAR)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> TimeWindow.resolve(t.plusSeconds(1), t, WEEK, YEAR))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("before");
    }

    @Test
    void windowsOverTheCapAre400() {
        Instant t = Instant.parse("2026-09-08T00:00:00Z");
        assertThat(TimeWindow.resolve(t.minus(YEAR), t, WEEK, YEAR).length()).isEqualTo(YEAR);
        assertThatThrownBy(() -> TimeWindow.resolve(t.minus(YEAR).minusSeconds(1), t, WEEK, YEAR))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("366 days");
    }

    @Test
    void previousIsTheSameLengthEndingAtTheStart() {
        Instant t = Instant.parse("2026-09-08T00:00:00Z");
        TimeWindow window = new TimeWindow(t.minus(WEEK), t);
        assertThat(window.previous()).isEqualTo(new TimeWindow(t.minus(WEEK).minus(WEEK), t.minus(WEEK)));
    }
}
