package com.auditflow.ingestion.validation;

/**
 * The presented token belongs to one tenant and the event claims another.
 * A 403 rather than a 400: the request is well formed and authenticated,
 * it is simply not allowed to write as that customer.
 */
public class TenantMismatchException extends RuntimeException {

    private final String boundCustomerId;
    private final String claimedCustomerId;

    public TenantMismatchException(String boundCustomerId, String claimedCustomerId) {
        super("token is bound to customer '" + boundCustomerId
                + "' but the event claims '" + claimedCustomerId + "'");
        this.boundCustomerId = boundCustomerId;
        this.claimedCustomerId = claimedCustomerId;
    }

    public String boundCustomerId() {
        return boundCustomerId;
    }

    public String claimedCustomerId() {
        return claimedCustomerId;
    }
}
