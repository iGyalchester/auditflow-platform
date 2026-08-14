package com.auditflow.gateway.controllers;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Verifies the gateway proxies report requests to reporting-service and
 * passes responses (including downstream errors) through unchanged. The
 * downstream HTTP boundary is faked with MockRestServiceServer; the real
 * gateway-to-reporting round trip is exercised by running both services.
 */
class ReportControllerTest {

    private static final String BASE_URL = "http://reporting.test:8084";

    @Test
    void proxiesReportRequestDownstream() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReportController controller = new ReportController(builder, BASE_URL);

        server.expect(requestToUriTemplate(
                        BASE_URL + "/api/v1/reports?customerId={c}&framework={f}&from={from}&to={to}",
                        "cust-1", "SOC2", "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z"))
                .andExpect(queryParam("framework", "SOC2"))
                .andRespond(withSuccess("SOC 2 Evidence Report", MediaType.TEXT_PLAIN));

        ResponseEntity<byte[]> response = controller.generate(
                "cust-1", "SOC2", "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(response.getBody())).contains("SOC 2 Evidence Report");
        server.verify();
    }

    @Test
    void passesDownstreamErrorsThrough() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ReportController controller = new ReportController(builder, BASE_URL);

        server.expect(requestToUriTemplate(
                        BASE_URL + "/api/v1/reports?customerId={c}&framework={f}&from={from}&to={to}",
                        "cust-1", "PCI", "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z"))
                .andRespond(withBadRequest());

        ResponseEntity<byte[]> response = controller.generate(
                "cust-1", "PCI", "2026-08-01T00:00:00Z", "2026-08-02T00:00:00Z");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        server.verify();
    }
}
