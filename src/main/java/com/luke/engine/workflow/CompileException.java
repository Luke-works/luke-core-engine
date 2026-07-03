package com.luke.engine.workflow;

/** Thrown when a workflow document cannot be compiled to valid BPMN. */
public class CompileException extends RuntimeException {
    public CompileException(String message) {
        super(message);
    }
}
