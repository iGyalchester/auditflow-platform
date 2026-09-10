package com.auditflow.gateway.controllers;

import com.auditflow.gateway.api.Stats;
import com.auditflow.gateway.data.StatsRepository;
import com.auditflow.gateway.security.CurrentCustomer;
import com.auditflow.gateway.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StatsController.class)
@Import({SecurityConfig.class, CurrentCustomer.class, RequestScope.class})
class StatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StatsRepository repository;

    @Test
    void returnsTheDashboardForTheWindowAsked() throws Exception {
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-03T00:00:00Z");
        when(repository.stats("acme", new TimeWindow(from, to))).thenReturn(new Stats(new Stats.Window(from, to),
                new Stats.Totals(12, 2, 1, 3, 4), new Stats.Totals(6, 0, 0, 0, 2),
                List.of(new Stats.DayBucket(LocalDate.of(2026, 9, 1), 12, 2, Map.of("LOW", 12L))),
                Map.of("AUTH_EVENT", 12L), Map.of("LOW", 12L), Map.of("SOC2:AC-2", 12L),
                List.of(new Stats.NameCount("boris", 12)), List.of()));

        mockMvc.perform(get("/api/v1/stats").header("X-Customer-Id", "acme")
                        .param("from", from.toString()).param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totals.events").value(12))
                .andExpect(jsonPath("$.previous.events").value(6))
                .andExpect(jsonPath("$.perDay[0].day").value("2026-09-01"))
                .andExpect(jsonPath("$.perDay[0].byRisk.LOW").value(12))
                .andExpect(jsonPath("$.topUsers[0].name").value("boris"));
    }

    @Test
    void defaultsToTheLastSevenDays() throws Exception {
        mockMvc.perform(get("/api/v1/stats").header("X-Customer-Id", "acme")).andExpect(status().isOk());

        ArgumentCaptor<TimeWindow> window = ArgumentCaptor.forClass(TimeWindow.class);
        verify(repository).stats(eq("acme"), window.capture());
        assertThat(window.getValue().length()).isEqualTo(Duration.ofDays(7));
        assertThat(window.getValue().to()).isBetween(Instant.now().minusSeconds(5), Instant.now());
    }

    @Test
    void invertedAndOverlongWindowsAre400() throws Exception {
        mockMvc.perform(get("/api/v1/stats").header("X-Customer-Id", "acme")
                        .param("from", "2026-09-03T00:00:00Z").param("to", "2026-09-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("bad_request"));
        mockMvc.perform(get("/api/v1/stats").header("X-Customer-Id", "acme")
                        .param("from", "2024-01-01T00:00:00Z").param("to", "2026-09-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("366 days")));
        verify(repository, org.mockito.Mockito.never()).stats(any(), any());
    }

    @Test
    void noCustomerIs400() throws Exception {
        mockMvc.perform(get("/api/v1/stats")).andExpect(status().isBadRequest());
        verifyNoInteractions(repository);
    }
}
