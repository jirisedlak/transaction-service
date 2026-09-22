package com.assessment.transactions.api;

import com.assessment.transactions.domain.TransactionNotFoundException;
import com.assessment.transactions.idempotency.IdempotencyException;
import com.assessment.transactions.logging.TraceContext;
import com.assessment.transactions.service.DeadLetterService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to RFC 9457 problem details. Framework errors (missing header, bad JSON, ...)
 * come from the superclass. Every problem carries the request's {@code correlationId} so a client
 * can quote it when asking for the matching log lines.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(TransactionNotFoundException.class)
    ProblemDetail handleNotFound(TransactionNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(DeadLetterService.DeadLetterNotFoundException.class)
    ProblemDetail handleDeadLetterNotFound(DeadLetterService.DeadLetterNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(DeadLetterService.RedeliveryFailedException.class)
    ProblemDetail handleRedeliveryFailed(DeadLetterService.RedeliveryFailedException e) {
        return problem(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IdempotencyException.InvalidKey.class)
    ProblemDetail handleInvalidKey(IdempotencyException.InvalidKey e) {
        return problem(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(IdempotencyException.KeyReuse.class)
    ProblemDetail handleKeyReuse(IdempotencyException.KeyReuse e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(IdempotencyException.InProgress.class)
    ProblemDetail handleInProgress(IdempotencyException.InProgress e) {
        return problem(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error; quote the correlationId when reporting it");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Request validation failed");
        body.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(body);
    }

    /** Framework-handled errors pass through here: log them. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        log.warn("{} -> {}: {}", ex.getClass().getSimpleName(), statusCode.value(), ex.getMessage());
        return super.handleExceptionInternal(ex, body, headers, statusCode, request);
    }

    /** The superclass builds the problem body right before this call, so tag it here. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            tag(problem);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(status.getReasonPhrase());
        tag(pd);
        if (status.is4xxClientError()) {
            log.warn("{}: {}", status.value(), detail);
        }
        return pd;
    }

    private static void tag(ProblemDetail problem) {
        String correlationId = TraceContext.correlationId();
        if (correlationId != null) {
            problem.setProperty(TraceContext.CORRELATION_ID, correlationId);
        }
    }
}
