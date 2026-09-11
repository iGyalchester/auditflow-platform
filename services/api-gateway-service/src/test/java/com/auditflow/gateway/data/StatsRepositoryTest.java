package com.auditflow.gateway.data;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure parts: which days a window covers, and the risk stack's fixed shape. */
class StatsRepositoryTest {

    @Test
    void daysAreUtcDaysAndTheEndIsExclusive() {
        assertThat(StatsRepository.days(Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-04T00:00:00Z")))
                .containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 3));
        // a window ending mid-day includes that day
        assertThat(StatsRepository.days(Instant.parse("2026-09-01T23:00:00Z"), Instant.parse("2026-09-02T01:00:00Z")))
                .containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2));
        assertThat(StatsRepository.days(Instant.parse("2026-09-01T10:00:00Z"), Instant.parse("2026-09-01T11:00:00Z")))
                .containsExactly(LocalDate.of(2026, 9, 1));
    }

    @Test
    void everyRiskLevelIsPresentInOrderAndUnknownsAreKept() {
        assertThat(StatsRepository.withEveryRisk(Map.of("HIGH", 2L, "UNKNOWN", 1L)))
                .containsExactly(Map.entry("LOW", 0L), Map.entry("MEDIUM", 0L), Map.entry("HIGH", 2L),
                        Map.entry("CRITICAL", 0L), Map.entry("UNKNOWN", 1L));
    }
}
