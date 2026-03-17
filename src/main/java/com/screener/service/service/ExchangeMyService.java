package com.screener.service.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.screener.service.client.YahooCrumbClient;
import com.screener.service.client.YahooHomeClient;
import com.screener.service.client.YahooScreenerClient;
import com.screener.service.constants.Constants;
import com.screener.service.enums.Market;
import com.screener.service.exception.ExternalApiException;
import com.screener.service.exception.NotFoundException;
import com.screener.service.exception.RateLimitException;
import com.screener.service.exception.SessionExpiredException;
import com.screener.service.model.DailyBar;
import com.screener.service.model.StockCandidate;
import com.screener.service.util.ThreadUtil;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class ExchangeMyService implements ExchangeService, SessionProvider {
	private static final int PAGE_SIZE = 250;
	private static final int MAX_PAGES = 8;
	private static final long SESSION_TTL = 25 * 60 * 1000L;
	private static final long COOLDOWN_MS = 25_000L;
	@Value("${screener.my.max-retries:3}")
	private int maxRetries;
	@Value("${screener.my.history-days:120}")
	private int historyDays;
	@Value("${screener.my.delay-min-ms:600}")
	private long delayMinMs;
	@Value("${screener.my.delay-max-ms:1400}")
	private long delayMaxMs;
	private final YahooHomeClient homeClient;
	private final YahooCrumbClient crumbClient;
	private final YahooScreenerClient screenerClient;
	private final WebClient chartClient;
	private final ObjectMapper json = new ObjectMapper();
	private final AtomicInteger uaIndex = new AtomicInteger(0);
	private volatile long last429At = 0;
	private volatile String sessionCookie;
	private volatile String crumb;
	private volatile long sessionAgeMs = 0;

	public ExchangeMyService(YahooHomeClient homeClient, YahooCrumbClient crumbClient,
			YahooScreenerClient screenerClient, @Qualifier("yahooChartClient") WebClient chartClient) {
		this.homeClient = homeClient;
		this.crumbClient = crumbClient;
		this.screenerClient = screenerClient;
		this.chartClient = chartClient;
	}

	@Override
	public Market getMarket() {
		return Market.MY;
	}

	@Override
	public List<DailyBar> fetchHistory(String code) {
		String ticker = code.trim().toUpperCase() + ".KL";
		throttleIfNeeded();
		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try {
				List<DailyBar> bars = callChartApi(ticker, code, attempt);
				if (!bars.isEmpty()) {
					List<DailyBar> clean = stripIncompleteBar(code, bars);
					log.info("[{}.KL] {} confirmed EOD bars", code.toUpperCase(), clean.size());
					return clean;
				}
			} catch (RateLimitException e) {
				last429At = System.currentTimeMillis();
				long wait = ThreadUtil.backoff(attempt);
				log.warn("[{}.KL] 429 — cooldown {}s (attempt {}/{})", code, wait / 1000, attempt, maxRetries);
				ThreadUtil.sleep(wait);
			} catch (NotFoundException e) {
				log.warn("[{}.KL] Not found on Yahoo Finance", code);
				return Collections.emptyList();
			} catch (Exception e) {
				log.warn("[{}.KL] Attempt {}/{} failed: {}", code, attempt, maxRetries, e.getMessage());
				ThreadUtil.sleep(ThreadUtil.backoff(attempt));
			}
		}
		log.error("[{}.KL] All {} attempts failed", code, maxRetries);
		return Collections.emptyList();
	}

	private List<DailyBar> stripIncompleteBar(String code, List<DailyBar> bars) {
		if (bars.size() < 2)
			return bars;
		DailyBar last = bars.get(bars.size() - 1);
		DailyBar prev = bars.get(bars.size() - 2);
		LocalDate today = LocalDate.now(Market.MY.zoneId);
		if (last.date().equals(today) && LocalTime.now(Market.MY.zoneId).isBefore(Market.MY.eodFinal)) {
			log.info("[{}.KL] Dropping intraday bar ({}) — using confirmed EOD ({})", code, last.date(), prev.date());
			return new ArrayList<>(bars.subList(0, bars.size() - 1));
		}
		return bars;
	}

	@Override
	public List<StockCandidate> fetchCandidates(double minPrice, double maxPrice, String exchange) {
		ensureSession();
		if (crumb == null || sessionCookie == null) {
			log.error("MY: Cannot proceed — Yahoo Finance session not established");
			return Collections.emptyList();
		}
		List<StockCandidate> all = new CopyOnWriteArrayList<>();
		int offset = 0;
		for (int page = 0; page < MAX_PAGES; page++) {
			try {
				List<StockCandidate> pageResult = fetchScreenerPage(minPrice, maxPrice, offset);
				if (pageResult.isEmpty())
					break;
				all.addAll(pageResult);
				log.info("MY screener page {}: +{} (total: {})", page + 1, pageResult.size(), all.size());
				offset += PAGE_SIZE;
				ThreadUtil.sleep(ThreadLocalRandom.current().nextLong(400, 900));
			} catch (SessionExpiredException e) {
				log.warn("MY session expired — refreshing and retrying page {}", page + 1);
				refreshSession();
				try {
					all.addAll(fetchScreenerPage(minPrice, maxPrice, offset));
					offset += PAGE_SIZE;
				} catch (Exception ex) {
					log.error("MY retry failed: {}", ex.getMessage());
					break;
				}
			} catch (Exception e) {
				log.error("MY screener page {} failed: {}", page + 1, e.getMessage());
				break;
			}
		}
		log.info("MY candidates RM{}-RM{}: {}", String.format("%.2f", minPrice), String.format("%.2f", maxPrice),
				all.size());
		return List.copyOf(all);
	}

	@SneakyThrows
	private List<DailyBar> callChartApi(String ticker, String code, int attempt) {
		String range = historyDays <= 180 ? "6mo" : "1y";
		String uri = String.format("/v8/finance/chart/%s?interval=1d&range=%s&includeAdjustedClose=true",
				URLEncoder.encode(ticker, StandardCharsets.UTF_8), range);
		ThreadUtil.sleep(ThreadLocalRandom.current().nextLong(delayMinMs, delayMaxMs) + (long) (attempt - 1) * 1500);
		String body = chartClient.get().uri(uri).headers(h -> applyChartHeaders(h, ticker)).retrieve()
				.onStatus(s -> s.value() == 429, r -> Mono.error(new RateLimitException("Yahoo Finance", 30L)))
				.onStatus(s -> s.value() == 404, r -> Mono.error(new NotFoundException("Ticker", ticker)))
				.onStatus(HttpStatusCode::isError,
						r -> r.bodyToMono(String.class).defaultIfEmpty("[no body]")
								.flatMap(errBody -> Mono.error(
										new ExternalApiException("Yahoo Finance", r.statusCode().value(), errBody))))
				.bodyToMono(String.class).block(Duration.ofSeconds(25));
		return body == null ? Collections.emptyList() : parseChartJson(code, body);
	}

	public List<Double> fetchPriceHistory(String code, String interval, int limit) {
		int fetchLimit = limit + 10;
		String ticker = code.trim().toUpperCase() + ".KL";
		throttleIfNeeded();
		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try {
				List<Double> prices = callPriceHistoryApi(ticker, code, interval, fetchLimit, attempt);
				if (!prices.isEmpty()) {
					log.info("[{}.KL] Fetched {} prices (interval={}, requested={})", code, prices.size(), interval,
							fetchLimit);
					return prices;
				}
			} catch (RateLimitException e) {
				last429At = System.currentTimeMillis();
				long wait = ThreadUtil.backoff(attempt);
				log.warn("[{}.KL] 429 — wait {}s", code, wait / 1000);
				ThreadUtil.sleep(wait);
			} catch (NotFoundException e) {
				log.warn("[{}.KL] Not found on Yahoo Finance", code);
				return Collections.emptyList();
			} catch (Exception e) {
				log.warn("[{}.KL] fetchPriceHistory attempt {}/{}: {}", code, attempt, maxRetries, e.getMessage());
				ThreadUtil.sleep(ThreadUtil.backoff(attempt));
			}
		}
		log.error("[{}.KL] fetchPriceHistory — all {} attempts failed", code, maxRetries);
		return Collections.emptyList();
	}

	@SneakyThrows
	private List<Double> callPriceHistoryApi(String ticker, String code, String interval, int limit, int attempt) {
		String uri = String.format(
				"/v8/finance/chart/%s?interval=%s&range=%s&includeAdjustedClose=true&includePrePost=false",
				URLEncoder.encode(ticker, StandardCharsets.UTF_8), mapInterval(interval), mapRange(interval, limit));
		ThreadUtil.sleep(ThreadLocalRandom.current().nextLong(delayMinMs, delayMaxMs) + (long) (attempt - 1) * 1500);
		String body = chartClient.get().uri(uri).headers(h -> applyChartHeaders(h, ticker)).retrieve()
				.onStatus(s -> s.value() == 429, r -> Mono.error(new RateLimitException("Yahoo Finance", 30L)))
				.onStatus(s -> s.value() == 404, r -> Mono.error(new NotFoundException("Ticker", ticker)))
				.onStatus(HttpStatusCode::isError,
						r -> r.bodyToMono(String.class).defaultIfEmpty("[no body]")
								.flatMap(errBody -> Mono.error(
										new ExternalApiException("Yahoo Finance", r.statusCode().value(), errBody))))
				.bodyToMono(String.class).block(Duration.ofSeconds(25));
		return body == null ? Collections.emptyList() : parsePriceHistoryJson(code, body, limit);
	}

	@SneakyThrows
	private List<StockCandidate> fetchScreenerPage(double minPrice, double maxPrice, int offset) {
		String ua = Constants.USER_AGENTS[ThreadLocalRandom.current().nextInt(Constants.USER_AGENTS.length)];
		String payload = buildScreenerPayload(minPrice, maxPrice, offset);
		String uri = "/v1/finance/screener" + "?corsDomain=finance.yahoo.com&formatted=false&lang=en-US&region=MY"
				+ "&crumb=" + URLEncoder.encode(crumb, StandardCharsets.UTF_8);
		String body = screenerClient.screen("finance.yahoo.com", "false", "en-US", "MY", crumb, ua, sessionCookie,
				Constants.FINANCE_YAHOO_URL, Constants.FINANCE_YAHOO_URL + "/research-hub/screener/", payload);
		return body == null ? List.of() : parseScreenerPage(body, minPrice, maxPrice);
	}

	@Override
	public String getSessionCookie() {
		ensureSession();
		return sessionCookie;
	}

	@Override
	public String getCrumb() {
		ensureSession();
		return crumb;
	}

	@Override
	public void forceRefresh() {
		sessionAgeMs = 0;
		refreshSession();
	}

	private void ensureSession() {
		if (sessionCookie == null || crumb == null || (System.currentTimeMillis() - sessionAgeMs) > SESSION_TTL)
			refreshSession();
	}

	private synchronized void refreshSession() {
		log.info("MY: Refreshing Yahoo Finance session...");
		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try {
				homeClient.getHome(Constants.USER_AGENTS[0], "text/html,*/*", "en-US,en;q=0.9").getHeaders()
						.get(HttpHeaders.SET_COOKIE);
				var homeResp = homeClient.getHome(Constants.USER_AGENTS[0], "text/html,*/*", "en-US,en;q=0.9");
				String cookie = extractCookies(homeResp.getHeaders().get(HttpHeaders.SET_COOKIE));
				if (cookie == null) {
					ThreadUtil.sleep(2000);
					continue;
				}
				ThreadUtil.sleep(500 + ThreadLocalRandom.current().nextLong(300));
				String crumbVal = crumbClient.getCrumb(Constants.USER_AGENTS[0], cookie,
						Constants.FINANCE_YAHOO_URL + "/");
				if (crumbVal == null || crumbVal.isBlank() || crumbVal.length() < 3 || crumbVal.contains("<")) {
					ThreadUtil.sleep(2000);
					continue;
				}
				sessionCookie = cookie;
				crumb = crumbVal.trim();
				sessionAgeMs = System.currentTimeMillis();
				log.info("MY: session ready. Crumb: {}...", crumb.substring(0, Math.min(crumb.length(), 8)));
				return;
			} catch (Exception e) {
				log.warn("MY session refresh {}/{} failed: {}", attempt, maxRetries, e.getMessage());
				ThreadUtil.sleep(2000L * attempt);
			}
		}
		log.error("MY: Failed to establish session after {} attempts", maxRetries);
	}

	private List<DailyBar> parseChartJson(String code, String body) throws Exception {
		try {
			JsonNode root = json.readTree(body);
			JsonNode error = root.path("chart").path("error");
			if (!error.isNull() && error.has("description")) {
				String desc = error.path("description").asText("");
				if (desc.contains("Not Found") || desc.contains("No data"))
					throw new NotFoundException("No data", desc);
				throw new Exception("Yahoo error: " + desc);
			}
			JsonNode result = root.path("chart").path("result");
			if (!result.isArray() || result.isEmpty())
				return Collections.emptyList();
			JsonNode r = result.get(0);
			JsonNode meta = r.path("meta");
			String name = meta.path("longName").asText(meta.path("shortName").asText(code));
			JsonNode tsNode = r.path("timestamp");
			if (!tsNode.isArray() || tsNode.isEmpty())
				return Collections.emptyList();
			JsonNode quote = r.path("indicators").path("quote").get(0);
			JsonNode adjClose = resolveAdjClose(r);
			List<DailyBar> bars = new ArrayList<>(tsNode.size());
			for (int i = 0; i < tsNode.size(); i++) {
				try {
					JsonNode o = quote.path("open").get(i), h = quote.path("high").get(i), l = quote.path("low").get(i),
							c = quote.path("close").get(i), v = quote.path("volume").get(i);
					if (o.isNull() || h.isNull() || l.isNull() || c.isNull() || v.isNull())
						continue;
					double open = o.asDouble(), high = h.asDouble(), low = l.asDouble(), close = c.asDouble();
					long vol = v.asLong();
					if (open <= 0 || high < low || close <= 0 || vol <= 0)
						continue;
					double adjCls = adjValue(adjClose, i, close);
					LocalDate date = Instant.ofEpochSecond(tsNode.get(i).asLong()).atZone(Market.MY.zoneId)
							.toLocalDate();
					bars.add(new DailyBar(code.toUpperCase(), name, date, open, high, low, adjCls, vol));
				} catch (Exception ignored) {
				}
			}
			bars.sort(Comparator.comparing(DailyBar::date));
			return bars;
		} catch (RateLimitException | NotFoundException e) {
			throw e;
		} catch (Exception e) {
			throw new Exception("parseChartJson failed", e);
		}
	}

	private List<Double> parsePriceHistoryJson(String code, String body, int limit) throws Exception {
		try {
			JsonNode root = json.readTree(body);
			JsonNode error = root.path("chart").path("error");
			if (!error.isNull() && error.has("description")) {
				String desc = error.path("description").asText("");
				if (desc.contains("Not Found") || desc.contains("No data"))
					throw new NotFoundException("Data", desc);
				throw new Exception("Yahoo error: " + desc);
			}
			JsonNode result = root.path("chart").path("result");
			if (!result.isArray() || result.isEmpty())
				return Collections.emptyList();
			JsonNode r = result.get(0);
			JsonNode quote = r.path("indicators").path("quote").get(0);
			JsonNode adjClose = resolveAdjClose(r);
			JsonNode tsNode = r.path("timestamp");
			if (!tsNode.isArray() || tsNode.isEmpty())
				return Collections.emptyList();
			List<Double> prices = new ArrayList<>(tsNode.size());
			for (int i = 0; i < tsNode.size(); i++) {
				try {
					JsonNode c = quote.path("close").get(i);
					JsonNode v = quote.path("volume").get(i);
					if (c == null || c.isNull() || v == null || v.isNull())
						continue;
					double close = c.asDouble();
					if (close <= 0)
						continue;
					prices.add(adjValue(adjClose, i, close));
				} catch (Exception ignored) {
				}
			}
			int from = Math.max(0, prices.size() - limit);
			return new ArrayList<>(prices.subList(from, prices.size()));
		} catch (NotFoundException e) {
			throw e;
		} catch (Exception e) {
			throw new Exception("parsePriceHistoryJson failed", e);
		}
	}

	private List<StockCandidate> parseScreenerPage(String body, double minPrice, double maxPrice) throws Exception {
		try {
			JsonNode root = json.readTree(body);
			JsonNode result = root.path("finance").path("result");
			if (result.isNull() || !result.isArray() || result.isEmpty()) {
				JsonNode error = root.path("finance").path("error");
				if (!error.isNull()) {
					String desc = error.path("description").asText("unknown");
					if (desc.contains("Unauthorized") || desc.contains("Invalid crumb"))
						throw new SessionExpiredException("Yahoo");
					throw new Exception("MY Screener error: " + desc);
				}
				return List.of();
			}
			JsonNode quotes = result.get(0).path("quotes");
			List<StockCandidate> candidates = new ArrayList<>();
			if (quotes.isArray()) {
				for (JsonNode q : quotes) {
					String symbol = q.path("symbol").asText("");
					if (!symbol.endsWith(".KL"))
						continue;
					String code = symbol.replace(".KL", "");
					if (!code.matches("\\d{4}"))
						continue;
					double price = q.path("regularMarketPrice").asDouble(0);
					if (price <= 0 || price < minPrice || price > maxPrice)
						continue;
					String name = q.path("shortName").asText(q.path("longName").asText(code));
					long volume = q.path("regularMarketVolume").asLong(0);
					String exch = q.path("exchange").asText("");
					candidates.add(new StockCandidate(code, name.isBlank() ? code : name, price, volume, exch));
				}
			}
			return candidates;
		} catch (SessionExpiredException e) {
			throw e;
		} catch (Exception e) {
			throw new Exception("parseScreenerPage failed", e);
		}
	}

	private JsonNode resolveAdjClose(JsonNode r) {
		JsonNode arr = r.path("indicators").path("adjclose");
		return (!arr.isNull() && arr.isArray() && !arr.isEmpty()) ? arr.get(0).path("adjclose") : json.nullNode();
	}

	private double adjValue(JsonNode adjClose, int i, double rawClose) {
		if (!adjClose.isNull() && adjClose.isArray() && i < adjClose.size() && !adjClose.get(i).isNull()) {
			double v = adjClose.get(i).asDouble();
			if (v > 0)
				return v;
		}
		return rawClose;
	}

	private void applyChartHeaders(HttpHeaders h, String ticker) {
		int ua = uaIndex.getAndIncrement() % Constants.USER_AGENTS.length;
		h.set("User-Agent", Constants.USER_AGENTS[ua]);
		h.set("Accept", "application/json,*/*");
		h.set("Accept-Language", "en-US,en;q=0.9");
		h.set("Accept-Encoding", "identity");
		h.set("Referer", Constants.FINANCE_YAHOO_URL + "/quote/" + ticker + "/");
	}

	private String extractCookies(List<String> setCookieHeaders) {
		if (setCookieHeaders == null)
			return null;
		List<String> cookies = new ArrayList<>();
		for (String header : setCookieHeaders) {
			String nv = header.split(";")[0].trim();
			if (nv.startsWith("A1=") || nv.startsWith("A3=") || nv.startsWith("A1S=") || nv.startsWith("GUCS=")
					|| nv.startsWith("GUC=") || nv.startsWith("PRF="))
				cookies.add(nv);
		}
		return cookies.isEmpty() ? null : String.join("; ", cookies);
	}

	private String buildScreenerPayload(double minPrice, double maxPrice, int offset) {
		return String.format("""
				{
				  "offset": %d, "size": %d,
				  "sortField": "dayvolume", "sortType": "desc", "quoteType": "EQUITY",
				  "topOperator": "AND",
				  "query": {
				    "operator": "AND",
				    "operands": [
				      { "operator": "or", "operands": [
				        { "operator": "eq", "operands": ["exchange", "KLQ"] },
				        { "operator": "eq", "operands": ["exchange", "KLS"] }
				      ]},
				      { "operator": "btwn", "operands": ["regularMarketPrice", %.4f, %.4f] },
				      { "operator": "gt",   "operands": ["dayvolume", 100000] }
				    ]
				  },
				  "userId": "", "userIdType": "guid"
				}""", offset, PAGE_SIZE, minPrice, maxPrice);
	}

	private void throttleIfNeeded() {
		long sinceBlock = System.currentTimeMillis() - last429At;
		if (last429At > 0 && sinceBlock < COOLDOWN_MS)
			ThreadUtil.sleep(COOLDOWN_MS - sinceBlock);
	}

	public double calcMA(List<Double> prices, int period) {
		if (prices == null || prices.isEmpty())
			return 0;
		int usable = Math.min(period, prices.size());
		return prices.subList(prices.size() - usable, prices.size()).stream().mapToDouble(Double::doubleValue).average()
				.orElse(0);
	}

	private String mapInterval(String interval) {
		return switch (interval.toLowerCase()) {
		case "1d" -> "1d";
		case "1w" -> "1wk";
		case "1mo" -> "1mo";
		case "1h" -> "1h";
		case "15min", "15m" -> "15m";
		case "5min", "5m" -> "5m";
		case "1min", "1m" -> "1m";
		default -> "1d";
		};
	}

	private String mapRange(String interval, int limit) {
		return switch (interval.toLowerCase()) {
		case "1d" -> limit <= 30 ? "1mo" : limit <= 90 ? "3mo" : "1y";
		case "1w" -> limit <= 12 ? "3mo" : limit <= 52 ? "1y" : "5y";
		case "1h" -> limit <= 24 ? "1d" : limit <= 168 ? "5d" : "1mo";
		case "15min", "15m" -> limit <= 50 ? "5d" : "1mo";
		case "5min", "5m" -> limit <= 100 ? "5d" : "1mo";
		case "1min", "1m" -> "1d";
		default -> "3mo";
		};
	}
}