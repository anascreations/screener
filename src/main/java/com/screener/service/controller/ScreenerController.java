package com.screener.service.controller;

import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.screener.service.dto.Level2Request;
import com.screener.service.dto.MaCalculationRequest;
import com.screener.service.dto.PullbackRequest;
import com.screener.service.dto.PullbackResult;
import com.screener.service.dto.TrendDistanceRequest;
import com.screener.service.enums.Market;
import com.screener.service.model.ScanResult;
import com.screener.service.repository.ScanResultRepository;
import com.screener.service.service.AnalysisService;
import com.screener.service.service.CalculationService;
import com.screener.service.service.ExchangeMyService;
import com.screener.service.service.OcrService;
import com.screener.service.service.PullbackService;
import com.screener.service.service.ScannerService;
import com.screener.service.service.TradingCalendarService;
import com.screener.service.util.ThreadUtil;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ═══════════════════════════════════════════════════════════════ UNIFIED
 * SCREENER CONTROLLER
 *
 * Base path: /api/{market} where market = MY | US (case-insensitive)
 *
 * Endpoints: ──────────────────────────────────────────────────────────────
 * POST /api/{market}/scan/{code} — Single stock scan POST
 * /api/{market}/scan/batch — Batch scan (≤ 20 stocks) GET
 * /api/{market}/scan/range — Full market range scan (main endpoint) GET
 * /api/{market}/watchlist/tomorrow — Tomorrow's trade list from DB GET
 * /api/{market}/result/today — Today's DB results GET
 * /api/{market}/result/{code} — Historical results for one stock
 *
 * MY examples: POST /api/my/scan/5243 GET
 * /api/my/scan/range?minPrice=0.10&maxPrice=1.00&minScore=70 GET
 * /api/my/watchlist/tomorrow
 *
 * US examples: POST /api/us/scan/AAPL GET
 * /api/us/scan/range?minPrice=5&maxPrice=50&exchange=NASDAQ GET
 * /api/us/watchlist/tomorrow
 * ═══════════════════════════════════════════════════════════════
 */
@Slf4j
@RestController
@RequestMapping("api/{market}")
@RequiredArgsConstructor
public class ScreenerController {
	private final AnalysisService analysisService;
	private final ScannerService scannerService;
	private final ScanResultRepository scanRepo;
	private final TradingCalendarService calendarService;
	private final ExchangeMyService exchangeMyService;
	private final PullbackService pullbackService;
	private final OcrService ocrService;
	private final CalculationService calcService;

	@PostMapping(value = "level2/analyze-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<Map<String, Object>> analyzeImage(@RequestParam("image") MultipartFile file) {
		try {
			BufferedImage image = ImageIO.read(file.getInputStream());
			if (image == null) {
				return ResponseEntity.badRequest()
						.body(Map.of("error", "Cannot read image — unsupported format or corrupted file"));
			}
			Level2Request extracted = ocrService.extract(image);
			if (extracted.bids().isEmpty() || extracted.asks().isEmpty()) {
				return ResponseEntity.unprocessableEntity().body(Map.of("error", "OCR could not detect bid/ask rows",
						"advice", "Ensure image is clear, unrotated, and contains visible price/volume columns"));
			}
			Map<String, Object> analysis = calcService.calculate(extracted);
			Map<String, Object> resp = new LinkedHashMap<>();
			resp.put("status", "OK");
			resp.put("rowsExtracted", Map.of("bids", extracted.bids().size(), "asks", extracted.asks().size()));
			resp.put("extractedData", extracted);
			resp.put("analysis", analysis);
			return ResponseEntity.ok(resp);
		} catch (Exception e) {
			return ResponseEntity.internalServerError()
					.body(Map.of("error", "Processing failed", "detail", e.getMessage()));
		}
	}

	// ─────────────────────────────────────────────────────────────────────────
	// POST /api/{market}/scan/{code} — single stock
	// MY: POST /api/my/scan/5243
	// US: POST /api/us/scan/AAPL
	// ─────────────────────────────────────────────────────────────────────────
	@PostMapping("scan/{code}")
	public ResponseEntity<Map<String, Object>> scanOne(@PathVariable String market, @PathVariable String code) {
		Market m = parseMarket(market);
		if (m == null)
			return badMarket(market);
		log.info("=== MANUAL SCAN [{}/{}] ===", m, code.toUpperCase());
		Optional<ScanResult> result = analysisService.scan(m, code);
		if (result.isEmpty()) {
			return ResponseEntity.ok(Map.of("market", m.name(), "code", code.toUpperCase(), "error",
					"No data from Yahoo Finance or insufficient history. Retry after market close."));
		}
		return ResponseEntity.ok(buildSingleResponse(m, result.get()));
	}

	// ─────────────────────────────────────────────────────────────────────────
	// POST /api/{market}/scan/batch — up to 20 stocks
	// Body: {"codes":["5243","1155"]} (MY) or {"codes":["AAPL","MSFT"]} (US)
	// ─────────────────────────────────────────────────────────────────────────
	@PostMapping("scan/batch")
	public ResponseEntity<Map<String, Object>> scanBatch(@PathVariable String market,
			@RequestBody Map<String, List<String>> body) {
		Market m = parseMarket(market);
		if (m == null)
			return badMarket(market);
		List<String> codes = body.getOrDefault("codes", Collections.emptyList());
		if (codes.isEmpty())
			return ResponseEntity.badRequest()
					.body(Map.of("error", "Empty code list.", "format", "{\"codes\":[\"5243\",\"1155\"]}"));
		if (codes.size() > 20)
			return ResponseEntity.badRequest().body(Map.of("error", "Max 20 stocks per batch."));
		log.info("=== BATCH SCAN [{}/{}] ===", m, codes.size());
		List<Map<String, Object>> results = new ArrayList<>();
		int buy = 0, watch = 0, ignore = 0;
		for (String code : codes) {
			ThreadUtil.sleep(400);
			Optional<ScanResult> res = analysisService.scan(m, code);
			if (res.isPresent()) {
				ScanResult r = res.get();
				results.add(buildCompactResult(m, r));
				switch (r.getDecision()) {
				case "BUY" -> buy++;
				case "WATCH" -> watch++;
				default -> ignore++;
				}
			}
		}
		results.sort(Comparator.comparingInt(x -> decisionOrder((String) x.get("decision"))));
		return ResponseEntity
				.ok(Map.of("market", m.name(), "summary", Map.of("buy", buy, "watch", watch, "ignore", ignore),
						"scanTime", LocalDateTime.now(m.zoneId).toString(), "results", results));
	}

	// ─────────────────────────────────────────────────────────────────────────
	// GET /api/{market}/scan/range — MAIN SCAN ENDPOINT
	//
	// Discovers candidates via Yahoo screener then runs full technical analysis.
	// Best time to run: after market close.
	//
	// MY: GET /api/my/scan/range?minPrice=0.10&maxPrice=1.00
	// GET /api/my/scan/range?minPrice=0.50&maxPrice=2.00&minScore=75
	// US: GET /api/us/scan/range?minPrice=5&maxPrice=50&exchange=NASDAQ
	// GET /api/us/scan/range?minPrice=10&maxPrice=100&exchange=ALL&minScore=75
	// ─────────────────────────────────────────────────────────────────────────
	@GetMapping("scan/range")
	public ResponseEntity<Map<String, Object>> scanByRange(@PathVariable String market, @RequestParam double minPrice,
			@RequestParam double maxPrice, @RequestParam(defaultValue = "70") double minScore,
			@RequestParam(defaultValue = "ALL") String exchange) {
		Market m = parseMarket(market);
		if (m == null)
			return badMarket(market);
		if (minPrice <= 0 || maxPrice <= 0)
			return ResponseEntity.badRequest().body(Map.of("error", "Prices must be > 0"));
		if (minPrice >= maxPrice)
			return ResponseEntity.badRequest().body(Map.of("error", "minPrice must be < maxPrice"));
		log.info("=== RANGE SCAN [{}/{}] {}-{} minScore:{} exchange:{} ===", m, m.name(), m.formatPrice(minPrice),
				m.formatPrice(maxPrice), (int) minScore, exchange);
		List<ScanResult> buySignals = scannerService.scanByPriceRange(m, minPrice, maxPrice, minScore, exchange);
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("market", m.name());
		resp.put("range", m.formatPrice(minPrice) + " — " + m.formatPrice(maxPrice));
		resp.put("minScore", (int) minScore);
		resp.put("exchange", exchange);
		resp.put("scanTime", LocalDateTime.now(m.zoneId).toString());
		resp.put("tradeDate", nextTradingDay(m, LocalDate.now(m.zoneId)).toString());
		resp.put("buyCount", buySignals.size());
		resp.put("note", "Full analysis in server console. Only BUY signals with score >= minScore are returned.");
		resp.put("buySignals", buySignals.stream().map(r -> buildFullResult(m, r)).collect(Collectors.toList()));
		return ResponseEntity.ok(resp);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// GET /api/{market}/watchlist/tomorrow — tomorrow's trade list from DB
	// ─────────────────────────────────────────────────────────────────────────
	@GetMapping("watchlist/tomorrow")
	public ResponseEntity<Map<String, Object>> getWatchlistTomorrow(@PathVariable String market,
			@RequestParam(required = false) Double minPrice, @RequestParam(required = false) Double maxPrice,
			@RequestParam(defaultValue = "65") double minScore, @RequestParam(defaultValue = "BUY") String decision) {
		Market m = parseMarket(market);
		if (m == null)
			return badMarket(market);
		LocalDate today = LocalDate.now(m.zoneId);
		LocalDate tradeDate = nextTradingDay(m, today);
		List<ScanResult> all = scanRepo.findByMarketAndScanDateOrderByScoreDesc(m, today);
		List<ScanResult> filtered = all.stream()
				.filter(r -> "all".equalsIgnoreCase(decision) || r.getDecision().equalsIgnoreCase(decision))
				.filter(r -> r.getScore() >= minScore).filter(r -> minPrice == null || r.getEntryPrice() >= minPrice)
				.filter(r -> maxPrice == null || r.getEntryPrice() <= maxPrice)
				.sorted(Comparator.comparingDouble(ScanResult::getScore).reversed()).collect(Collectors.toList());
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("market", m.name());
		resp.put("tradeDate", tradeDate.toString());
		resp.put("scanDate", today.toString());
		resp.put("filters", buildFilters(m, decision, minScore, minPrice, maxPrice));
		resp.put("todaySummary",
				Map.of("totalScanned", (long) all.size(), "buy",
						all.stream().filter(r -> "BUY".equals(r.getDecision())).count(), "watch",
						all.stream().filter(r -> "WATCH".equals(r.getDecision())).count(), "ignore",
						all.stream().filter(r -> "IGNORE".equals(r.getDecision())).count()));
		resp.put("matchedCount", filtered.size());
		List<Map<String, Object>> buyList = buildWatchlistItems(m, filtered, "BUY");
		List<Map<String, Object>> watchList = buildWatchlistItems(m, filtered, "WATCH");
		if (!buyList.isEmpty())
			resp.put("buySignals", buyList);
		if (!watchList.isEmpty())
			resp.put("watchSignals", watchList);
		if (filtered.isEmpty())
			resp.put("message", "No signals found. Run GET /api/" + market + "/scan/range first.");
		return ResponseEntity.ok(resp);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// GET /api/{market}/result/today — DB results for today
	// ─────────────────────────────────────────────────────────────────────────
	@GetMapping("result/today")
	public ResponseEntity<Map<String, Object>> getTodayResults(@PathVariable String market) {
		Market m = parseMarket(market);
		if (m == null)
			return badMarket(market);
		LocalDate today = LocalDate.now(m.zoneId);
		List<ScanResult> all = scanRepo.findByMarketAndScanDateOrderByScoreDesc(m, today);
		List<ScanResult> buys = all.stream().filter(r -> "BUY".equals(r.getDecision())).toList();
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("market", m.name());
		resp.put("date", today.toString());
		resp.put("scanned", all.size());
		resp.put("summary",
				Map.of("buy", all.stream().filter(r -> "BUY".equals(r.getDecision())).count(), "watch",
						all.stream().filter(r -> "WATCH".equals(r.getDecision())).count(), "ignore",
						all.stream().filter(r -> "IGNORE".equals(r.getDecision())).count()));
		resp.put("buySignals", buys.stream().map(r -> buildFullResult(m, r)).collect(Collectors.toList()));
		return ResponseEntity.ok(resp);
	}

	// ─────────────────────────────────────────────────────────────────────────
	// GET /api/{market}/result/{code} — historical results for one stock
	// ─────────────────────────────────────────────────────────────────────────
	@GetMapping("result/{code}")
	public ResponseEntity<?> getHistory(@PathVariable String market, @PathVariable String code) {
		Market m = parseMarket(market);
		if (m == null)
			return badMarket(market);
		List<ScanResult> history = scanRepo.findByMarketAndStockCodeOrderByScannedAtDesc(m, code.toUpperCase());
		if (history.isEmpty())
			return ResponseEntity.ok(Map.of("message", "No records for " + code.toUpperCase() + " [" + m.name() + "]"));
		return ResponseEntity.ok(history);
	}

	private Map<String, Object> buildSingleResponse(Market m, ScanResult r) {
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("market", m.name());
		resp.put("code", m.fullTicker(r.getStockCode()));
		resp.put("name", r.getStockName());
		resp.put("decision", r.getDecision());
		resp.put("score", r.getScore());
		resp.put("scanTime", r.getScannedAt().toString());
		resp.put("tradeDate", r.getTradeDate().toString());
		if (!"IGNORE".equals(r.getDecision()))
			resp.put("price", buildPriceBlock(m, r));
		resp.put("indicators", buildIndicators(r));
		resp.put("reasons", r.getReasons());
		resp.put("warnings", r.getWarnings());
		return resp;
	}

	private Map<String, Object> buildCompactResult(Market m, ScanResult r) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("code", m.fullTicker(r.getStockCode()));
		map.put("name", r.getStockName());
		map.put("decision", r.getDecision());
		map.put("score", r.getScore());
		if (!"IGNORE".equals(r.getDecision())) {
			map.put("entry", r.getEntryPrice());
			map.put("sl", r.getStopLoss());
			map.put("tp1", r.getTargetTP1());
		}
		return map;
	}

	private Map<String, Object> buildFullResult(Market m, ScanResult r) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("code", m.fullTicker(r.getStockCode()));
		map.put("name", r.getStockName());
		map.put("score", r.getScore());
		map.put("closePrice", r.getClosePrice());
		map.put("volumeRatio", String.format("%.2fx", r.getVolumeRatio()));
		map.put("price", buildPriceBlock(m, r));
		map.put("indicators", buildIndicators(r));
		map.put("reasons", r.getReasons());
		if (r.getWarnings() != null && !r.getWarnings().isBlank())
			map.put("warnings", r.getWarnings());
		return map;
	}

	private Map<String, Object> buildPriceBlock(Market m, ScanResult r) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("buyRange", m.formatPrice(r.getEntryPrice()) + " — " + m.formatPrice(r.getEntryMax()));
		p.put("entryIdeal", r.getEntryPrice());
		p.put("entryMax", r.getEntryMax());
		p.put("stopLoss", r.getStopLoss());
		p.put("tp1_40pct", r.getTargetTP1());
		p.put("tp2_40pct", r.getTargetTP2());
		p.put("tp3_20pct", r.getTargetTP3());
		p.put("riskReward", "1:" + r.getRiskReward());
		return p;
	}

	private Map<String, Object> buildIndicators(ScanResult r) {
		return Map.of("rsi", r.getRsi(), "macdHist", r.getMacdHistogram(), "adx", r.getAdx(), "ema9", r.getEma9(),
				"ema21", r.getEma21(), "ema50", r.getEma50(), "atr", r.getAtr());
	}

	private List<Map<String, Object>> buildWatchlistItems(Market m, List<ScanResult> results, String type) {
		return results.stream().filter(r -> type.equals(r.getDecision())).map(r -> {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("code", m.fullTicker(r.getStockCode()));
			item.put("name", r.getStockName());
			item.put("score", r.getScore());
			item.put("closePrice", r.getClosePrice());
			item.put("volumeRatio", String.format("%.2fx", r.getVolumeRatio()));
			item.put("tradeDate", r.getTradeDate().toString());
			item.put("price", buildPriceBlock(m, r));
			item.put("indicators", buildIndicators(r));
			item.put("action", buildAction(m, r));
			item.put("reasons", r.getReasons());
			if (r.getWarnings() != null && !r.getWarnings().isBlank())
				item.put("warnings", r.getWarnings());
			return item;
		}).collect(Collectors.toList());
	}

	private Map<String, Object> buildAction(Market m, ScanResult r) {
		return Map.of("limitBuyAt", r.getEntryPrice(), "skipIfAbove", r.getEntryMax(), "setStopLoss", r.getStopLoss(),
				"guide", String.format("At open: if price ≤ %s → LIMIT BUY. If > %s → SKIP.",
						m.formatPrice(r.getEntryPrice()), m.formatPrice(r.getEntryMax())));
	}

	private Map<String, Object> buildFilters(Market m, String decision, double minScore, Double minPrice,
			Double maxPrice) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("decision", decision);
		f.put("minScore", minScore);
		if (minPrice != null)
			f.put("minPrice", m.formatPrice(minPrice));
		if (maxPrice != null)
			f.put("maxPrice", m.formatPrice(maxPrice));
		return f;
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

	private LocalDate nextTradingDay(Market m, LocalDate from) {
		if (m == Market.MY)
			return calendarService.nextTradingDay(from);
		LocalDate d = from.plusDays(1);
		while (d.getDayOfWeek().getValue() >= 6)
			d = d.plusDays(1);
		return d;
	}

	// ─────────────────────────────────────────────────────────────────────────
	// GET /api/ma/calculation/{marketPrice}/{ma5}/{ma20}
	//
	// MA Position & Risk Calculator
	//
	// Validates two conditions before calculating:
	// 1. marketPrice must be above BOTH ma5 AND ma20 (bullish alignment)
	// 2. ma5 must be above ma20 (short-term > long-term = uptrend)
	//
	// If either condition fails → SKIP with reason.
	// If both pass → calculate how far price is above ma20 and label risk.
	//
	// Risk label based on % above MA20:
	// < 5% → Low Risk (price just crossed, early entry)
	// 5% – 10% → Medium Risk (momentum building, still tradeable)
	// > 10% → High Risk (extended, chasing — avoid new entry)
	//
	// Example:
	// GET /api/ma/calculation/1.42/1.38/1.31
	// marketPrice=1.42, ma5=1.38, ma20=1.31
	// % above MA20 = (1.42 - 1.31) / 1.31 * 100 = 8.40% → Medium Risk
	// ─────────────────────────────────────────────────────────────────────────
	@GetMapping("ma/calculation/{marketPrice}/{ma5}/{ma20}")
	public ResponseEntity<Map<String, Object>> maCalculation(@PathVariable double marketPrice, @PathVariable double ma5,
			@PathVariable double ma20, @RequestParam(defaultValue = "DAILY") String timeframe) {
		TimeframeConfig tf = TimeframeConfig.of(timeframe);
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("input", Map.of("marketPrice", marketPrice, "ma5", ma5, "ma20", ma20, "timeframe", tf.label(),
				"holdDuration", tf.holdDuration()));
		// ── Validation 1: price must be at or above MA20 (key support level) ──
		// Price below MA20 = support broken = SKIP
		// Price below MA5 but above/at MA20 = pullback to MA20 = valid entry
		// opportunity
		if (marketPrice < ma20) {
			resp.put("decision", "SKIP");
			resp.put("emoji", "🚫");
			resp.put("reason", String.format("Price (%.4f) is below MA20 (%.4f) — support broken. "
					+ "MA20 must hold as support for this setup to be valid.", marketPrice, ma20));
			resp.put("detail", Map.of("priceVsMA20", String.format("%.4f below MA20 (%.4f)", ma20 - marketPrice, ma20),
					"note", "Price below MA20 = downtrend. Wait for price to reclaim MA20."));
			return ResponseEntity.ok(resp);
		}
		// ── Validation 2: MA5 must be at or above MA20 (golden cross confirmed) ─
		if (ma5 < ma20) {
			resp.put("decision", "SKIP");
			resp.put("emoji", "🚫");
			resp.put("reason", String.format("MA5 (%.4f) is below MA20 (%.4f) — golden cross has not occurred. "
					+ "Short-term average must cross above long-term before entering.", ma5, ma20));
			resp.put("detail",
					Map.of("ma5AboveMA20", false, "gap",
							String.format("MA5 is %.4f (%.2f%%) below MA20", ma20 - ma5, (ma20 - ma5) / ma20 * 100),
							"note", "Wait for MA5 ≥ MA20 golden cross."));
			return ResponseEntity.ok(resp);
		}
		// ── Calculation: how far is price above MA20? ─────────────────────────
		double pctAboveMA20 = (marketPrice - ma20) / ma20 * 100;
		double pctAboveMA5 = (marketPrice - ma5) / ma5 * 100;
		double ma5AboveMA20 = (ma5 - ma20) / ma20 * 100;
		// ── Risk level: fixed thresholds per spec ─────────────────────────────
		// 1% – 5% → Low Risk → PROCEED (price just above MA20, ideal entry)
		// 5% – 10% → Medium Risk → PROCEED (extended but acceptable, reduce size)
		// > 10% → High Risk → SKIP (overextended, wait for pullback)
		// < 1% → At MA20 → PROCEED (price holding MA20 as support)
		String risk;
		String riskEmoji;
		String riskDecision;
		String riskAdvice;
		if (pctAboveMA20 > 10.0) {
			risk = "High Risk";
			riskEmoji = "🔴";
			riskDecision = "SKIP";
			riskAdvice = String.format(
					"[%s] Price is %.2f%% above MA20 — overextended (> 10%%). "
							+ "High probability of mean reversion back to MA20. Do NOT enter now. "
							+ "Wait for price to pull back to MA5 (%.4f) or MA20 (%.4f) before re-evaluating.",
					tf.label(), pctAboveMA20, ma5, ma20);
		} else if (pctAboveMA20 >= 5.0) {
			risk = "Medium Risk";
			riskEmoji = "🟡";
			riskDecision = "PROCEED";
			riskAdvice = String.format(
					"[%s] Price is %.2f%% above MA20 — medium-risk zone (5%%–10%%). "
							+ "Still tradeable. Reduce position size by 30–50%%. "
							+ "Ideal entry: wait for pullback to MA5 (%.4f). Expected hold: %s.",
					tf.label(), pctAboveMA20, ma5, tf.holdDuration());
		} else {
			risk = "Low Risk";
			riskEmoji = "🟢";
			riskDecision = "PROCEED";
			riskAdvice = String.format("[%s] Price is %.2f%% above MA20 — low-risk zone (≤ 5%%). "
					+ "Price is close to MA20 support. Early entry, full position size allowed. "
					+ "Expected hold: %s.", tf.label(), pctAboveMA20, tf.holdDuration());
		}
		resp.put("decision", riskDecision);
		resp.put("emoji", riskEmoji);
		resp.put("risk", risk);
		resp.put("advice", riskAdvice);
		resp.put("calculation",
				Map.of("formula", "(marketPrice - MA20) / MA20 × 100", "pctAboveMA20",
						String.format("%.2f%%", pctAboveMA20), "pctAboveMA5", String.format("%.2f%%", pctAboveMA5),
						"ma5AboveMA20", String.format("%.2f%%", ma5AboveMA20), "result",
						String.format("(%.4f - %.4f) / %.4f × 100 = %.2f%%", marketPrice, ma20, ma20, pctAboveMA20),
						"thresholds", "≤5% = Low Risk PROCEED | 5–10% = Medium Risk PROCEED | >10% = High Risk SKIP"));
		// High Risk — skip, no trade plan
		if ("SKIP".equals(riskDecision)) {
			resp.put("maAlignment", Map.of("priceAboveMA5", true, "priceAboveMA20", true, "ma5AboveMA20", true, "trend",
					"⚠️ Bullish but overextended — wait for pullback"));
			return ResponseEntity.ok(resp);
		}
		// ── Trade plan (Low and Medium Risk only) ─────────────────────────────
		double entryIdeal = round4(ma5);
		double entryMax = round4(marketPrice);
		double stopLoss = round4(ma20 * (1.0 - tf.slBuffer()));
		double risk_R = entryIdeal - stopLoss;
		double tp1 = round4(entryIdeal + risk_R * 1.0);
		double tp2 = round4(entryIdeal + risk_R * 2.0);
		double tp3 = round4(entryIdeal + risk_R * 3.5);
		if (pctAboveMA5 <= 0.5) {
			entryIdeal = round4(marketPrice);
			risk_R = entryIdeal - stopLoss;
			tp1 = round4(entryIdeal + risk_R * 1.0);
			tp2 = round4(entryIdeal + risk_R * 2.0);
			tp3 = round4(entryIdeal + risk_R * 3.5);
		}
		resp.put("maAlignment", Map.of("priceAboveMA5", true, "priceAboveMA20", true, "ma5AboveMA20", true, "trend",
				"✅ Bullish — Price > MA5 > MA20"));
		Map<String, Object> tradePlan = calculationPlan(marketPrice, ma20, tf, entryIdeal, entryMax, stopLoss, risk_R,
				tp1, tp2, tp3);
		resp.put("tradePlan", tradePlan);
		return ResponseEntity.ok(resp);
	}

	@PostMapping("ma/calculation")
	public ResponseEntity<Map<String, Object>> maCalculation(@RequestBody @Valid MaCalculationRequest request) {
		return ResponseEntity.ok(calcService.maCalculate(request));
	}

	@PostMapping("ma/trend-distance")
	public ResponseEntity<Map<String, Object>> trendDistance(@RequestBody @Valid TrendDistanceRequest request) {
		return ResponseEntity.ok(calcService.trendDistance(request));
	}

	@GetMapping("pullback")
	public ResponseEntity<?> detectPullback(@RequestParam String code,
			@RequestParam(defaultValue = "1d") String interval, @RequestParam(defaultValue = "20") int limit) {
		try {
			List<Double> prices = exchangeMyService.fetchPriceHistory(code, interval, limit + 10);
			if (prices.size() < 5) {
				return ResponseEntity.badRequest()
						.body(Map.of("error", "Insufficient price history for " + code.toUpperCase(), "got",
								prices.size(), "need", "minimum 5", "advice", "Stock may be newly listed or delisted"));
			}
			int availablePeriod20 = Math.min(20, prices.size());
			int availablePeriod5 = Math.min(5, prices.size());
			double ma5 = exchangeMyService.calcMA(prices, availablePeriod5);
			double ma20 = exchangeMyService.calcMA(prices, availablePeriod20);
			double current = prices.get(prices.size() - 1);
			PullbackResult result = pullbackService.detect(new PullbackRequest(prices, current, ma5, ma20));
			Map<String, Object> resp = new LinkedHashMap<>();
			resp.put("code", code.toUpperCase());
			resp.put("interval", interval);
			resp.put("candles", prices.size());
			resp.put("currentPrice", current);
			resp.put("ma5", Math.round(ma5 * 10000.0) / 10000.0);
			resp.put("ma20", Math.round(ma20 * 10000.0) / 10000.0);
			if (availablePeriod20 < 20) {
				resp.put("dataWarning", String.format(
						"Only %d candles available — MA20 calculated using MA%d. " + "Results may be less reliable.",
						prices.size(), availablePeriod20));
			}
			resp.put("pullback", result);
			return ResponseEntity.ok(resp);
		} catch (Exception e) {
			return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
		}
	}

	private Map<String, Object> calculationPlan(double marketPrice, double ma20, TimeframeConfig tf, double entryIdeal,
			double entryMax, double stopLoss, double risk_R, double tp1, double tp2, double tp3) {
		Map<String, Object> tradePlan = new LinkedHashMap<>();
		tradePlan.put("entryIdeal", entryIdeal);
		tradePlan.put("entryMax", entryMax);
		tradePlan.put("entryNote",
				entryIdeal < marketPrice
						? String.format("Queue at MA5 (%.4f). If no pullback, max entry = current price (%.4f).",
								entryIdeal, entryMax)
						: String.format("Enter at current price (%.4f) — already at MA5 level.", entryIdeal));
		tradePlan.put("stopLoss", stopLoss);
		tradePlan.put("stopLossNote",
				String.format("%.4f%% buffer below MA20 (%.4f). MA20 break = trend invalid, exit immediately.",
						tf.slBuffer() * 100, ma20));
		tradePlan.put("riskPerUnit", round4(risk_R));
		tradePlan.put("tp1", tp1);
		tradePlan.put("tp1Note", String.format("Sell 40%% at %.4f  (R:R 1:1.0) — move SL to breakeven", tp1));
		tradePlan.put("tp2", tp2);
		tradePlan.put("tp2Note", String.format("Sell 40%% at %.4f  (R:R 1:2.0)", tp2));
		tradePlan.put("tp3", tp3);
		tradePlan.put("tp3Note", String.format("Sell 20%% at %.4f  (R:R 1:3.5) — trail or hold", tp3));
		return tradePlan;
	}

	private int decisionOrder(String d) {
		return switch (d) {
		case "BUY" -> 0;
		case "WATCH" -> 1;
		default -> 2;
		};
	}

	// ─────────────────────────────────────────────────────────────────────────
	// TIMEFRAME CONFIG
	// Centralised thresholds so both MA and EMA endpoints share the same logic.
	//
	// label : display name in response
	// lowThreshold : % above MA20/EMA21 = LOW RISK ceiling
	// highThreshold: % above MA20/EMA21 = MEDIUM RISK ceiling (above = HIGH)
	// slBuffer : fractional buffer below support for stop loss (0.001 = 0.1%)
	// holdDuration : expected trade hold time for guidance text
	//
	// Why thresholds tighten on lower timeframes:
	// On a daily chart a 7% extension takes days to form.
	// On a 15-min chart a 7% extension happens in minutes — mean reversion is
	// instant.
	// Lower timeframes need tighter bands to give accurate risk guidance.
	// ─────────────────────────────────────────────────────────────────────────
	private record TimeframeConfig(String label, double lowThreshold, double highThreshold, double slBuffer,
			String holdDuration) {
		static TimeframeConfig of(String raw) {
			return switch (raw.toUpperCase().trim().replace("-", "").replace("_", "").replace(" ", "")) {
			case "15M", "15MIN", "15MINUTE", "15MINUTES" ->
				new TimeframeConfig("15M", 1.0, 3.0, 0.0010, "15–60 minutes");
			case "30M", "30MIN", "30MINUTE", "30MINUTES" ->
				new TimeframeConfig("30M", 1.5, 3.5, 0.0010, "30 min – 2 hours");
			case "1H", "60M", "1HOUR", "HOURLY" -> new TimeframeConfig("1H", 1.5, 4.0, 0.0015, "1–4 hours");
			case "2H", "2HOUR" -> new TimeframeConfig("2H", 2.0, 5.0, 0.0020, "2–8 hours");
			case "4H", "4HOUR" -> new TimeframeConfig("4H", 2.0, 5.0, 0.0030, "4–24 hours");
			case "DAILY", "1D", "D", "DAY" -> new TimeframeConfig("DAILY", 3.0, 7.0, 0.0010, "2–5 days");
			case "WEEKLY", "1W", "W", "WEEK" -> new TimeframeConfig("WEEKLY", 5.0, 12.0, 0.0050, "2–6 weeks");
			default -> new TimeframeConfig("DAILY (default)", 3.0, 7.0, 0.0010, "2–5 days");
			};
		}
	}

	/** Rounds to 4 decimal places — avoids floating point noise in price fields */
	private double round4(double v) {
		return Math.round(v * 10000.0) / 10000.0;
	}

	// ─────────────────────────────────────────────────────────────────────────
	// GET
	// /api/{market}/ema/calculation/{marketPrice}/{ema9}/{ema21}/{ema50}/{ema200}
	//
	// EMA Position & Risk Calculator — uses the full EMA stack (9/21/50/200).
	//
	// Why EMA over MA:
	// EMA gives more weight to recent prices — reacts faster to price changes.
	// EMA9 = very short-term momentum (scalp / day trade trigger)
	// EMA21 = short-term trend (swing trade baseline)
	// EMA50 = medium-term trend (institutional reference)
	// EMA200= long-term trend (bull/bear market dividing line)
	//
	// Validation — ALL 4 must pass for PROCEED:
	// 1. price > EMA9 > EMA21 > EMA50 > EMA200 (perfect bullish stack)
	// Partial stack (price > EMA9 > EMA21 only) → PROCEED with lower grade
	// 2. If price < EMA200 → hard SKIP regardless of other EMAs
	//
	// Stack grades:
	// FULL : price > EMA9 > EMA21 > EMA50 > EMA200 → strongest signal
	// PARTIAL_STRONG : price > EMA9 > EMA21 > EMA50 → good, EMA200 below
	// PARTIAL_WEAK : price > EMA9 > EMA21 only → short-term only
	// NONE : any EMA above price → SKIP
	//
	// Risk level — based on % above EMA21 (swing trade baseline):
	// < 3% → LOW RISK (tight to EMA21 — ideal entry)
	// 3-7% → MEDIUM RISK (extended, consider waiting for pullback)
	// > 7% → HIGH RISK (overextended, likely to revert to EMA21)
	//
	// Entry strategy:
	// Ideal = pullback to EMA9 (momentum traders buy here)
	// Normal = pullback to EMA21 (swing traders buy here)
	// Max = current price (never chase above current close)
	//
	// Stop loss = just below EMA21 (0.1% buffer)
	// If EMA21 breaks → short-term trend invalidated
	//
	// Tick-adjusted targets use EMA21 as the anchor:
	// TP1 = entry + 1.0R (sell 40%)
	// TP2 = entry + 2.0R (sell 40%) — move SL to breakeven
	// TP3 = entry + 3.5R (sell 20%) — trail or hold
	//
	// Examples:
	// GET /api/my/ema/calculation/1.42/1.39/1.35/1.28/1.10 → FULL stack, Low Risk
	// GET /api/my/ema/calculation/1.42/1.39/1.35/1.45/1.10 → SKIP (EMA50 above
	// price)
	// GET /api/my/ema/calculation/0.52/0.53/0.51/0.48/0.45 → SKIP (price below
	// EMA9)
	// ─────────────────────────────────────────────────────────────────────────
	@GetMapping("ema/calculation/{marketPrice}/{ema9}/{ema21}/{ema50}/{ema200}")
	public ResponseEntity<Map<String, Object>> emaCalculation(@PathVariable double marketPrice,
			@PathVariable double ema9, @PathVariable double ema21, @PathVariable double ema50,
			@PathVariable double ema200, @RequestParam(defaultValue = "DAILY") String timeframe) {
		TimeframeConfig tf = TimeframeConfig.of(timeframe);
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("input", Map.of("marketPrice", marketPrice, "ema9", ema9, "ema21", ema21, "ema50", ema50, "ema200",
				ema200, "timeframe", tf.label(), "holdDuration", tf.holdDuration()));
		// ── Individual checks ─────────────────────────────────────────────────
		boolean aboveEma9 = marketPrice > ema9;
		boolean aboveEma21 = marketPrice > ema21;
		boolean aboveEma50 = marketPrice > ema50;
		boolean aboveEma200 = marketPrice > ema200;
		boolean ema9AboveEma21 = ema9 > ema21;
		boolean ema21AboveEma50 = ema21 > ema50;
		boolean ema50AboveEma200 = ema50 > ema200;
		// Build readable checks block
		Map<String, Object> checks = new LinkedHashMap<>();
		checks.put("priceAboveEMA9", aboveEma9 ? "✅ PASS" : "❌ FAIL");
		checks.put("priceAboveEMA21", aboveEma21 ? "✅ PASS" : "❌ FAIL");
		checks.put("priceAboveEMA50", aboveEma50 ? "✅ PASS" : "❌ FAIL");
		checks.put("priceAboveEMA200", aboveEma200 ? "✅ PASS" : "❌ FAIL");
		checks.put("ema9AboveEMA21", ema9AboveEma21 ? "✅ PASS" : "❌ FAIL");
		checks.put("ema21AboveEMA50", ema21AboveEma50 ? "✅ PASS" : "❌ FAIL");
		checks.put("ema50AboveEMA200", ema50AboveEma200 ? "✅ PASS" : "❌ FAIL");
		resp.put("checks", checks);
		// ── Hard block: price below EMA200 = bear market territory ───────────
		if (!aboveEma200) {
			resp.put("decision", "SKIP");
			resp.put("emoji", "🚫");
			resp.put("stackGrade", "NONE");
			resp.put("reason",
					String.format(
							"Price (%.4f) is BELOW EMA200 (%.4f) — stock is in long-term downtrend (bear territory). "
									+ "EMA200 is the institutional bull/bear dividing line. No long trades below it.",
							marketPrice, ema200));
			resp.put("advice",
					"Hard skip. Wait for price to reclaim EMA200 and hold above it for at least 3 trading days before considering any long position.");
			return ResponseEntity.ok(resp);
		}
		// ── Hard block: price below EMA21 = short-term trend broken ──────────
		if (!aboveEma21) {
			resp.put("decision", "SKIP");
			resp.put("emoji", "🚫");
			resp.put("stackGrade", "NONE");
			resp.put("reason",
					String.format(
							"Price (%.4f) is below EMA21 (%.4f) — short-term trend is bearish. "
									+ "Even though EMA200 is below (%s), the near-term momentum is down.",
							marketPrice, ema21, aboveEma200 ? "bullish long-term" : "also bearish"));
			resp.put("advice",
					"Skip. Wait for price to close above EMA21 with a bullish candle. EMA21 reclaim = potential re-entry.");
			return ResponseEntity.ok(resp);
		}
		// ── Hard block: EMA9 above price = very short-term trend broken ──────
		if (!aboveEma9) {
			resp.put("decision", "SKIP");
			resp.put("emoji", "🚫");
			resp.put("stackGrade", "NONE");
			resp.put("reason",
					String.format(
							"Price (%.4f) is below EMA9 (%.4f) — short-term momentum has stalled. "
									+ "Price above EMA21 but lagging EMA9 suggests recent pullback.",
							marketPrice, ema9));
			resp.put("advice",
					String.format("Borderline setup. Price is above EMA21 (%.4f) which is the support. "
							+ "If you want to enter, use EMA21 as entry (%.4f) and wait for price to reclaim EMA9. "
							+ "Do not enter until price closes above EMA9 (%.4f).", ema21, ema21, ema9));
			return ResponseEntity.ok(resp);
		}
		// ── Determine stack grade ─────────────────────────────────────────────
		boolean fullStack = aboveEma9 && ema9AboveEma21 && ema21AboveEma50 && ema50AboveEma200;
		boolean partialStrongStack = aboveEma9 && ema9AboveEma21 && ema21AboveEma50 && !ema50AboveEma200;
		boolean partialWeakStack = aboveEma9 && ema9AboveEma21 && !ema21AboveEma50;
		String stackGrade;
		String stackEmoji;
		String stackNote;
		if (fullStack) {
			stackGrade = "FULL STACK";
			stackEmoji = "🏆";
			stackNote = "Price > EMA9 > EMA21 > EMA50 > EMA200 — perfect bullish alignment. "
					+ "All timeframes in agreement. Strongest possible EMA signal.";
		} else if (partialStrongStack) {
			stackGrade = "PARTIAL — STRONG";
			stackEmoji = "✅";
			stackNote = "Price > EMA9 > EMA21 > EMA50, but EMA50 not yet above EMA200. "
					+ "Short and medium term bullish. Long-term trend still transitioning. "
					+ "Good trade, not the best. Reduce position by 20%.";
		} else if (partialWeakStack) {
			stackGrade = "PARTIAL — WEAK";
			stackEmoji = "⚠️";
			stackNote = "Price > EMA9 > EMA21 only. EMA50 and EMA200 above price. "
					+ "Short-term bounce only — may be fighting the larger trend. "
					+ "Scalp plan only. Do not swing trade. Tight SL required.";
		} else {
			stackGrade = "MIXED";
			stackEmoji = "⚠️";
			stackNote = "EMAs not in clean order. Choppy/sideways market. No clear trend edge.";
		}
		// ── Risk level based on % above EMA21 ────────────────────────────────
		double pctAboveEma21 = (marketPrice - ema21) / ema21 * 100;
		double pctAboveEma9 = (marketPrice - ema9) / ema9 * 100;
		double pctAboveEma50 = (marketPrice - ema50) / ema50 * 100;
		double pctAboveEma200 = (marketPrice - ema200) / ema200 * 100;
		String riskLevel;
		String riskEmoji;
		String riskAdvice;
		if (pctAboveEma21 < tf.lowThreshold()) {
			riskLevel = "LOW RISK";
			riskEmoji = "🟢";
			riskAdvice = String.format(
					"[%s] Price is %.2f%% above EMA21 — within low-risk zone (< %.0f%%). "
							+ "Hugging EMA21 — ideal entry zone for %s. Full position size for %s grade.",
					tf.label(), pctAboveEma21, tf.lowThreshold(), tf.holdDuration(), stackGrade);
		} else if (pctAboveEma21 <= tf.highThreshold()) {
			riskLevel = "MEDIUM RISK";
			riskEmoji = "🟡";
			riskAdvice = String.format(
					"[%s] Price is %.2f%% above EMA21 — medium-risk zone (%.0f%%–%.0f%%). "
							+ "Consider waiting for pullback to EMA9 before entering. Reduce position 30%%. Hold: %s.",
					tf.label(), pctAboveEma21, tf.lowThreshold(), tf.highThreshold(), tf.holdDuration());
		} else {
			riskLevel = "HIGH RISK";
			riskEmoji = "🔴";
			riskAdvice = String.format(
					"[%s] Price is %.2f%% above EMA21 — overextended (> %.0f%%). "
							+ "High probability of pullback to EMA9 or EMA21. Wait for mean reversion. "
							+ "If entering now: scalp only with very tight SL. Hold: %s.",
					tf.label(), pctAboveEma21, tf.highThreshold(), tf.holdDuration());
		}
		// ── Trade plan ────────────────────────────────────────────────────────
		// Entry ideal = pullback to EMA9 (momentum continuation entry)
		// Entry swing = pullback to EMA21 (conservative swing entry)
		// Stop loss = just below EMA21 (support invalidation level)
		double entryMomentum = round4(ema9);
		double entrySwing = round4(ema21);
		double entryMax = round4(marketPrice);
		double stopLoss = round4(ema21 * (1.0 - tf.slBuffer()));
		double riskMomentum = entryMomentum - stopLoss;
		double riskSwing = entrySwing - stopLoss;
		// Use momentum entry for TP calculations (tighter, higher R:R)
		double rBase = riskMomentum > 0 ? riskMomentum : riskSwing;
		double tp1 = round4(entryMomentum + rBase * 1.0);
		double tp2 = round4(entryMomentum + rBase * 2.0);
		double tp3 = round4(entryMomentum + rBase * 3.5);
		// If price already near EMA9 (within 0.5%), enter at current price
		if (pctAboveEma9 <= 0.5) {
			tp1 = round4(marketPrice + rBase * 1.0);
			tp2 = round4(marketPrice + rBase * 2.0);
			tp3 = round4(marketPrice + rBase * 3.5);
		}
		// ── EMA distance summary ──────────────────────────────────────────────
		Map<String, Object> emaDistances = new LinkedHashMap<>();
		emaDistances.put("aboveEMA9", String.format("%.2f%%", pctAboveEma9));
		emaDistances.put("aboveEMA21", String.format("%.2f%%", pctAboveEma21));
		emaDistances.put("aboveEMA50", String.format("%.2f%%", pctAboveEma50));
		emaDistances.put("aboveEMA200", String.format("%.2f%%", pctAboveEma200));
		resp.put("decision", "PROCEED");
		resp.put("stackGrade", stackGrade);
		resp.put("stackEmoji", stackEmoji);
		resp.put("stackNote", stackNote);
		resp.put("riskLevel", riskLevel);
		resp.put("riskEmoji", riskEmoji);
		resp.put("advice", riskAdvice);
		resp.put("calculation",
				Map.of("formula", "(marketPrice - EMA21) / EMA21 × 100", "pctAboveEMA21",
						String.format("%.2f%%", pctAboveEma21), "riskThresholds",
						String.format("Low < %.0f%% | Medium %.0f%%–%.0f%% | High > %.0f%%  [%s]", tf.lowThreshold(),
								tf.lowThreshold(), tf.highThreshold(), tf.highThreshold(), tf.label()),
						"emaDistances", emaDistances));
		Map<String, Object> tradePlan = new LinkedHashMap<>();
		tradePlan.put("entryMomentum", entryMomentum);
		tradePlan.put("entryMomentumNote",
				String.format("Queue at EMA9 (%.4f) — momentum pullback entry. Best R:R.", entryMomentum));
		tradePlan.put("entrySwing", entrySwing);
		tradePlan.put("entrySwingNote",
				String.format("Queue at EMA21 (%.4f) — conservative swing entry if deeper pullback.", entrySwing));
		tradePlan.put("entryMax", entryMax);
		tradePlan.put("entryMaxNote", String
				.format("Do NOT buy above current price (%.4f). If opens above this = wait for pullback.", entryMax));
		tradePlan.put("stopLoss", stopLoss);
		tradePlan.put("stopLossNote",
				String.format("%.4f%% buffer below EMA21 (%.4f). EMA21 break = trend invalidated, exit immediately.",
						tf.slBuffer() * 100, ema21));
		tradePlan.put("riskPerUnit", round4(rBase));
		tradePlan.put("tp1", tp1);
		tradePlan.put("tp1Note", String.format("Sell 40%% at %.4f  (R:R 1:1.0) — then move SL to breakeven", tp1));
		tradePlan.put("tp2", tp2);
		tradePlan.put("tp2Note", String.format("Sell 40%% at %.4f  (R:R 1:2.0)", tp2));
		tradePlan.put("tp3", tp3);
		tradePlan.put("tp3Note", String.format("Sell 20%% at %.4f  (R:R 1:3.5) — trail stop or hold", tp3));
		resp.put("tradePlan", tradePlan);
		return ResponseEntity.ok(resp);
	}
}