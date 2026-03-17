package com.screener.service.exception;

import org.springframework.http.HttpStatus;

public class ExternalApiException extends BaseAppException {
	private static final long serialVersionUID = 5L;
	private final int httpStatusCode;
	private final String responseBody;

	public ExternalApiException(String source, int httpStatusCode, String responseBody) {
		super(HttpStatus.BAD_GATEWAY, "EXTERNAL_API_ERROR",
				String.format("External API '%s' returned %d: %s", source, httpStatusCode, responseBody));
		this.httpStatusCode = httpStatusCode;
		this.responseBody = responseBody;
	}

	public int getHttpStatusCode() {
		return httpStatusCode;
	}

	public String getResponseBody() {
		return responseBody;
	}
}
