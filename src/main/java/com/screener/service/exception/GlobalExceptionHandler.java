package com.screener.service.exception;

import java.net.URI;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
	@ExceptionHandler(BaseAppException.class)
	public ProblemDetail handleAppException(BaseAppException ex, HttpServletResponse response) {
		log.warn("[{}] {}", ex.getErrorCode(), ex.getMessage());
		ProblemDetail pd = problem(ex.getStatus(), "/errors/" + ex.getErrorCode().toLowerCase().replace('_', '-'),
				ex.getErrorCode(), ex.getMessage());
		if (ex instanceof RateLimitException rle) {
			pd.setProperty("retryAfterSeconds", rle.getRetryAfterSeconds());
		}
		if (ex instanceof ExternalApiException eae) {
			pd.setProperty("upstreamStatus", eae.getHttpStatusCode());
			pd.setProperty("upstreamBody", eae.getResponseBody());
		}
		return pd;
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ProblemDetail handleValidation(ConstraintViolationException ex) {
		log.warn("Constraint violation: {}", ex.getMessage());
		return problem(HttpStatus.BAD_REQUEST, "/errors/validation", "Invalid Request Parameters", ex.getMessage());
	}

	@ExceptionHandler(Exception.class)
	public ProblemDetail handleGeneric(Exception ex) {
		log.error("Unexpected error", ex);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "/errors/internal", "INTERNAL_ERROR",
				"An unexpected error occurred.");
	}

	@ExceptionHandler(NoResourceFoundException.class)
	public ResponseEntity<Void> handleNoStaticResource(NoResourceFoundException ex) {
		log.info("[Static] Resource not found: {}", ex.getResourcePath());
		return ResponseEntity.notFound().build();
	}

	private ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
		pd.setType(URI.create(type));
		pd.setTitle(title);
		pd.setProperty("timestamp", Instant.now());
		return pd;
	}
}
