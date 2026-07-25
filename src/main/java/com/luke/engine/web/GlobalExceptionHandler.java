package com.luke.engine.web;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The engine's single error boundary for the {@code /api/**} surface (#63).
 *
 * <p>Before this, errors surfaced three inconsistent ways — controller {@code ResponseStatusException}
 * reasons, hand-rolled JSON in the auth filters, and Spring's default Whitelabel for anything
 * uncaught (which can leak an internal exception message/stack). This maps them all to one
 * {@link ApiError} shape with a correlation id.
 *
 * <p>Two rules:
 * <ol>
 *   <li><b>One shape.</b> {@code {error, message, status, correlationId}} everywhere.</li>
 *   <li><b>Don't leak.</b> A {@code ResponseStatusException} carries a developer-authored reason
 *       (safe to surface, unchanged behaviour); any <em>unexpected</em> exception is logged with
 *       the correlation id and answered with a generic 500 message — never the raw exception.</li>
 * </ol>
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} keeps Spring MVC's own exceptions (unreadable
 * body → 400, wrong method → 405, missing param, …) mapped to their correct status instead of the
 * catch-all 500; {@link #handleExceptionInternal} re-shapes those bodies into the {@link ApiError}
 * form. More specific, controller-scoped advices (e.g. {@code SignatureExceptionAdvice}) still win
 * for their controllers.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** {@code ResponseStatusException} (and {@code ErrorResponseException}) — the intended,
     *  developer-authored status + reason. Re-shaped, reason passed through. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Object> onResponseStatus(ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();
        String reason = ex.getReason() != null ? ex.getReason() : defaultReason(status);
        return ResponseEntity.status(status).body(ApiError.body(status.value(), titleFor(status), reason));
    }

    /** Last resort: log the whole thing server-side, tell the client nothing specific. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> onUnexpected(Exception ex) {
        log.error("Unhandled exception [cid={}]", ApiError.correlationId(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.body(500, "Internal Server Error", "An unexpected error occurred."));
    }

    /** Re-shape the bodies Spring MVC builds for its own exceptions into the {@link ApiError} form. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
                                                             HttpHeaders headers, HttpStatusCode status,
                                                             WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, status, request);
        HttpStatusCode effective = response != null ? response.getStatusCode() : status;
        String message = defaultReason(effective);
        Object original = response != null ? response.getBody() : null;
        if (original instanceof ProblemDetail pd && pd.getDetail() != null) {
            message = pd.getDetail(); // Spring's detail is developer/framework text, safe to surface
        } else if (ex instanceof ErrorResponseException ere && ere.getBody().getDetail() != null) {
            message = ere.getBody().getDetail();
        }
        Map<String, Object> reshaped = ApiError.body(effective.value(), titleFor(effective), message);
        return ResponseEntity.status(effective).headers(headers).body(reshaped);
    }

    private static String titleFor(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved != null ? resolved.getReasonPhrase() : "Error";
    }

    private static String defaultReason(HttpStatusCode status) {
        return status.is5xxServerError() ? "An unexpected error occurred." : "Request could not be processed.";
    }
}
