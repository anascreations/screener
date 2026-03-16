package com.screener.service.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.support.WebClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

import com.screener.service.client.ScreenerClient;
import com.screener.service.client.YahooCrumbClient;
import com.screener.service.client.YahooHomeClient;
import com.screener.service.client.YahooScreenerClient;
import com.screener.service.constants.Constants;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

@Slf4j
@Configuration
public class WebClientConfig {

	@Bean
	ConnectionProvider yahooConnectionPool() {
		return ConnectionProvider.builder("yahoo-pool").maxConnections(10).pendingAcquireMaxCount(20)
				.maxIdleTime(Duration.ofSeconds(30)).maxLifeTime(Duration.ofMinutes(5))
				.evictInBackground(Duration.ofSeconds(60)).build();
	}

	@Bean
	@Qualifier("yahooChartClient")
	WebClient yahooChartClient(ConnectionProvider yahooConnectionPool) {
		return buildWebClient(Constants.QUERY1_FINANCE_YAHOO_URL, yahooConnectionPool, 8192);
	}

	@Bean
	YahooHomeClient yahooHomeClient(ConnectionProvider yahooConnectionPool) {
		return proxyClient(Constants.FINANCE_YAHOO_URL, yahooConnectionPool, 128 * 1024, YahooHomeClient.class);
	}

	@Bean
	YahooCrumbClient yahooCrumbClient(ConnectionProvider yahooConnectionPool) {
		return proxyClient(Constants.QUERY2_FINANCE_YAHOO_URL, yahooConnectionPool, 8192, YahooCrumbClient.class);
	}

	@Bean
	YahooScreenerClient yahooScreenerClient(ConnectionProvider yahooConnectionPool) {
		return proxyClient(Constants.QUERY2_FINANCE_YAHOO_URL, yahooConnectionPool, 8192, YahooScreenerClient.class);
	}

	@Bean
	ScreenerClient screenerClient(WebClient.Builder builder, @Value("${screener.api.base-url}") String baseUrl) {
		return proxyClient(builder.baseUrl(baseUrl).build(), ScreenerClient.class);
	}

	private WebClient buildWebClient(String baseUrl, ConnectionProvider pool, int maxHeaderSize) {
		HttpClient netty = HttpClient.create(pool).option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 15_000)
				.responseTimeout(Duration.ofSeconds(20)).protocol(HttpProtocol.HTTP11)
				.httpResponseDecoder(spec -> spec.maxHeaderSize(maxHeaderSize));
		return WebClient.builder().baseUrl(baseUrl).clientConnector(new ReactorClientHttpConnector(netty))
				.codecs(cfg -> cfg.defaultCodecs().maxInMemorySize(2 * 1024 * 1024)).build();
	}

	private <T> T proxyClient(String baseUrl, ConnectionProvider pool, int maxHeaderSize, Class<T> clientClass) {
		return proxyClient(buildWebClient(baseUrl, pool, maxHeaderSize), clientClass);
	}

	private <T> T proxyClient(WebClient webClient, Class<T> clientClass) {
		return HttpServiceProxyFactory.builderFor(WebClientAdapter.create(webClient)).build().createClient(clientClass);
	}
}