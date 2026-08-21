package com.ledger.api;

import com.ledger.error.IdempotencyConflictException;
import com.ledger.error.LedgerException;
import com.ledger.error.UnbalancedEntryException;
import com.ledger.service.ConcurrencyExhaustedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves this service as {@code application/problem+json} (RFC 7807).
 *
 * <p>One error format, machine-readable, with a stable {@code type} URI per
 * failure mode so a client can branch on the type rather than string-matching a
 * message. Each response also carries the request's trace id, so a caller
 * reporting "I got a 409" hands over something that finds the exact log line.
 */
@RestControllerAdvice
public class ProblemDetailsHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<ProblemDetail> handleLedger(LedgerException e, HttpServletRequest req) {
        ProblemDetail problem = base(e.getStatus(), e.getTitle(), e.getMessage(), e.getType(), req);

        if (e instanceof UnbalancedEntryException unbalanced) {
            // The imbalance itself, per currency, so the client can fix the entry
            // without guessing which leg is wrong.
            problem.setProperty("imbalanceByCurrency", unbalanced.getImbalanceByCurrency());
        }
        if (e instanceof IdempotencyConflictException conflict) {
            problem.setProperty("idempotencyKey", conflict.getIdempotencyKey());
        }

        HttpHeaders headers = new HttpHeaders();
        if (e instanceof ConcurrencyExhaustedException) {
            // Actionable: tell the client when to come back rather than making
            // them invent a backoff.
            headers.add(HttpHeaders.RETRY_AFTER, "1");
            log.warn("contention exhausted: {}", e.getMessage());
        }

        return ResponseEntity.status(e.getStatus()).headers(headers).body(problem);
    }

    /**
     * Method-level validation failure -- e.g. a blank Idempotency-Key header.
     *
     * <p>{@code @Validated} on a controller routes these through
     * {@link ConstraintViolationException} rather than
     * {@link MethodArgumentNotValidException}, and without this handler they
     * surface as a 500. A malformed header is a client error, so it is a 400.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(
            ConstraintViolationException e, HttpServletRequest req) {

        Map<String, String> violations = new LinkedHashMap<>();
        e.getConstraintViolations().forEach(
                v -> violations.put(v.getPropertyPath().toString(), v.getMessage()));

        ProblemDetail problem = base(HttpStatus.BAD_REQUEST, "Request failed validation",
                "One or more parameters are invalid.",
                "https://ledger.example/problems/validation-failed", req);
        problem.setProperty("violations", violations);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * The deferred constraint trigger fired, or a unique index rejected a write.
     *
     * <p>Reaching here means the application-level check missed something the
     * database caught -- which is the trigger doing exactly its job. It is a 422
     * (the request was well-formed but unprocessable), and it is logged at ERROR
     * because it indicates a gap in the service-level validation.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException e,
                                                         HttpServletRequest req) {
        String cause = rootMessage(e);
        log.error("database rejected a write that application validation allowed: {}", cause);

        ProblemDetail problem = base(HttpStatus.UNPROCESSABLE_ENTITY,
                "Database invariant violated",
                "The database rejected this write: " + cause,
                "https://ledger.example/problems/invariant-violation", req);
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        Map<String, String> violations = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(
                fe -> violations.put(fe.getField(), fe.getDefaultMessage()));
        e.getBindingResult().getGlobalErrors().forEach(
                ge -> violations.put(ge.getObjectName(), ge.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request failed validation");
        problem.setType(URI.create("https://ledger.example/problems/validation-failed"));
        problem.setTitle("Request failed validation");
        problem.setProperty("violations", violations);
        decorate(problem, null);

        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * A missing or unbindable request header.
     *
     * <p>Hooked on {@code handleServletRequestBindingException} rather than a
     * per-exception method: {@link ResponseEntityExceptionHandler} exposes no
     * {@code handleMissingRequestHeader}, and
     * {@link MissingRequestHeaderException} extends
     * {@link ServletRequestBindingException}, so this is the override Spring
     * actually routes it through.
     */
    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(
            ServletRequestBindingException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        String detail;
        String headerName = null;
        if (e instanceof MissingRequestHeaderException missing) {
            headerName = missing.getHeaderName();
            detail = "Idempotency-Key".equalsIgnoreCase(headerName)
                    ? "The Idempotency-Key header is required on writes. Generate a "
                      + "unique key per logical request so a retry cannot double-post."
                    : "Missing required header: " + headerName;
        } else {
            detail = "The request could not be bound: " + e.getMessage();
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setType(URI.create("https://ledger.example/problems/missing-header"));
        problem.setTitle("Missing required header");
        if (headerName != null) {
            problem.setProperty("header", headerName);
        }
        decorate(problem, null);

        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body is not valid JSON, or a field "
                        + "has the wrong type. Note that amountMinor is an integer "
                        + "number of minor units, never a decimal.");
        problem.setType(URI.create("https://ledger.example/problems/malformed-body"));
        problem.setTitle("Malformed request body");
        decorate(problem, null);

        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception e, HttpServletRequest req) {
        log.error("unhandled exception", e);
        ProblemDetail problem = base(HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal error",
                // Deliberately not e.getMessage(): an exception message can carry
                // SQL fragments or internal ids. The trace id is how a caller gets
                // help without us leaking internals into a public response.
                "An unexpected error occurred. Quote the traceId when reporting it.",
                "https://ledger.example/problems/internal-error", req);
        return ResponseEntity.internalServerError().body(problem);
    }

    private ProblemDetail base(HttpStatus status, String title, String detail,
                               String type, HttpServletRequest req) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(type));
        decorate(problem, req);
        return problem;
    }

    private void decorate(ProblemDetail problem, HttpServletRequest req) {
        problem.setProperty("timestamp", Instant.now().toString());
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        if (req != null) {
            problem.setInstance(URI.create(req.getRequestURI()));
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message.trim();
    }
}
