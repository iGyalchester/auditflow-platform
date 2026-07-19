package com.auditflow.common.interfaces;

import com.auditflow.common.model.AuditEvent;

import java.util.List;

/**
 * A destination for persisted audit events, decoupled from any specific
 * storage technology. Business logic depends only on this interface, never
 * on a concrete cloud SDK, so the backing store (S3, a local filesystem,
 * another object store) can be swapped without touching callers.
 */
public interface DataSink {

    void write(AuditEvent event);

    void writeBatch(List<AuditEvent> events);

    String sinkName();
}
