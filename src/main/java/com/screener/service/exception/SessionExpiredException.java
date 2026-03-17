package com.screener.service.exception;

import org.springframework.http.HttpStatus;

public class SessionExpiredException extends BaseAppException {
	private static final long serialVersionUID = 3L;

	public SessionExpiredException(String provider) {
		super(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", String.format("Session expired for provider: %s", provider));
	}
}
