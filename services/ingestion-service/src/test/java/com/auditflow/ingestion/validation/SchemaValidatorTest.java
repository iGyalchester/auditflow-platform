package com.auditflow.ingestion.validation;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.model.AuditEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchemaValidatorTest {

    private final SchemaValidator validator = new SchemaValidator();

    @Test
    void anEventMayOnlyClaimTheTenantItsTokenIsBoundTo() {
        AuditEvent forCustomer1 = AuditEvent.builder()
                .eventId("evt-1").customerId("cust-1")
                .type(EventType.API_CALL).timestamp(Instant.now()).build();

        assertDoesNotThrow(() -> validator.validate(forCustomer1, "cust-1"));
        // no bound tenant (ingestion running open) imposes no restriction
        assertDoesNotThrow(() -> validator.validate(forCustomer1, null));

        TenantMismatchException mismatch = assertThrows(TenantMismatchException.class,
                () -> validator.validate(forCustomer1, "someone-else"));
        assertEquals("someone-else", mismatch.boundCustomerId());
        assertEquals("cust-1", mismatch.claimedCustomerId());
    }

    @Test
    void acceptsWellFormedEvent() {
        AuditEvent event = AuditEvent.builder()
                .eventId("evt-1")
                .customerId("cust-1")
                .type(EventType.API_CALL)
                .timestamp(Instant.now())
                .build();

        assertDoesNotThrow(() -> validator.validate(event));
    }

    @Test
    void rejectsEventWithoutCustomerId() {
        AuditEvent blankCustomer = AuditEvent.builder()
                .eventId("evt-1")
                .customerId(" ")
                .type(EventType.API_CALL)
                .timestamp(Instant.now())
                .build();

        assertThrows(IllegalArgumentException.class, () -> validator.validate(blankCustomer));
    }
}
