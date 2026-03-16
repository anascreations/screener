package com.screener.service.client;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import com.screener.service.constants.Constants;

@HttpExchange(url = Constants.QUERY2_FINANCE_YAHOO_URL, contentType = "application/json", accept = "application/json")
public interface YahooScreenerClient {

	@PostExchange("/v1/finance/screener")
	String screen(@RequestParam String corsDomain, @RequestParam String formatted, @RequestParam String lang,
			@RequestParam String region, @RequestParam String crumb, @RequestHeader("User-Agent") String userAgent,
			@RequestHeader("Cookie") String cookie, @RequestHeader("Origin") String origin,
			@RequestHeader("Referer") String referer, @RequestBody String payload);
}
