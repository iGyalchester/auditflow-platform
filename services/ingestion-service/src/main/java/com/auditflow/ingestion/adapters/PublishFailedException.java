package com.auditflow.ingestion.adapters;

/**
 * Kafka did not acknowledge the event within the delivery timeout. The
 * caller must not be told "accepted": the HTTP layer maps this to 503 so
 * the source retries (the collector agent holds its checkpoint on any
 * non-2xx and re-sends the batch).
 */
public class PublishFailedException extends RuntimeException {

    public PublishFailedException(String eventId, Throwable cause) {
        super("Kafka did not acknowledge event " + eventId + ": " + cause.getMessage(), cause);
    }
}
