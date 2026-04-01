package com.screener.service.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.screener.service.client.YahooCrumbClient;
import com.screener.service.client.YahooHomeClient;
import com.screener.service.constants.Constants;
import com.screener.service.dto.IndexCache;
import com.screener.service.dto.YahooSession;
import com.screener.service.enums.Market;
import com.screener.service.exception.ExternalApiException;
import com.screener.service.exception.RateLimitException;
import com.screener.service.exception.SessionExpiredException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class MarketIndexService {
	private static final long CACHE_TTL_MS = 60 * 60 * 1000L;
	private static final long SESSION_TTL_MS = 30 * 60 * 1000L;

	public enum IndexTrend {
		UPTREND, NEUTRAL, DOWNTREND
	}

	private final Map<Market, IndexCache> indexCache = new ConcurrentHashMap<>();
	private volatile YahooSession session;
	private final WebClient chartClient;
	private final YahooHomeClient yahooHomeClient;
	private final YahooCrumbClient yahooCrumbClient;
	private final ObjectMapper objectMapper;

	public MarketIndexService(@Qualifier("yahooChartClient") WebClient chartClient, YahooHomeClient yahooHomeClient,
			YahooCrumbClient yahooCrumbClient, ObjectMapper objectMapper) {
		this.chartClient = chartClient;
		this.yahooHomeClient = yahooHomeClient;
		this.yahooCrumbClient = yahooCrumbClient;
		this.objectMapper = objectMapper;
	}

	// @PostConstruct
	public void init() {
		try {
			refreshSession();
		} catch (Exception e) {
			log.warn("[INDEX] Session pre-warm failed: {}", e.getMessage());
		}
		for (Market m : Market.values()) {
			try {
				fetch(m);
			} catch (Exception e) {
				log.warn("[INDEX] Pre-warm failed for {}: {}", m, e.getMessage());
			}
		}
	}

	public IndexTrend getTrend(Market market) {
		IndexCache cached = indexCache.get(market);
		if (cached != null && System.currentTimeMillis() - cached.fetchedAt() < CACHE_TTL_MS) {
			return cached.trend();
		}
		return fetch(market);
	}

	public String getSummary(Market market) {
		IndexCache cached = indexCache.get(market);
		if (cached == null)
			return "Not yet fetched";
		return String.format("%s | Close=%s EMA20=%s EMA50=%s", cached.trend(), market.formatPrice(cached.indexClose()),
				market.formatPrice(cached.ema20()), market.formatPrice(cached.ema50()));
	}

	private synchronized void refreshSession() {
		String userAgent = randomUserAgent();
		ResponseEntity<Void> homeResp = yahooHomeClient.getHome(userAgent, "text/html,application/xhtml+xml,*/*;q=0.8",
				"en-US,en;q=0.9");
		String cookie = Optional.ofNullable(homeResp.getHeaders().get(HttpHeaders.SET_COOKIE)).orElse(List.of())
				.stream().collect(Collectors.joining("; "));
		if (cookie.isBlank()) {
			log.warn("[INDEX] No Set-Cookie received from Yahoo home — crumb call may fail");
		}
		String crumb = yahooCrumbClient.getCrumb(userAgent, cookie, Constants.FINANCE_YAHOO_URL + "/");
		session = new YahooSession(cookie, crumb, System.currentTimeMillis());
		log.info("[INDEX] Yahoo session refreshed | crumb={}", crumb);
	}

	private YahooSession getOrRefreshSession() {
		if (session == null || System.currentTimeMillis() - session.fetchedAt() > SESSION_TTL_MS) {
			refreshSession();
		}
		return session;
	}

	private IndexTrend fetch(Market market) {
		String ticker = indexTicker(market);
		try {
			YahooSession s = getOrRefreshSession();
			String body = chartClient.get()
					.uri(uriBuilder -> uriBuilder.path("/v8/finance/chart/{ticker}").queryParam("interval", "1d")
							.queryParam("range", "6mo").build(ticker))
					.header(HttpHeaders.USER_AGENT, randomUserAgent())
					.header(HttpHeaders.ACCEPT, "application/json,*/*")
					.header(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9").header(HttpHeaders.COOKIE, s.cookie())
					.retrieve()
					.onStatus(status -> status.value() == 429,
							r -> Mono.error(new RateLimitException("Yahoo Finance Chart", 30L)))
					.onStatus(status -> status.value() == 401, r -> {
						session = null;
						return Mono.error(new SessionExpiredException("Yahoo Finance"));
					})
					.onStatus(HttpStatusCode::isError,
							r -> r.bodyToMono(String.class).defaultIfEmpty("[no body]")
									.flatMap(errBody -> Mono.error(
											new ExternalApiException("Yahoo Chart", r.statusCode().value(), errBody))))
					.bodyToMono(String.class).block(Duration.ofSeconds(25));
			List<Double> closes = parseCloses(ticker, body);
			if (closes.size() < 52) {
				log.warn("[INDEX] {} only {} bars — assuming NEUTRAL", ticker, closes.size());
				return neutral(market, "insufficient bars");
			}
			double ema20 = ema(closes, 20);
			double ema50 = ema(closes, 50);
			double lastClose = closes.get(closes.size() - 1);
			IndexTrend trend;
			if (ema20 > ema50 && lastClose > ema20)
				trend = IndexTrend.UPTREND;
			else if (ema20 < ema50 && lastClose < ema20)
				trend = IndexTrend.DOWNTREND;
			else
				trend = IndexTrend.NEUTRAL;
			indexCache.put(market, new IndexCache(trend, lastClose, ema20, ema50, System.currentTimeMillis()));
			String icon = switch (trend) {
			case UPTREND -> "📈";
			case DOWNTREND -> "📉";
			case NEUTRAL -> "➡️";
			};
			log.info("[INDEX] {} {} {} | Close={} EMA20={} EMA50={}", ticker, icon, trend,
					market.formatPrice(lastClose), market.formatPrice(ema20), market.formatPrice(ema50));
			return trend;
		} catch (SessionExpiredException e) {
			log.warn("[INDEX] {} session expired — assuming NEUTRAL", ticker);
			return neutral(market, e.getMessage());
		} catch (Exception e) {
			log.warn("[INDEX] {} fetch failed: {} — assuming NEUTRAL", ticker, e.getMessage());
			return neutral(market, e.getMessage());
		}
	}

	private IndexTrend neutral(Market market, String reason) {
		indexCache.put(market,
				new IndexCache(IndexTrend.NEUTRAL, 0, 0, 0, System.currentTimeMillis() - CACHE_TTL_MS / 2));
		return IndexTrend.NEUTRAL;
	}

	private List<Double> parseCloses(String ticker, String body) throws Exception {
		JsonNode root = objectMapper.readTree(body);
		JsonNode result = root.path("chart").path("result");
		if (!result.isArray() || result.isEmpty())
			return List.of();
		JsonNode closes = result.get(0).path("indicators").path("quote").get(0).path("close");
		if (!closes.isArray())
			return List.of();
		List<Double> list = new ArrayList<>(closes.size());
		for (JsonNode c : closes) {
			if (!c.isNull() && c.asDouble() > 0) {
				list.add(c.asDouble());
			}
		}
		return list;
	}

	private double ema(List<Double> data, int period) {
		double ema = 0;
		for (int i = 0; i < period; i++)
			ema += data.get(i);
		ema /= period;
		double alpha = 2.0 / (period + 1);
		for (int i = period; i < data.size(); i++) {
			ema = data.get(i) * alpha + ema * (1 - alpha);
		}
		return ema;
	}

	private String indexTicker(Market market) {
		return switch (market) {
		case MY -> Constants.KLCI_IDX;
		case US -> Constants.GSPC_IDX;
		};
	}

	private String randomUserAgent() {
		return Constants.USER_AGENTS[ThreadLocalRandom.current().nextInt(Constants.USER_AGENTS.length)];
	}
}