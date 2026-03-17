package com.screener.service.exception;

import org.springframework.http.HttpStatus;

public class ScreenerRateLimitException extends BaseAppException {
	private static final long serialVersionUID = 4L;

	public ScreenerRateLimitException(String screenerName) {
		super(HttpStatus.TOO_MANY_REQUESTS, "SCREENER_RATE_LIMIT",
				String.format("Screener API rate limit hit for: %s", screenerName));
	}
}