package com.screener.service.controller;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.screener.service.enums.Market;
import com.screener.service.model.ScanResult;
import com.screener.service.service.AnalysisService;
import com.screener.service.service.FundamentalService;
import com.screener.service.service.FundamentalService.FundamentalResult;
import com.screener.service.util.ThreadUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("api/{market}/fundamental")
@RequiredArgsConstructor
@Slf4j
public class FundamentalController {
	private final FundamentalService fundamentalService;
	private final AnalysisService analysisService;

	@GetMapping("{code}")
	public ResponseEntity<Map<String, Object>> getFundamentals(@PathVariable String market, @PathVariable String code) {
		Market m = parseMarket(market);
		if (m == null)
			return badMarket(market);
		log.info("=== FUNDAMENTAL ANALYSIS [{}/{}] ===", m, code.toUpperCase());
		FundamentalResult result = fundamentalService.analyze(m, code);
		return ResponseEntity.ok(buildFundamentalResponse(m, result));
	}

	@PostMapping("{code}/full")
	public ResponseEntity<Map<String, Object>> getFullAnalysis(@PathVariable String market, @PathVariable String code) {
		Market m = parseMarket(market);
		if (m == null)
			return badMarket(market);
		log.info("=== FULL ANALYSIS (Technical + Fundamental) [{}/{}] ===", m, code.toUpperCase());
		ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
		CompletableFuture<Optional<ScanResult>> techFuture = CompletableFuture
				.supplyAsync(() -> analysisService.scan(m, code), pool);
		CompletableFuture<FundamentalResult> fundFuture = CompletableFuture
				.supplyAsync(() -> fundamentalService.analyze(m, code), pool);
		pool.shutdown();
		Optional<ScanResult> techResult;
		FundamentalResult fundResult;
		try {
			techResult = techFuture.get();
			fundResult = fundFuture.get();
		} catch (Exception e) {
			return ResponseEntity.status(503).body(Map.of("error", "Analysis failed: " + e.getMessage()));
		}
		return ResponseEntity.ok(buildCombinedResponse(m, code, techResult, fundResult));
	}

	@PostMapping("batch")
	public ResponseEntity<Map<String, Object>> batchFundamentals(@PathVariable String market,
			@RequestBody Map<String, List<String>> body) {
		Market m = parseMarket(market);
		if (m == null)
			return badMarket(market);
		List<String> codes = body.getOrDefault("codes", List.of());
		if (codes.isEmpty())
			return ResponseEntity.badRequest()
					.body(Map.of("error", "Empty code list", "format", "{\"codes\":[\"5243\",\"1155\"]}"));
		if (codes.size() > 10)
			return ResponseEntity.badRequest()
					.body(Map.of("error", "Max 10 stocks per batch for fundamental analysis"));
		log.info("=== BATCH FUNDAMENTAL [{}/{}] {} stocks ===", m, m.name(), codes.size());
		var results = codes.stream().map(code -> {
			ThreadUtil.sleep(500);
			FundamentalResult r = fundamentalService.analyze(m, code);
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("code", r.code());
			item.put("ticker", r.ticker());
			item.put("verdict", r.verdict());
			item.put("verdictEmoji", verdictEmoji(r.verdict()));
			item.put("score", r.score());
			item.put("criticalReds", r.criticalRedFlags());
			item.put("tradingAdvice", r.tradingAdvice());
			item.put("topGreenFlags", r.greenFlags().stream().limit(3).toList());
			item.put("topRedFlags", r.redFlags().stream().limit(3).toList());
			return item;
		}).toList();
		var sorted = results.stream()
				.sorted((a, b) -> verdictOrder((String) a.get("verdict")) - verdictOrder((String) b.get("verdict")))
				.toList();
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("market", m.name());
		resp.put("scanTime", LocalDateTime.now(m.zoneId).toString());
		resp.put("count", codes.size());
		resp.put("summary",
				Map.of("buySupported", sorted.stream().filter(r -> "BUY_SUPPORTED".equals(r.get("verdict"))).count(),
						"watch", sorted.stream().filter(r -> "WATCH".equals(r.get("verdict"))).count(), "skip",
						sorted.stream().filter(r -> "SKIP".equals(r.get("verdict"))).count()));
		resp.put("results", sorted);
		return ResponseEntity.ok(resp);
	}

	@GetMapping("batch")
	public ResponseEntity<Map<String, Object>> getMultiFundamentals(@PathVariable String market,
			@RequestParam List<String> codes, @RequestParam(defaultValue = "false") boolean detail) {
		Market m = parseMarket(market);
		if (m == null)
			return badMarket(market);
		List<String> flat = codes.stream().flatMap(s -> java.util.Arrays.stream(s.split(","))).map(String::trim)
				.filter(s -> !s.isEmpty()).distinct().toList();
		if (flat.isEmpty())
			return ResponseEntity.badRequest()
					.body(Map.of("error", "No stock codes provided", "usage",
							"GET /api/my/fundamental?codes=5243,1155,7277", "alt",
							"GET /api/my/fundamental?codes=5243&codes=1155&codes=7277"));
		if (flat.size() > 20)
			return ResponseEntity.badRequest()
					.body(Map.of("error", "Max 20 stocks per request", "received", flat.size()));
		log.info("=== MULTI FUNDAMENTAL [{}/{}] codes={} detail={} ===", m, m.name(), flat, detail);
		long startMs = System.currentTimeMillis();
		ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
		List<CompletableFuture<Map<String, Object>>> futures = flat.stream()
				.map(code -> CompletableFuture.supplyAsync(() -> {
					ThreadUtil.sleep(300);
					FundamentalResult r = fundamentalService.analyze(m, code);
					return detail ? buildFundamentalResponse(m, r) : buildCompactItem(r);
				}, pool)).toList();
		pool.shutdown();
		List<Map<String, Object>> results = futures.stream().map(f -> {
			try {
				return f.get();
			} catch (Exception e) {
				return Map.<String, Object>of("error", e.getMessage());
			}
		}).sorted((a, b) -> {
			String va = (String) a.getOrDefault("verdict",
					a.containsKey("verdict") ? a.get("verdict") : extractVerdict(a));
			String vb = (String) b.getOrDefault("verdict",
					b.containsKey("verdict") ? b.get("verdict") : extractVerdict(b));
			return verdictOrder(va) - verdictOrder(vb);
		}).toList();
		long elapsed = System.currentTimeMillis() - startMs;
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("market", m.name());
		resp.put("scanTime", LocalDateTime.now(m.zoneId).toString());
		resp.put("elapsedMs", elapsed);
		resp.put("requested", flat.size());
		resp.put("detail", detail);
		resp.put("summary",
				Map.of("BUY_SUPPORTED", results.stream().filter(r -> "BUY_SUPPORTED".equals(extractVerdict(r))).count(),
						"WATCH", results.stream().filter(r -> "WATCH".equals(extractVerdict(r))).count(), "SKIP",
						results.stream().filter(r -> "SKIP".equals(extractVerdict(r))).count(), "ERROR",
						results.stream().filter(r -> r.containsKey("error")).count()));
		resp.put("hint", detail ? "Full metrics shown. Use detail=false for compact view."
				: "Compact view. Add &detail=true for full metrics per stock.");
		resp.put("results", results);
		return ResponseEntity.ok(resp);
	}

	private Map<String, Object> buildCompactItem(FundamentalResult r) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("code", r.code());
		item.put("ticker", r.ticker());
		item.put("verdict", r.verdict());
		item.put("emoji", verdictEmoji(r.verdict()));
		item.put("score", r.score());
		item.put("criticalReds", r.criticalRedFlags());
		item.put("tradingAdvice", r.tradingAdvice());
		Map<String, Object> snap = new LinkedHashMap<>();
		Map<String, Object> met = r.metrics();
		snap.put("reportDate", met.getOrDefault("reportDate", "N/A"));
		snap.put("dataFreshness", met.getOrDefault("dataFreshness", "N/A"));
		snap.put("revenueGrowthQoQ", met.getOrDefault("revenueGrowthQoQ", "N/A"));
		snap.put("earningsGrowthQoQ", met.getOrDefault("earningsGrowthQoQ", "N/A"));
		snap.put("netProfitMargin", met.getOrDefault("netProfitMargin", "N/A"));
		snap.put("epsTrailing", met.getOrDefault("epsTrailing", "N/A"));
		snap.put("epsTrendNextY", met.getOrDefault("epsTrendNextY", "N/A"));
		snap.put("roe", met.getOrDefault("roe", "N/A"));
		snap.put("debtToEquity", met.getOrDefault("debtToEquity", "N/A"));
		snap.put("currentRatio", met.getOrDefault("currentRatio", "N/A"));
		snap.put("freeCashflow", met.getOrDefault("freeCashflow", "N/A"));
		snap.put("trailingPE", met.getOrDefault("trailingPE", "N/A"));
		snap.put("dividendYield", met.getOrDefault("dividendYield", "N/A"));
		snap.put("payoutRatio", met.getOrDefault("payoutRatio", "N/A"));
		item.put("snapshot", snap);
		item.put("greenFlags", r.greenFlags().stream().limit(3).toList());
		item.put("redFlags", r.redFlags().stream().limit(3).toList());
		return item;
	}

	@SuppressWarnings("unchecked")
	private String extractVerdict(Map<String, Object> item) {
		Object v = item.get("verdict");
		if (v instanceof String s)
			return s;
		if (v instanceof Map) {
			Object result = ((Map<String, Object>) v).get("result");
			return result != null ? result.toString() : "SKIP";
		}
		return "SKIP";
	}

	private Map<String, Object> buildFundamentalResponse(Market mkt, FundamentalResult result) {
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("market", mkt.name());
		resp.put("code", result.code());
		resp.put("ticker", result.ticker());
		resp.put("analysisTime", LocalDateTime.now(mkt.zoneId).toString());
		Map<String, Object> verdict = new LinkedHashMap<>();
		verdict.put("result", result.verdict());
		verdict.put("emoji", verdictEmoji(result.verdict()));
		verdict.put("score", result.score());
		verdict.put("detail", result.verdictDetail());
		verdict.put("criticalRedFlags", result.criticalRedFlags());
		resp.put("verdict", verdict);
		resp.put("tradingAdvice", result.tradingAdvice());
		resp.put("greenFlags", result.greenFlags());
		resp.put("redFlags", result.redFlags());
		resp.put("neutralNotes", result.neutralNotes());
		resp.put("metrics", result.metrics());
		resp.put("quickView", buildQuickView(result));
		return resp;
	}

	private Map<String, Object> buildCombinedResponse(Market m, String code, Optional<ScanResult> techOpt,
			FundamentalResult fund) {
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("market", m.name());
		resp.put("code", code.toUpperCase());
		resp.put("analysisTime", LocalDateTime.now(m.zoneId).toString());
		String techDecision = techOpt.map(ScanResult::getDecision).orElse("NO_DATA");
		String fundVerdict = fund.verdict();
		String combined = combinedVerdict(techDecision, fundVerdict, fund.criticalRedFlags());
		Map<String, Object> combinedBlock = new LinkedHashMap<>();
		combinedBlock.put("verdict", combined);
		combinedBlock.put("emoji", combinedEmoji(combined));
		combinedBlock.put("technicalSignal", techDecision);
		combinedBlock.put("fundamentalGrade", fundVerdict);
		combinedBlock.put("interpretation", combinedInterpretation(combined, techDecision, fundVerdict));
		combinedBlock.put("positionSizing", combinedPositionSizing(combined));
		resp.put("combinedVerdict", combinedBlock);
		Map<String, Object> techBlock = new LinkedHashMap<>();
		if (techOpt.isPresent()) {
			ScanResult t = techOpt.get();
			techBlock.put("decision", t.getDecision());
			techBlock.put("score", t.getScore());
			techBlock.put("confidence", t.getConfidence());
			techBlock.put("tradeType", t.getTradeType());
			techBlock.put("candlePattern", t.getCandlePattern());
			techBlock.put("closePrice", t.getClosePrice());
			techBlock.put("volumeRatio", String.format("%.2fx", t.getVolumeRatio()));
			techBlock.put("rsi", t.getRsi());
			techBlock.put("adx", t.getAdx());
			techBlock.put("macdHistogram", t.getMacdHistogram());
			techBlock.put("macdDepth", t.getMacdDepth());
			if ("BUY".equals(t.getDecision()) || "WATCH".equals(t.getDecision())) {
				techBlock.put("entryPlan",
						Map.of("entryIdeal", t.getEntryPrice(), "entryMax", t.getEntryMax(), "stopLoss",
								t.getStopLoss(), "tp1_40pct", t.getTargetTP1(), "tp2_40pct", t.getTargetTP2(),
								"tp3_20pct", t.getTargetTP3(), "riskReward", "1:" + t.getRiskReward(), "scalpEntry",
								t.getScalpEntry(), "scalpSL", t.getScalpSL(), "scalpTP1", t.getScalpTP1()));
			}
			techBlock.put("reasons", t.getReasons());
			techBlock.put("warnings", t.getWarnings());
		} else {
			techBlock.put("decision", "NO_DATA");
			techBlock.put("note", "Could not fetch price history from Yahoo Finance");
		}
		resp.put("technical", techBlock);
		Map<String, Object> fundBlock = new LinkedHashMap<>();
		fundBlock.put("verdict", fund.verdict());
		fundBlock.put("score", fund.score());
		fundBlock.put("criticalReds", fund.criticalRedFlags());
		fundBlock.put("greenFlags", fund.greenFlags());
		fundBlock.put("redFlags", fund.redFlags());
		fundBlock.put("tradingAdvice", fund.tradingAdvice());
		fundBlock.put("metrics", fund.metrics());
		fundBlock.put("quickView", buildQuickView(fund));
		resp.put("fundamental", fundBlock);
		return resp;
	}

	private Map<String, Object> buildQuickView(FundamentalResult r) {
		Map<String, Object> q = new LinkedHashMap<>();
		Map<String, Object> m = r.metrics();
		q.put("Revenue Growth QoQ", m.getOrDefault("revenueGrowthQoQ", "N/A"));
		q.put("Revenue Growth YoY", m.getOrDefault("revenueGrowthYoY", "N/A"));
		q.put("Net Profit Margin", m.getOrDefault("netProfitMargin", "N/A"));
		q.put("Earnings Growth", m.getOrDefault("earningsGrowthQoQ", "N/A"));
		q.put("EPS (Trailing)", m.getOrDefault("epsTrailing", "N/A"));
		q.put("EPS (Forward)", m.getOrDefault("epsForward", "N/A"));
		q.put("EPS Trend (Next Y)", m.getOrDefault("epsTrendNextY", "N/A"));
		q.put("ROE", m.getOrDefault("roe", "N/A"));
		q.put("Debt / Equity", m.getOrDefault("debtToEquity", "N/A"));
		q.put("Current Ratio", m.getOrDefault("currentRatio", "N/A"));
		q.put("Free Cash Flow", m.getOrDefault("freeCashflow", "N/A"));
		q.put("P/E (Trailing)", m.getOrDefault("trailingPE", "N/A"));
		q.put("P/E (Forward)", m.getOrDefault("forwardPE", "N/A"));
		q.put("Dividend Yield", m.getOrDefault("dividendYield", "N/A"));
		q.put("Payout Ratio", m.getOrDefault("payoutRatio", "N/A"));
		return q;
	}

	private String combinedVerdict(String tech, String fund, long critReds) {
		if (critReds >= 2)
			return "SKIP";
		return switch (tech) {
		case "BUY" -> switch (fund) {
		case "BUY_SUPPORTED" -> "STRONG_BUY";
		case "WATCH" -> "BUY";
		default -> "CAUTIOUS";
		};
		case "WATCH" -> switch (fund) {
		case "BUY_SUPPORTED" -> "WATCH_QUALITY";
		default -> "WATCH";
		};
		default -> "SKIP";
		};
	}

	private String combinedEmoji(String v) {
		return switch (v) {
		case "STRONG_BUY" -> "🏆";
		case "BUY" -> "✅";
		case "CAUTIOUS" -> "⚠️";
		case "WATCH_QUALITY" -> "⭐";
		case "WATCH" -> "👀";
		default -> "🚫";
		};
	}

	private String combinedInterpretation(String combined, String tech, String fund) {
		return switch (combined) {
		case "STRONG_BUY" ->
			"Technical BUY signal confirmed by strong fundamentals. Execute with full confidence grade sizing. Both engines agree — this is the best trade setup.";
		case "BUY" ->
			"Technical BUY signal with mixed fundamentals. Execute at 70% normal position. The chart says buy, the fundamentals are neutral. Use TP1 as primary target.";
		case "CAUTIOUS" ->
			"Technical BUY signal BUT fundamentals are deteriorating. High risk. Scalp only (same day, strict SL). The MACD cross may be a dead-cat bounce. Avoid overnight hold.";
		case "WATCH_QUALITY" ->
			"Strong fundamentals but no technical BUY signal yet. Put on watchlist. When MACD cross appears on this stock, it will be a high-conviction trade.";
		case "WATCH" ->
			"Neither technical nor fundamental strongly supports buying now. Monitor and reassess next scan.";
		default -> "Insufficient signals or critical red flags detected. Skip this trade.";
		};
	}

	private String combinedPositionSizing(String combined) {
		return switch (combined) {
		case "STRONG_BUY" -> "Full position size per confidence grade (Grade A=100%, B=70%, C=50%)";
		case "BUY" -> "Reduce by 30% — mixed signals. Grade A→70%, Grade B→50%, Grade C→SKIP";
		case "CAUTIOUS" -> "Scalp only. 30-40% of normal size. Strict same-day exit. No overnight.";
		case "WATCH_QUALITY" -> "No trade yet. Set alert for MACD cross. When triggered: full size.";
		case "WATCH" -> "No trade. Monitor only.";
		default -> "Do not trade.";
		};
	}

	private String verdictEmoji(String verdict) {
		return switch (verdict) {
		case "BUY_SUPPORTED" -> "✅";
		case "WATCH" -> "⚠️";
		case "SKIP" -> "🚫";
		default -> "❓";
		};
	}

	private int verdictOrder(String verdict) {
		return switch (verdict) {
		case "BUY_SUPPORTED" -> 0;
		case "WATCH" -> 1;
		case "SKIP" -> 2;
		default -> 3;
		};
	}

	private Market parseMarket(String raw) {
		try {
			return Market.from(raw);
		} catch (Exception e) {
			return null;
		}
	}

	private ResponseEntity<Map<String, Object>> badMarket(String raw) {
		return ResponseEntity.badRequest().body(Map.of("error", "Unknown market: " + raw, "valid", "MY | US"));
	}
}