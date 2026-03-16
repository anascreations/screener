//package com.screener.service.exception;
//
//import java.net.URI;
//import java.time.Instant;
//
//import org.springframework.http.HttpStatus;
//import org.springframework.http.ProblemDetail;
//import org.springframework.web.bind.MissingServletRequestParameterException;
//import org.springframework.web.bind.annotation.ExceptionHandler;
//import org.springframework.web.bind.annotation.RestControllerAdvice;
//import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
//
//import jakarta.validation.ConstraintViolationException;
//import lombok.extern.slf4j.Slf4j;
//
///**
// * @author: Mohd Anas
// *
// */
//@Slf4j
//@RestControllerAdvice
//public class GlobalExceptionHandler {
//	@ExceptionHandler(ConstraintViolationException.class)
//	public ProblemDetail handleValidation(ConstraintViolationException ex) {
//		log.warn("Constraint violation: {}", ex.getMessage());
//		return problem(HttpStatus.BAD_REQUEST, "/errors/validation", "Invalid Request Parameters", ex.getMessage());
//	}
//
//	/**
//	 * Wrong type for a {@code @RequestParam}, e.g. passing {@code "abc"} for a
//	 * {@code double} parameter.
//	 */
//	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
//	public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
//		String detail = String.format("Parameter '%s' must be of type %s", ex.getName(),
//				ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "unknown");
//		log.warn("Type mismatch: {}", detail);
//		return problem(HttpStatus.BAD_REQUEST, "/errors/type-mismatch", "Parameter Type Error", detail);
//	}
//
//	/**
//	 * Required {@code @RequestParam} not supplied at all.
//	 */
//	@ExceptionHandler(MissingServletRequestParameterException.class)
//	public ProblemDetail handleMissingParam(MissingServletRequestParameterException ex) {
//		log.warn("Missing parameter: {}", ex.getMessage());
//		return problem(HttpStatus.BAD_REQUEST, "/errors/missing-parameter", "Missing Required Parameter",
//				ex.getMessage());
//	}
//
//	/**
//	 * Explicit business-rule violations thrown from the controller, e.g.
//	 * {@code minPrice >= maxPrice} in the watchlist endpoint.
//	 */
//	@ExceptionHandler(IllegalArgumentException.class)
//	public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
//		log.warn("Illegal argument: {}", ex.getMessage());
//		return problem(HttpStatus.BAD_REQUEST, "/errors/bad-request", "Bad Request", ex.getMessage());
//	}
//
//	// ─────────────────────────────────────────────────────────────────────────
//	// 5xx Server / Upstream Errors
//	// ─────────────────────────────────────────────────────────────────────────
//
//	/**
//	 * Upstream data provider (Twelve Data) returned an error or was unreachable.
//	 * Mapped to 502 Bad Gateway because the failure is in a dependency, not our
//	 * code.
//	 */
//	@ExceptionHandler(DataFetchException.class)
//	public ProblemDetail handleDataFetch(DataFetchException ex) {
//		log.warn("Data fetch error: {}", ex.getMessage());
//		return problem(HttpStatus.BAD_GATEWAY, "/errors/data-fetch", "External Data Error", ex.getMessage());
//	}
//
//	/**
//	 * Catch-all for anything not explicitly handled above. Logs the full stack
//	 * trace for investigation.
//	 */
//	@ExceptionHandler(Exception.class)
//	public ProblemDetail handleGeneric(Exception ex) {
//		log.error("Unexpected error", ex);
//		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "/errors/internal", "Internal Server Error",
//				"An unexpected error occurred.");
//	}
//
//	// ─────────────────────────────────────────────────────────────────────────
//
//	private ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
//		ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
//		pd.setType(URI.create(type));
//		pd.setTitle(title);
//		pd.setProperty("timestamp", Instant.now());
//		return pd;
//	}
//}

