package com.screener.service.client;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import com.screener.service.constants.Constants;

@HttpExchange(url = Constants.FINANCE_YAHOO_URL)
public interface YahooHomeClient {

	@GetExchange("/")
	ResponseEntity<Void> getHome(@RequestHeader("User-Agent") String userAgent, @RequestHeader("Accept") String accept,
			@RequestHeader("Accept-Language") String acceptLanguage);
}