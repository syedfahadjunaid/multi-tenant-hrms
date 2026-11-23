package com.hrms.core.common;

import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into RFC 9457 "problem+json" responses.
 *
 * <p>Security rule: never send stack traces, SQL, or internal hostnames to the client.
 * Log the details server-side, and return a short, generic message.
 *
 * <p>Validation errors (400) come from the {@link ResponseEntityExceptionHandler} base class.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** One entry per invalid request field, so a UI can show the message next to the input. */
    public record InvalidField(String field, String message) {}

    /** Adds {@code "errors": [{"field": "email", "message": "must be a well-formed email address"}]}. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ex.getBody();
        problem.setDetail("Some fields are invalid.");
        problem.setProperty("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new InvalidField(error.getField(),
                        Objects.requireNonNullElse(error.getDefaultMessage(), "is invalid")))
                .toList());
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail businessRule(BusinessRuleException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage());
        if (e.getField() != null) {
            problem.setProperty("errors", List.of(new InvalidField(e.getField(), e.getMessage())));
        }
        return problem;
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /** Two people changed the same record at the same time; the second save loses (@Version). */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrentUpdate(ObjectOptimisticLockingFailureException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Someone else changed this record at the same time. Reload and try again.");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException e) {
        log.info("Integrity violation: {}", e.getMostSpecificCause().getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The request conflicts with an existing record.");
    }

    @ExceptionHandler(PropertyReferenceException.class)
    ProblemDetail badSort(PropertyReferenceException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Unknown sort property.");
    }

    @ExceptionHandler(RestClientResponseException.class)
    ProblemDetail aiServiceError(RestClientResponseException e) {
        log.warn("AI service returned {}", e.getStatusCode());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "The AI assistant failed to answer.");
    }

    @ExceptionHandler(ResourceAccessException.class)
    ProblemDetail aiServiceUnavailable(ResourceAccessException e) {
        log.warn("AI service unreachable: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "The AI assistant is unavailable.");
    }
}
