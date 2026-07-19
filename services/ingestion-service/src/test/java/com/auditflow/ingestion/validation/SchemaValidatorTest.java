package com.auditflow.ingestion.validation;

import com.auditflow.common.enums.EventType;
import com.auditflow.common.model.AuditEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SchemaValidatorTest {

    private final SchemaValidator validator = new SchemaValidator();

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
