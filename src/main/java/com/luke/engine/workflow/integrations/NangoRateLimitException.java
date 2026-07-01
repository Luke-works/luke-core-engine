package com.luke.engine.workflow.integrations;

/** See {@link NangoExceptions}. */
public class NangoRateLimitException extends NangoException {
    public NangoRateLimitException(String message) {
        super(message);
    }
}
