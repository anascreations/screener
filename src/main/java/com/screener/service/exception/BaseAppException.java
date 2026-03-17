package com.screener.service.exception;

import org.springframework.http.HttpStatus;

public abstract class BaseAppException extends RuntimeException {
	private static final long serialVersionUID = -1976044107296798583L;
	private final HttpStatus status;
	private final String errorCode;

	protected BaseAppException(HttpStatus status, String errorCode, String message) {
		super(message);
		this.status = status;
		this.errorCode = errorCode;
	}

	protected BaseAppException(HttpStatus status, String errorCode, String message, Throwable cause) {
		super(message, cause);
		this.status = status;
		this.errorCode = errorCode;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getErrorCode() {
		return errorCode;
	}
}
