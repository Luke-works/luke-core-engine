package com.luke.engine.capability.email;

import java.util.Map;

/**
 * Published once a QUEUED {@link EmailMessage} row is persisted (#59). {@link EmailDispatcher}
 * consumes it <b>after the publishing transaction commits</b> and delivers off the request thread,
 * so the API returns as soon as the send is durably queued. Carries the fully-built Postmark wire
 * body so delivery needs no further request context.
 */
record EmailQueuedEvent(String messageId, String serverToken, Map<String, Object> body, boolean template) {}
