package com.screener.service.exception;

import org.springframework.http.HttpStatus;

public class RateLimitException extends BaseAppException {
	private static final long serialVersionUID = 1L;
	private final long retryAfterSeconds;

	public RateLimitException(String source, long retryAfterSeconds) {
		super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
				String.format("Rate limit exceeded for %s. Retry after %ds", source, retryAfterSeconds));
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public long getRetryAfterSeconds() {
		return retryAfterSeconds;
	}
}