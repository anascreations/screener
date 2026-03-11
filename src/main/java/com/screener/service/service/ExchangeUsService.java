package com.screener.service.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.screener.service.enums.Market;
import com.screener.service.model.DailyBar;
import com.screener.service.model.StockCandidate;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ExchangeUsService implements ExchangeService, SessionProvider {
	private static final String[] USER_AGENTS = {
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36",
			"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/123.0.0.0 Safari/537.36",
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
			"Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36" };
	public static final String NASDAQ = "NMS";
	public static final String NYSE = "NYQ";
	public static final String NASDAQ_CM = "NCM";
	public static final String NYSE_MKT = "ASE";
	private static final String FINANCE_HOME = "https://finance.yahoo.com";
	private static final String CRUMB_URL = "https://query2.finance.yahoo.com/v1/test/getcrumb";
	private static final String SCREENER_URL = "https://query2.finance.yahoo.com/v1/finance/screener";
	private static final int PAGE_SIZE = 250;
	private static final int MAX_PAGES = 8;
	private static final long SESSION_TTL = 25 * 60 * 1000L;
	private static final long COOLDOWN_MS = 25_000;
	private static final long SESSION_WARMUP_MS = 3_000;
	private static final long SCREENER_429_WAIT = 15_000;
	private final HttpClient http;
	private final ObjectMapper json = new ObjectMapper();
	private final AtomicInteger uaIndex = new AtomicInteger(0);
	@Value("${screener.us.history-days:120}")
	private int historyDays;
	@Value("${screener.us.max-retries:3}")
	private int maxRetries;
	@Value("${screener.us.delay-min-ms:600}")
	private long delayMinMs;
	@Value("${screener.us.delay-max-ms:1400}")
	private long delayMaxMs;
	private volatile long last429At = 0;
	private volatile long screener429At = 0;
	private volatile String sessionCookie = null;
	private volatile String crumb = null;
	private volatile long sessionAgeMs = 0;
	private volatile long sessionFreshAt = 0;

	public ExchangeUsService() {
		this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
				.followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
	}

	@PreDestroy
	public void destroy() {
		if (http instanceof AutoCloseable ac) {
			try {
				ac.close();
			} catch (Exception e) {
				log.warn("Error closing US HttpClient: {}", e.getMessage());
			}
		}
	}

	@Override
	public Market getMarket() {
		return Market.US;
	}

	@Override
	public List<DailyBar> fetchHistory(String symbol) {
		String ticker = symbol.trim().toUpperCase();
		long sinceBlock = System.currentTimeMillis() - last429At;
		if (last429At > 0 && sinceBlock < COOLDOWN_MS)
			sleep(COOLDOWN_MS - sinceBlock);
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
				long wait = backoff(attempt);
				log.warn("[{}] 429 — cooldown {}s (attempt {}/{})", ticker, wait / 1000, attempt, maxRetries);
				sleep(wait);
			} catch (NotFoundException e) {
				log.warn("[{}] Not found on Yahoo Finance", ticker);
				return Collections.emptyList();
			} catch (Exception e) {
				log.warn("[{}] Attempt {}/{} failed: {}", ticker, attempt, maxRetries, e.getMessage());
				sleep(backoff(attempt));
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
		throttleIfPost429();
		List<String> exchanges = resolveExchanges(exchange);
		List<StockCandidate> all = new CopyOnWriteArrayList<>();
		int offset = 0;
		for (int page = 0; page < MAX_PAGES; page++) {
			try {
				List<StockCandidate> page_ = fetchScreenerPage(minPrice, maxPrice, exchanges, offset);
				if (page_.isEmpty())
					break;
				all.addAll(page_);
				log.info("US screener page {}: +{} stocks (total: {})", page + 1, page_.size(), all.size());
				offset += PAGE_SIZE;
				sleep(ThreadLocalRandom.current().nextLong(600, 1200));
			} catch (ScreenerRateLimitException e) {
				screener429At = System.currentTimeMillis();
				log.warn("US screener 429 on page {} — cooling down {}s", page + 1, SCREENER_429_WAIT / 1000);
				sleep(SCREENER_429_WAIT + ThreadLocalRandom.current().nextLong(2000, 5000));
				refreshSession();
				sleep(SESSION_WARMUP_MS + ThreadLocalRandom.current().nextLong(1000, 2000));
				try {
					List<StockCandidate> retry = fetchScreenerPage(minPrice, maxPrice, exchanges, offset);
					if (!retry.isEmpty()) {
						all.addAll(retry);
						offset += PAGE_SIZE;
					}
				} catch (Exception ex) {
					log.error("US retry after 429 failed: {}", ex.getMessage());
					break;
				}
			} catch (SessionExpiredException e) {
				log.warn("US session expired — refreshing and retrying page {}", page + 1);
				refreshSession();
				sleep(SESSION_WARMUP_MS + ThreadLocalRandom.current().nextLong(500, 1500));
				try {
					List<StockCandidate> retry = fetchScreenerPage(minPrice, maxPrice, exchanges, offset);
					if (!retry.isEmpty()) {
						all.addAll(retry);
						offset += PAGE_SIZE;
					}
				} catch (Exception ex) {
					log.error("US retry failed: {}", ex.getMessage());
					break;
				}
			} catch (Exception e) {
				log.error("US screener page {} failed: {}", page + 1, e.getMessage());
				break;
			}
		}
		log.info("US candidates ${}-${} on {}: {}", String.format("%.2f", minPrice), String.format("%.2f", maxPrice),
				exchange, all.size());
		return List.copyOf(all);
	}

	private List<DailyBar> callChartApi(String ticker, int attempt) throws Exception {
		String range = historyDays <= 180 ? "6mo" : "1y";
		String url = String.format(
				"https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=1d&range=%s&includeAdjustedClose=true",
				URLEncoder.encode(ticker, StandardCharsets.UTF_8), range);
		sleep(ThreadLocalRandom.current().nextLong(delayMinMs, delayMaxMs) + (long) (attempt - 1) * 1500);
		int ua = uaIndex.getAndIncrement() % USER_AGENTS.length;
		HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", USER_AGENTS[ua])
				.header("Accept", "application/json,*/*").header("Accept-Language", "en-US,en;q=0.9")
				.header("Accept-Encoding", "identity")
				.header("Referer", "https://finance.yahoo.com/quote/" + ticker + "/").timeout(Duration.ofSeconds(20))
				.GET().build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
		int status = resp.statusCode();
		if (status == 429)
			throw new RateLimitException();
		if (status == 404)
			throw new NotFoundException();
		if (status != 200)
			throw new IOException("HTTP " + status + " for " + ticker);
		return parseChartJson(ticker, resp.body());
	}

	private List<DailyBar> parseChartJson(String ticker, String body) throws Exception {
		JsonNode root = json.readTree(body);
		JsonNode error = root.path("chart").path("error");
		if (!error.isNull() && error.has("description")) {
			String desc = error.path("description").asText("");
			if (desc.contains("Not Found") || desc.contains("No data"))
				throw new NotFoundException();
			throw new IOException("Yahoo error: " + desc);
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
		JsonNode adjClArr = r.path("indicators").path("adjclose");
		JsonNode adjClose = (!adjClArr.isNull() && adjClArr.isArray() && !adjClArr.isEmpty())
				? adjClArr.get(0).path("adjclose")
				: json.nullNode();
		List<DailyBar> bars = new ArrayList<>(tsNode.size());
		for (int i = 0; i < tsNode.size(); i++) {
			try {
				JsonNode o = quote.path("open").get(i), h = quote.path("high").get(i), l = quote.path("low").get(i),
						c = quote.path("close").get(i), v = quote.path("volume").get(i);
				if (o.isNull() || h.isNull() || l.isNull() || c.isNull() || v.isNull())
					continue;
				double open = o.asDouble(), high = h.asDouble(), low = l.asDouble(), close = c.asDouble();
				long volume = v.asLong();
				if (open <= 0 || high < low || close <= 0 || volume <= 0)
					continue;
				double adjCls = (!adjClose.isNull() && adjClose.isArray() && i < adjClose.size()
						&& !adjClose.get(i).isNull()) ? adjClose.get(i).asDouble() : close;
				if (adjCls <= 0)
					adjCls = close;
				LocalDate date = Instant.ofEpochSecond(tsNode.get(i).asLong()).atZone(Market.US.zoneId).toLocalDate();
				bars.add(new DailyBar(ticker, name, date, open, high, low, adjCls, volume));
			} catch (Exception ignored) {
			}
		}
		bars.sort(Comparator.comparing(DailyBar::date));
		return bars;
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

	private List<StockCandidate> fetchScreenerPage(double minPrice, double maxPrice, List<String> exchanges, int offset)
			throws Exception {
		String ua = USER_AGENTS[ThreadLocalRandom.current().nextInt(USER_AGENTS.length)];
		String payload = buildPayload(minPrice, maxPrice, exchanges, offset);
		HttpRequest req = HttpRequest.newBuilder()
				.uri(URI.create(SCREENER_URL + "?corsDomain=finance.yahoo.com&formatted=false&lang=en-US&region=US"
						+ "&crumb=" + URLEncoder.encode(crumb, StandardCharsets.UTF_8)))
				.header("Content-Type", "application/json").header("User-Agent", ua)
				.header("Accept", "application/json,*/*").header("Cookie", sessionCookie).header("Origin", FINANCE_HOME)
				.header("Referer", FINANCE_HOME + "/research-hub/screener/").timeout(Duration.ofSeconds(20))
				.POST(HttpRequest.BodyPublishers.ofString(payload)).build();
		HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
		int status = resp.statusCode();
		if (status == 429)
			throw new ScreenerRateLimitException();
		if (status == 401 || status == 403)
			throw new SessionExpiredException();
		if (status != 200)
			throw new RuntimeException("US Screener HTTP " + status);
		return parseScreenerPage(resp.body(), minPrice, maxPrice);
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

	private List<StockCandidate> parseScreenerPage(String body, double minPrice, double maxPrice) throws Exception {
		JsonNode root = json.readTree(body);
		JsonNode result = root.path("finance").path("result");
		if (result.isNull() || !result.isArray() || result.isEmpty()) {
			JsonNode error = root.path("finance").path("error");
			if (!error.isNull()) {
				String desc = error.path("description").asText("unknown");
				if (desc.contains("Unauthorized") || desc.contains("Invalid crumb"))
					throw new SessionExpiredException();
				throw new RuntimeException("US Screener error: " + desc);
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
				candidates.add(new StockCandidate(symbol, name.isBlank() ? symbol : name, price, volume, exch, mktCap));
			}
		}
		return candidates;
	}

	private List<String> resolveExchanges(String exchange) {
		return switch (exchange.toUpperCase()) {
		case "NASDAQ" -> List.of(NASDAQ, NASDAQ_CM);
		case "NYSE" -> List.of(NYSE, NYSE_MKT);
		case "ALL" -> List.of(NASDAQ, NASDAQ_CM, NYSE, NYSE_MKT);
		default -> List.of(exchange);
		};
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

	private void warmUpIfNeeded() {
		long msSinceFresh = System.currentTimeMillis() - sessionFreshAt;
		if (msSinceFresh < SESSION_WARMUP_MS) {
			long warmup = SESSION_WARMUP_MS - msSinceFresh + ThreadLocalRandom.current().nextLong(500, 1500);
			log.info("US session warm-up — waiting {}ms", warmup);
			sleep(warmup);
		}
	}

	private void throttleIfPost429() {
		long msSince429 = System.currentTimeMillis() - screener429At;
		if (screener429At > 0 && msSince429 < SCREENER_429_WAIT) {
			long remaining = SCREENER_429_WAIT - msSince429;
			log.warn("US screener in 429 cooldown — waiting {}ms", remaining);
			sleep(remaining);
		}
	}

	private void ensureSession() {
		if (sessionCookie == null || crumb == null || (System.currentTimeMillis() - sessionAgeMs) > SESSION_TTL)
			refreshSession();
	}

	private synchronized void refreshSession() {
		log.info("US: Refreshing Yahoo Finance session...");
		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try {
				HttpRequest homeReq = HttpRequest.newBuilder().uri(URI.create(FINANCE_HOME))
						.header("User-Agent", USER_AGENTS[0]).header("Accept", "text/html,*/*")
						.header("Accept-Language", "en-US,en;q=0.9").timeout(Duration.ofSeconds(15)).GET().build();
				HttpResponse<String> homeResp = http.send(homeReq, HttpResponse.BodyHandlers.ofString());
				String cookie = extractCookie(homeResp);
				if (cookie == null) {
					sleep(2000);
					continue;
				}
				sleep(1500 + ThreadLocalRandom.current().nextLong(1000));
				HttpRequest crumbReq = HttpRequest.newBuilder().uri(URI.create(CRUMB_URL))
						.header("User-Agent", USER_AGENTS[0]).header("Cookie", cookie)
						.header("Referer", FINANCE_HOME + "/").timeout(Duration.ofSeconds(10)).GET().build();
				HttpResponse<String> crumbResp = http.send(crumbReq, HttpResponse.BodyHandlers.ofString());
				String crumbVal = crumbResp.body().trim();
				if (crumbVal.isBlank() || crumbVal.length() < 3 || crumbVal.contains("<")) {
					sleep(2000);
					continue;
				}
				sessionCookie = cookie;
				crumb = crumbVal;
				sessionAgeMs = System.currentTimeMillis();
				sessionFreshAt = System.currentTimeMillis();
				log.info("US: session ready. Crumb: {}...", crumb.substring(0, Math.min(crumb.length(), 8)));
				return;
			} catch (Exception e) {
				log.warn("US session refresh {}/{} failed: {}", attempt, maxRetries, e.getMessage());
				sleep(2000L * attempt);
			}
		}
		log.error("US: Failed to establish Yahoo Finance session after {} attempts", maxRetries);
	}

	private String extractCookie(HttpResponse<String> response) {
		List<String> cookies = new ArrayList<>();
		for (String header : response.headers().allValues("Set-Cookie")) {
			String nv = header.split(";")[0].trim();
			if (nv.startsWith("A1=") || nv.startsWith("A3=") || nv.startsWith("A1S=") || nv.startsWith("GUCS=")
					|| nv.startsWith("GUC=") || nv.startsWith("PRF="))
				cookies.add(nv);
		}
		return cookies.isEmpty() ? null : String.join("; ", cookies);
	}

	private long backoff(int attempt) {
		return Math.min(5000L * (1L << (attempt - 1)) + ThreadLocalRandom.current().nextLong(2000), 30_000L);
	}

	private void sleep(long ms) {
		if (ms <= 0)
			return;
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static class RateLimitException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	private static class NotFoundException extends RuntimeException {
		private static final long serialVersionUID = 2L;
	}

	private static class SessionExpiredException extends RuntimeException {
		private static final long serialVersionUID = 3L;
	}

	private static class ScreenerRateLimitException extends RuntimeException {
		private static final long serialVersionUID = 4L;
	}
}