package dev.floresm.transactions.api.error;

import dev.floresm.transactions.domain.exception.IdempotencyKeyReuseException;
import dev.floresm.transactions.domain.exception.NotFoundException;
import dev.floresm.transactions.domain.money.InvalidAmountException;
import dev.floresm.transactions.service.TransactionHistoryService.InvalidCursorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One place that turns exceptions into HTTP responses, so every error has the same shape.
 *
 * <p>The shape is <b>RFC 9457 "Problem Details for HTTP APIs"</b> ({@code application/problem+json}):
 * <pre>
 * { "type": "https://.../errors/not-found", "title": "Not Found", "status": 404,
 *   "detail": "Card 3f2... not found", "instance": "/api/v1/cards/3f2...", "code": "NOT_FOUND" }
 * </pre>
 * A standard means clients and tools already know how to read it. {@code code} is our stable,
 * machine-readable identifier; {@code detail} is for humans and may change.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} gives Problem Details for all of Spring MVC's
 * own exceptions (malformed JSON, missing header, wrong HTTP method, parameter validation...).
 *
 * <p><b>Security:</b> unexpected exceptions return a generic 500 message. Stack traces and SQL
 * errors are logged server-side, never sent to the client (they leak internals to attackers).
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://fraud-aware-platform.example/errors/";

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handleNotFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", "NOT_FOUND", e.getMessage());
    }

    /** 422 Unprocessable Content: the request is well-formed but semantically unacceptable. */
    @ExceptionHandler(IdempotencyKeyReuseException.class)
    ProblemDetail handleKeyReuse(IdempotencyKeyReuseException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-key-reuse", "IDEMPOTENCY_KEY_REUSED", e.getMessage());
    }

    @ExceptionHandler(InvalidAmountException.class)
    ProblemDetail handleInvalidAmount(InvalidAmountException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-amount", "INVALID_AMOUNT", e.getMessage());
    }

    @ExceptionHandler(InvalidCursorException.class)
    ProblemDetail handleInvalidCursor(InvalidCursorException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-cursor", "INVALID_CURSOR", e.getMessage());
    }

    /** Last resort. Anything that reaches here is a bug or an outage, not a client mistake. */
    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e) {
        log.error("unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "INTERNAL_ERROR",
                "An unexpected error occurred");
    }

    /**
     * {@code @Valid @RequestBody} failed Bean Validation: list every invalid field so the client can
     * fix them all in one go instead of discovering them one request at a time.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(@NonNull MethodArgumentNotValidException ex,
                                                                  @NonNull HttpHeaders headers,
                                                                  @NonNull HttpStatusCode status,
                                                                  @NonNull WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(validationProblem(errors));
    }

    /**
     * Spring 6.1+ "method validation": raised instead of the exception above when the controller
     * method also has constraints directly on parameters (our {@code @Pattern} on the
     * Idempotency-Key header, {@code @Min/@Max} on {@code limit}). It can contain both
     * parameter-level errors and the field errors of a {@code @Valid} body, so we flatten both.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(@NonNull HandlerMethodValidationException ex,
                                                                            @NonNull HttpHeaders headers,
                                                                            @NonNull HttpStatusCode status,
                                                                            @NonNull WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors bodyErrors) {
                bodyErrors.getFieldErrors().forEach(fe -> errors.add(fieldError(fe.getField(), fe.getDefaultMessage())));
            } else {
                String name = parameterName(result);
                result.getResolvableErrors().forEach(err -> errors.add(fieldError(name, err.getDefaultMessage())));
            }
        }
        return ResponseEntity.badRequest().body(validationProblem(errors));
    }

    /**
     * Every other Spring MVC exception (malformed JSON, missing header, wrong method...) gets a
     * Problem Details body from the base class; we add our {@code code} property to it.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(@NonNull Exception ex, Object body,
                                                             @NonNull HttpHeaders headers,
                                                             @NonNull HttpStatusCode statusCode,
                                                             @NonNull WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail pd
                && (pd.getProperties() == null || !pd.getProperties().containsKey("code"))) {
            pd.setProperty("code", statusCode.is4xxClientError() ? "BAD_REQUEST" : "INTERNAL_ERROR");
        }
        return response;
    }

    /** Uses the HTTP name ("Idempotency-Key") rather than the Java parameter name when there is one. */
    private static String parameterName(ParameterValidationResult result) {
        RequestHeader header = result.getMethodParameter().getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.value().isEmpty()) {
            return header.value();
        }
        String name = result.getMethodParameter().getParameterName();
        return name == null ? "parameter" : name;
    }

    private static Map<String, String> fieldError(String field, String message) {
        return Map.of("field", field, "message", message == null ? "invalid" : message);
    }

    private static ProblemDetail validationProblem(List<Map<String, String>> errors) {
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "validation", "VALIDATION_FAILED",
                "Request validation failed");
        body.setProperty("errors", errors);
        return body;
    }

    private static ProblemDetail problem(HttpStatus status, String type, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + type));
        pd.setProperty("code", code);
        return pd;
    }
}
