package com.luke.engine.capability.signature;

import java.util.Map;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * Shared exception mapping for BOTH signature controllers (authed + public). Scoped to the two
 * controllers so it does not affect unrelated endpoints. Crucially this gives the PUBLIC sign path
 * the optimistic-lock→409 mapping too: concurrent POSTs to the same token (untrusted, retry-happy
 * clients) collide on the {@code @Version} guard and must surface as 409, not a generic 500.
 */
@RestControllerAdvice(assignableTypes = {
    SignatureController.class,
    PublicSignatureController.class,
    SignatureDefinitionController.class,
    SignatureInstanceController.class,
    PublicInstanceSignController.class
})
public class SignatureExceptionAdvice {

    /** {@code ObjectOptimisticLockingFailureException} extends this — concurrent write → 409. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> onConcurrentEdit() {
        return Map.of("error", "Conflict", "message", "The request was modified concurrently; retry");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Map<String, Object> onTooLarge() {
        return Map.of("error", "Payload Too Large", "message", "The PDF exceeds the maximum upload size");
    }
}
