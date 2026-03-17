package com.screener.service.exception;

import org.springframework.http.HttpStatus;

public class NotFoundException extends BaseAppException {
	private static final long serialVersionUID = 2L;

	public NotFoundException(String resource, Object identifier) {
		super(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", String.format("%s not found: %s", resource, identifier));
	}
}