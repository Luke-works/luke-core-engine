package com.luke.engine.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** For an event-mode wait node: the inbound event to correlate on. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WaitEvent(String capability, String type, String correlationKey) {
}
