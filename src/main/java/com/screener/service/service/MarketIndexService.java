package com.screener.service.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.screener.service.enums.Market;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class MarketIndexService {
	private static final String CHART_URL = "https://query1.finance.yahoo.com/v8/finance/chart/%s?interval=1d&range=6mo";
	private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36";
	private static final long CACHE_TTL_MS = 60 * 60 * 1000L;

	public enum IndexTrend {
		UPTREND, NEUTRAL, DOWNTREND
	}

	private record IndexCache(IndexTrend trend, double indexClose, double ema20, double ema50, long fetchedAt) {
	}

	private final Map<Market, IndexCache> cache = new ConcurrentHashMap<>();
	private final HttpClient httpClient;
	private final ObjectMapper json = new ObjectMapper();

	public MarketIndexService() {
		this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
				.followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
	}

	@PostConstruct
	public void init() {
		for (Market m : Market.values()) {
			try {
				fetch(m);
			} catch (Exception e) {
				log.warn("[INDEX] Pre-warm failed for {}: {}", m, e.getMessage());
			}
		}
	}

	public IndexTrend getTrend(Market market) {
		IndexCache cached = cache.get(market);
		if (cached != null && (System.currentTimeMillis() - cached.fetchedAt()) < CACHE_TTL_MS) {
			return cached.trend();
		}
		return fetch(market);
	}

	public String getSummary(Market market) {
		IndexCache cached = cache.get(market);
		if (cached == null)
			return "Not yet fetched";
		return String.format("%s | Close=%s EMA20=%s EMA50=%s", cached.trend(), market.formatPrice(cached.indexClose()),
				market.formatPrice(cached.ema20()), market.formatPrice(cached.ema50()));
	}

	private IndexTrend fetch(Market market) {
		String ticker = indexTicker(market);
		try {
			String url = String.format(CHART_URL, URLEncoder.encode(ticker, StandardCharsets.UTF_8));
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", USER_AGENT)
					.header("Accept", "application/json,*/*").header("Accept-Language", "en-US,en;q=0.9")
					.timeout(Duration.ofSeconds(20)).GET().build();
			HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
			if (resp.statusCode() != 200) {
				log.warn("[INDEX] {} HTTP {} — assuming NEUTRAL", ticker, resp.statusCode());
				return neutral(market, "HTTP " + resp.statusCode());
			}
			List<Double> closes = parseCloses(ticker, resp.body());
			if (closes.size() < 52) {
				log.warn("[INDEX] {} only {} bars — assuming NEUTRAL", ticker, closes.size());
				return neutral(market, "insufficient bars");
			}
			double ema20 = ema(closes, 20);
			double ema50 = ema(closes, 50);
			double todayClose = closes.get(closes.size() - 1);
			IndexTrend trend;
			if (ema20 > ema50 && todayClose > ema20) {
				trend = IndexTrend.UPTREND;
			} else if (ema20 < ema50 && todayClose < ema20) {
				trend = IndexTrend.DOWNTREND;
			} else {
				trend = IndexTrend.NEUTRAL;
			}
			cache.put(market, new IndexCache(trend, todayClose, ema20, ema50, System.currentTimeMillis()));
			String icon = switch (trend) {
			case UPTREND -> "📈";
			case DOWNTREND -> "📉";
			case NEUTRAL -> "➡️";
			};
			log.info("[INDEX] {} {} {} | Close={} EMA20={} EMA50={}", ticker, icon, trend,
					market.formatPrice(todayClose), market.formatPrice(ema20), market.formatPrice(ema50));
			return trend;
		} catch (Exception e) {
			log.warn("[INDEX] {} fetch failed: {} — assuming NEUTRAL", ticker, e.getMessage());
			return neutral(market, e.getMessage());
		}
	}

	private IndexTrend neutral(Market market, String reason) {
		cache.put(market, new IndexCache(IndexTrend.NEUTRAL, 0, 0, 0, System.currentTimeMillis() - CACHE_TTL_MS / 2));
		return IndexTrend.NEUTRAL;
	}

	private List<Double> parseCloses(String ticker, String body) throws Exception {
		JsonNode root = json.readTree(body);
		JsonNode result = root.path("chart").path("result");
		if (!result.isArray() || result.isEmpty())
			return List.of();
		JsonNode r = result.get(0);
		JsonNode ts = r.path("timestamp");
		JsonNode closes = r.path("indicators").path("quote").get(0).path("close");
		if (!ts.isArray() || !closes.isArray())
			return List.of();
		List<Double> list = new ArrayList<>(ts.size());
		for (int i = 0; i < ts.size(); i++) {
			JsonNode c = closes.get(i);
			if (!c.isNull() && c.asDouble() > 0) {
				list.add(c.asDouble());
			}
		}
		list.sort(Comparator.naturalOrder());
		return list;
	}

	private double ema(List<Double> data, int period) {
		if (data.size() < period)
			return data.get(data.size() - 1);
		double alpha = 2.0 / (period + 1);
		double ema = 0;
		for (int i = 0; i < period; i++)
			ema += data.get(data.size() - period + i - (data.size() - period));
		int startIdx = data.size() - (data.size());
		ema = 0;
		for (int i = 0; i < period; i++)
			ema += data.get(i);
		ema /= period;
		for (int i = period; i < data.size(); i++) {
			ema = data.get(i) * alpha + ema * (1 - alpha);
		}
		return ema;
	}

	private String indexTicker(Market market) {
		return switch (market) {
		case MY -> "^KLSE";
		case US -> "^GSPC";
		};
	}
}