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
import com.screener.service.exception.ScreenerRateLimitException;
import com.screener.service.exception.SessionExpiredException;
import com.screener.service.model.DailyBar;
import com.screener.service.model.StockCandidate;
import com.screener.service.util.ThreadUtil;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class ExchangeUsService implements ExchangeService, SessionProvider {
	private static final int PAGE_SIZE = 250;
	private static final int MAX_PAGES = 8;
	private static final long SESSION_TTL = 25 * 60 * 1000L;
	private static final long COOLDOWN_MS = 25_000L;
	private static final long SESSION_WARMUP_MS = 3_000L;
	private static final long SCREENER_429_WAIT = 15_000L;
	@Value("${screener.us.history-days:120}")
	private int historyDays;
	@Value("${screener.us.max-retries:3}")
	private int maxRetries;
	@Value("${screener.us.delay-min-ms:600}")
	private long delayMinMs;
	@Value("${screener.us.delay-max-ms:1400}")
	private long delayMaxMs;
	private final YahooHomeClient homeClient;
	private final YahooCrumbClient crumbClient;
	private final YahooScreenerClient screenerClient;
	private final WebClient chartClient;
	private final ObjectMapper json = new ObjectMapper();
	private final AtomicInteger uaIndex = new AtomicInteger(0);
	private volatile long last429At = 0;
	private volatile long screener429At = 0;
	private volatile String sessionCookie;
	private volatile String crumb;
	private volatile long sessionAgeMs = 0;
	private volatile long sessionFreshAt = 0;

	public ExchangeUsService(YahooHomeClient homeClient, YahooCrumbClient crumbClient,
			YahooScreenerClient screenerClient, @Qualifier("yahooChartClient") WebClient chartClient) {
		this.homeClient = homeClient;
		this.crumbClient = crumbClient;
		this.screenerClient = screenerClient;
		this.chartClient = chartClient;
	}

	@Override
	public Market getMarket() {
		return Market.US;
	}

	@Override
	public List<DailyBar> fetchHistory(String symbol) {
		String ticker = symbol.trim().toUpperCase();
		throttleChartIfNeeded();
		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try {
				List<DailyBar> bars = callChartApi(ticker, attempt);
				if (!bars.isEmpty()) {
					List<DailyBar> clean = stripIncompleteBar(ticker, bars);
					log.info("[{}] {} confirmed EOD bars", ticker, clean.size());
					return clean;
				}
			} catch (RateLimitException e) {
				last429At = System.currentTimeMillis();
				long wait = ThreadUtil.backoff(attempt);
				log.warn("[{}] 429 — cooldown {}s (attempt {}/{})", ticker, wait / 1000, attempt, maxRetries);
				ThreadUtil.sleep(wait);
			} catch (NotFoundException e) {
				log.warn("[{}] Not found on Yahoo Finance", ticker);
				return Collections.emptyList();
			} catch (Exception e) {
				log.warn("[{}] Attempt {}/{} failed: {}", ticker, attempt, maxRetries, e.getMessage());
				ThreadUtil.sleep(ThreadUtil.backoff(attempt));
			}
		}
		log.error("[{}] All {} attempts failed", ticker, maxRetries);
		return Collections.emptyList();
	}

	@Override
	public List<StockCandidate> fetchCandidates(double minPrice, double maxPrice, String exchange) {
		ensureSession();
		if (crumb == null || sessionCookie == null) {
			log.error("US: Cannot proceed — Yahoo Finance session not established");
			return Collections.emptyList();
		}
		warmUpIfNeeded();
		throttleScreenerIfNeeded();
		List<String> exchanges = resolveExchanges(exchange);
		List<StockCandidate> all = new CopyOnWriteArrayList<>();
		int offset = 0;
		for (int page = 0; page < MAX_PAGES; page++) {
			try {
				List<StockCandidate> pageResult = fetchScreenerPage(minPrice, maxPrice, exchanges, offset);
				if (pageResult.isEmpty())
					break;
				all.addAll(pageResult);
				log.info("US screener page {}: +{} (total: {})", page + 1, pageResult.size(), all.size());
				offset += PAGE_SIZE;
				ThreadUtil.sleep(ThreadLocalRandom.current().nextLong(600, 1200));
			} catch (ScreenerRateLimitException e) {
				screener429At = System.currentTimeMillis();
				long wait = SCREENER_429_WAIT + ThreadLocalRandom.current().nextLong(2000, 5000);
				log.warn("US screener 429 on page {} — cooling down {}s", page + 1, wait / 1000);
				ThreadUtil.sleep(wait);
				refreshSession();
				ThreadUtil.sleep(SESSION_WARMUP_MS + ThreadLocalRandom.current().nextLong(1000, 2000));
				offset = retryPage(all, minPrice, maxPrice, exchanges, offset, page);
			} catch (SessionExpiredException e) {
				log.warn("US session expired — refreshing and retrying page {}", page + 1);
				refreshSession();
				ThreadUtil.sleep(SESSION_WARMUP_MS + ThreadLocalRandom.current().nextLong(500, 1500));
				offset = retryPage(all, minPrice, maxPrice, exchanges, offset, page);
			} catch (Exception e) {
				log.error("US screener page {} failed: {}", page + 1, e.getMessage());
				break;
			}
		}
		log.info("US candidates ${}-${} on {}: {}", String.format("%.2f", minPrice), String.format("%.2f", maxPrice),
				exchange, all.size());
		return List.copyOf(all);
	}

	@SneakyThrows
	private List<DailyBar> callChartApi(String ticker, int attempt) {
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
		return body == null ? Collections.emptyList() : parseChartJson(ticker, body);
	}

	@SneakyThrows
	private List<StockCandidate> fetchScreenerPage(double minPrice, double maxPrice, List<String> exchanges,
			int offset) {
		String ua = Constants.USER_AGENTS[ThreadLocalRandom.current().nextInt(Constants.USER_AGENTS.length)];
		String payload = buildPayload(minPrice, maxPrice, exchanges, offset);
		String body = screenerClient.screen("finance.yahoo.com", "false", "en-US", "US", crumb, ua, sessionCookie,
				Constants.FINANCE_YAHOO_URL, Constants.FINANCE_YAHOO_URL + "/research-hub/screener/", payload);
		return body == null ? List.of() : parseScreenerPage(body, minPrice, maxPrice);
	}

	private int retryPage(List<StockCandidate> all, double minPrice, double maxPrice, List<String> exchanges,
			int offset, int page) {
		try {
			List<StockCandidate> retry = fetchScreenerPage(minPrice, maxPrice, exchanges, offset);
			if (!retry.isEmpty()) {
				all.addAll(retry);
				return offset + PAGE_SIZE;
			}
		} catch (Exception ex) {
			log.error("US retry on page {} failed: {}", page + 1, ex.getMessage());
		}
		return offset;
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
		log.info("US: Refreshing Yahoo Finance session...");
		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try {
				var homeResp = homeClient.getHome(Constants.USER_AGENTS[0], "text/html,*/*", "en-US,en;q=0.9");
				String cookie = extractCookies(homeResp.getHeaders().get(HttpHeaders.SET_COOKIE));
				if (cookie == null) {
					ThreadUtil.sleep(2000);
					continue;
				}
				ThreadUtil.sleep(1500 + ThreadLocalRandom.current().nextLong(1000));
				String crumbVal = crumbClient.getCrumb(Constants.USER_AGENTS[0], cookie,
						Constants.FINANCE_YAHOO_URL + "/");
				if (crumbVal == null || crumbVal.isBlank() || crumbVal.length() < 3 || crumbVal.contains("<")) {
					ThreadUtil.sleep(2000);
					continue;
				}
				sessionCookie = cookie;
				crumb = crumbVal.trim();
				sessionAgeMs = System.currentTimeMillis();
				sessionFreshAt = System.currentTimeMillis();
				log.info("US: session ready. Crumb: {}...", crumb.substring(0, Math.min(crumb.length(), 8)));
				return;
			} catch (Exception e) {
				log.warn("US session refresh {}/{} failed: {}", attempt, maxRetries, e.getMessage());
				ThreadUtil.sleep(2000L * attempt);
			}
		}
		log.error("US: Failed to establish session after {} attempts", maxRetries);
	}

	private List<DailyBar> parseChartJson(String ticker, String body) throws Exception {
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
			JsonNode meta = r.path("meta");
			String name = meta.path("longName").asText(meta.path("shortName").asText(ticker));
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
					LocalDate date = Instant.ofEpochSecond(tsNode.get(i).asLong()).atZone(Market.US.zoneId)
							.toLocalDate();
					bars.add(new DailyBar(ticker, name, date, open, high, low, adjCls, vol));
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
					throw new Exception("US Screener error: " + desc);
				}
				return List.of();
			}
			JsonNode quotes = result.get(0).path("quotes");
			List<StockCandidate> candidates = new ArrayList<>();
			if (quotes.isArray()) {
				for (JsonNode q : quotes) {
					String symbol = q.path("symbol").asText("").trim().toUpperCase();
					if (symbol.isEmpty() || symbol.contains(".") || symbol.contains("^") || symbol.length() > 5)
						continue;
					double price = q.path("regularMarketPrice").asDouble(0);
					if (price <= 0 || price < minPrice || price > maxPrice)
						continue;
					String name = q.path("shortName").asText(q.path("longName").asText(symbol));
					long volume = q.path("regularMarketVolume").asLong(0);
					String exch = q.path("exchange").asText("");
					double mktCap = q.path("marketCap").asDouble(0);
					candidates.add(
							new StockCandidate(symbol, name.isBlank() ? symbol : name, price, volume, exch, mktCap));
				}
			}
			return candidates;
		} catch (SessionExpiredException | ScreenerRateLimitException e) {
			throw e;
		} catch (Exception e) {
			throw new Exception("parseScreenerPage failed", e);
		}
	}

	private List<DailyBar> stripIncompleteBar(String symbol, List<DailyBar> bars) {
		if (bars.size() < 2)
			return bars;
		DailyBar last = bars.get(bars.size() - 1);
		DailyBar prev = bars.get(bars.size() - 2);
		LocalDate today = LocalDate.now(Market.US.zoneId);
		if (last.date().equals(today) && LocalTime.now(Market.US.zoneId).isBefore(Market.US.eodFinal)) {
			log.info("[{}] Dropping intraday bar ({}) — using confirmed EOD ({})", symbol, last.date(), prev.date());
			return new ArrayList<>(bars.subList(0, bars.size() - 1));
		}
		return bars;
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

	private String buildPayload(double minPrice, double maxPrice, List<String> exchanges, int offset) {
		StringBuilder exchOps = new StringBuilder();
		for (int i = 0; i < exchanges.size(); i++) {
			if (i > 0)
				exchOps.append(", ");
			exchOps.append(String.format("{\"operator\":\"eq\",\"operands\":[\"exchange\",\"%s\"]}", exchanges.get(i)));
		}
		return String.format("""
				{
				  "offset": %d, "size": %d,
				  "sortField": "dayvolume", "sortType": "desc", "quoteType": "EQUITY",
				  "topOperator": "AND",
				  "query": {
				    "operator": "AND",
				    "operands": [
				      { "operator": "or",   "operands": [%s] },
				      { "operator": "btwn", "operands": ["regularMarketPrice", %.4f, %.4f] },
				      { "operator": "gt",   "operands": ["dayvolume", 500000] },
				      { "operator": "gt",   "operands": ["intradaymarketcap", 300000000] }
				    ]
				  },
				  "userId": "", "userIdType": "guid"
				}""", offset, PAGE_SIZE, exchOps, minPrice, maxPrice);
	}

	private List<String> resolveExchanges(String exchange) {
		return switch (exchange.toUpperCase()) {
		case "NASDAQ" -> List.of(Constants.NASDAQ, Constants.NASDAQ_CM);
		case "NYSE" -> List.of(Constants.NYSE, Constants.NYSE_MKT);
		case "ALL" -> List.of(Constants.NASDAQ, Constants.NASDAQ_CM, Constants.NYSE, Constants.NYSE_MKT);
		default -> List.of(exchange);
		};
	}

	private void throttleChartIfNeeded() {
		long sinceBlock = System.currentTimeMillis() - last429At;
		if (last429At > 0 && sinceBlock < COOLDOWN_MS)
			ThreadUtil.sleep(COOLDOWN_MS - sinceBlock);
	}

	private void throttleScreenerIfNeeded() {
		long msSince429 = System.currentTimeMillis() - screener429At;
		if (screener429At > 0 && msSince429 < SCREENER_429_WAIT) {
			long remaining = SCREENER_429_WAIT - msSince429;
			log.warn("US screener in 429 cooldown — waiting {}ms", remaining);
			ThreadUtil.sleep(remaining);
		}
	}

	private void warmUpIfNeeded() {
		long msSinceFresh = System.currentTimeMillis() - sessionFreshAt;
		if (msSinceFresh < SESSION_WARMUP_MS) {
			long warmup = SESSION_WARMUP_MS - msSinceFresh + ThreadLocalRandom.current().nextLong(500, 1500);
			log.info("US session warm-up — waiting {}ms", warmup);
			ThreadUtil.sleep(warmup);
		}
	}
}