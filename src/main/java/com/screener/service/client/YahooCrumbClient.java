package com.screener.service.client;

import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;

import com.screener.service.constants.Constants;

@HttpExchange(url = Constants.QUERY2_FINANCE_YAHOO_URL)
public interface YahooCrumbClient {

	@GetExchange("/v1/test/getcrumb")
	String getCrumb(@RequestHeader("User-Agent") String userAgent, @RequestHeader("Cookie") String cookie,
			@RequestHeader("Referer") String referer);
}
