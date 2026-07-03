package com.luke.engine.workflow.integrations;

/** Base type for a Nango call failure. See {@link NangoExceptions} for the taxonomy. */
public class NangoException extends RuntimeException {
    public NangoException(String message) {
        super(message);
    }
}
