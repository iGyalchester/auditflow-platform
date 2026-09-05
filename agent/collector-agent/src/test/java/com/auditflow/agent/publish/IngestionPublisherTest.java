package com.auditflow.agent.publish;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.model.AuditEvent;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withAccepted;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

/**
 * Asserts on the JSON that actually leaves the agent, through a real
 * RestClient against a stubbed transport - not on a mock of our own
 * publisher.
 */
class IngestionPublisherTest {

    private static final Instant LOGGED_AT = Instant.parse("2026-01-02T03:04:05Z");

    private MockRestServiceServer server;
    private IngestionPublisher publisher;

    private void wire(String token) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        publisher = new IngestionPublisher(builder, "http://ingestion.test", token);
    }

    private static AuditEvent event(Instant loggedAt) {
        return AuditEvent.builder()
                .eventId("evt-1")
                .customerId("resistance")
                .userId("app_user")
                .type(EventType.DATABASE_QUERY)
                .resource("resistance-mysql")
                .action("SELECT")
                .query("SELECT * FROM job_application WHERE id = ?")
                .timestamp(loggedAt)
                .build();
    }

    /**
     * The whole point of the field. The agent polls, so what it reads now was
     * logged some time ago - a whole outage ago when it is catching up. Send
     * only the payload and ingestion stamps arrival time, which would date an
     * hour of recovered history to the minute we recovered it.
     */
    @Test
    void sendsTheEventTimestampAsOccurredAt() {
        wire("tok-1");
        server.expect(requestTo("http://ingestion.test/api/v1/events"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("X-Audit-Token", "tok-1"))
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.customerId").value("resistance"))
                // ISO-8601, not {"epochSecond":...}: this module has
                // jackson-databind but no JSR-310 module, so the publisher
                // formats the Instant itself. If that ever regresses, the
                // string form is what catches it.
                .andExpect(jsonPath("$.occurredAt").value("2026-01-02T03:04:05Z"))
                .andRespond(withAccepted());

        assertThat(publisher.publish(List.of(event(LOGGED_AT)))).isTrue();
        server.verify();
    }

    @Test
    void noTokenConfiguredMeansNoTokenHeader() {
        wire("");
        server.expect(requestTo("http://ingestion.test/api/v1/events"))
                .andExpect(headerDoesNotExist("X-Audit-Token"))
                .andRespond(withAccepted());

        assertThat(publisher.publish(List.of(event(LOGGED_AT)))).isTrue();
        server.verify();
    }

    /**
     * A rejected batch has to report false, because that is what makes the
     * runner hold its checkpoint and re-read the same rows next poll. Return
     * true here and the events are silently lost.
     */
    @Test
    void aRejectedEventReportsFailureSoTheCheckpointHolds() {
        wire("tok-1");
        server.expect(requestTo("http://ingestion.test/api/v1/events"))
                .andRespond(withServerError());

        assertThat(publisher.publish(List.of(event(LOGGED_AT)))).isFalse();
        server.verify();
    }
}
