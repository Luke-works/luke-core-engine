package com.luke.engine.workflow.integrations;

/** A tenant has hit its active-connection quota. Mapped to HTTP 409. */
public class QuotaExceededException extends IntegrationException {
    public QuotaExceededException(String message) {
        super(message);
    }
}
