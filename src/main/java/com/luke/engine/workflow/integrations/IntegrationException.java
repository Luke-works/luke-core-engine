package com.luke.engine.workflow.integrations;

/** A recoverable integration error (bad request, Nango failure). Mapped to HTTP 400. */
public class IntegrationException extends RuntimeException {
    public IntegrationException(String message) {
        super(message);
    }
}
