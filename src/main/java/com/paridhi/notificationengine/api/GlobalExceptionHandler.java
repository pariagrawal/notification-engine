package com.paridhi.notificationengine.api;

import com.paridhi.notificationengine.api.dto.ApiError;
import com.paridhi.notificationengine.exception.DuplicateInFlightException;
import com.paridhi.notificationengine.exception.InvalidRequestException;
import com.paridhi.notificationengine.exception.NotFoundException;
import com.paridhi.notificationengine.exception.RateLimitExceededException;
import com.paridhi.notificationengine.exception.TemplateRenderException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps the engine's exceptions onto stable HTTP status codes and error codes. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of(404, "NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> handleInvalid(InvalidRequestException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of(422, "UNPROCESSABLE", ex.getMessage()));
    }

    @ExceptionHandler(TemplateRenderException.class)
    public ResponseEntity<ApiError> handleTemplate(TemplateRenderException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiError.of(422, "TEMPLATE_RENDER_FAILED", ex.getMessage()));
    }

    @ExceptionHandler(DuplicateInFlightException.class)
    public ResponseEntity<ApiError> handleInFlight(DuplicateInFlightException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(409, "DUPLICATE_IN_FLIGHT", ex.getMessage()));
    }

    /** Includes {@code Retry-After} so a well-behaved client can back off correctly. */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimit(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, ex.getRetryAfter().toSeconds())))
                .body(ApiError.of(429, "RATE_LIMITED", ex.getMessage(),
                        Map.of("limit", String.valueOf(ex.getLimit()))));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> details = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            details.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "VALIDATION_FAILED", "request validation failed", details));
    }

    /**
     * A body Jackson cannot bind — malformed JSON, or a value outside an enum's range.
     * Without this the catch-all below would report the caller's mistake as a 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.debug("rejecting unreadable request body", ex);
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "MALFORMED_REQUEST", "request body could not be parsed"));
    }

    /** A path variable or query parameter of the wrong type, e.g. a non-UUID id. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "VALIDATION_FAILED", "invalid value for '%s'".formatted(ex.getName())));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleParameterValidation(HandlerMethodValidationException ex) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "VALIDATION_FAILED", "request validation failed"));
    }

    /**
     * Last resort. Spring's own client errors (unsupported media type, wrong method,
     * missing parameter, unknown path, and {@code ResponseStatusException}) implement
     * {@link ErrorResponse} and carry their real status; they are the caller's mistake,
     * not a 500, and keep headers such as {@code Allow}.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse framework && framework.getStatusCode().is4xxClientError()) {
            HttpStatusCode status = framework.getStatusCode();
            HttpStatus known = HttpStatus.resolve(status.value());
            String detail = framework.getBody().getDetail();
            String message = detail != null ? detail : known != null ? known.getReasonPhrase() : "request rejected";
            log.debug("rejecting request with {}: {}", status.value(), message);
            return ResponseEntity.status(status)
                    .headers(framework.getHeaders())
                    .body(ApiError.of(status.value(), known != null ? known.name() : "CLIENT_ERROR", message));
        }
        log.error("unhandled error serving request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of(500, "INTERNAL_ERROR", "something went wrong"));
    }
}
