package se.caibirch.jobpilot.common;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain exceptions to JSON error bodies: {@code {"error": "..."}}. */
@RestControllerAdvice
public class ApiExceptions {

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(ApiExceptions.class);

    /** The browser tab behind an application is gone (closed by the user, or the browser was restarted). */
    @ExceptionHandler(se.caibirch.jobpilot.apply.BrowserService.TabClosedException.class)
    ResponseEntity<Map<String, String>> tabClosed(se.caibirch.jobpilot.apply.BrowserService.TabClosedException e) {
        return body(HttpStatus.CONFLICT, new RuntimeException("That browser tab is closed. Use Restart and refill to open it again."));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<Map<String, String>> conflict(ConflictException e) {
        return body(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, e);
    }

    /** Last resort: a JSON body with a readable message instead of a bare 500, so the UI can show what went wrong. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception e) throws Exception {
        if (e instanceof org.springframework.web.ErrorResponse || e instanceof org.springframework.web.HttpMediaTypeException
                || e instanceof org.springframework.web.bind.ServletRequestBindingException
                || e instanceof org.springframework.http.converter.HttpMessageNotReadableException) {
            throw e; // Spring's own 4xx handling
        }
        log.error("Unhandled error in a request", e);
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return body(HttpStatus.INTERNAL_SERVER_ERROR, new RuntimeException("Something went wrong: " + message));
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, Exception e) {
        return ResponseEntity.status(status).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}
