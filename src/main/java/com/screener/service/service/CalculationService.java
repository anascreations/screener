package com.screener.service.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.screener.service.dto.Level2Request;
import com.screener.service.dto.MaCalculationRequest;
import com.screener.service.dto.TrendDistanceRequest;
import com.screener.service.model.Level2Entry;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Service
public class CalculationService {
	public Map<String, Object> calculate(Level2Request req) {
		List<Level2Entry> bids = req.bids();
		List<Level2Entry> asks = req.asks();
		Map<String, Object> resp = new LinkedHashMap<>();
		double bestBid = bids.get(0).price();
		double bestAsk = asks.get(0).price();
		double spread = round4(bestAsk - bestBid);
		double spreadPct = round4((spread / bestAsk) * 100);
		resp.put("spread", Map.of("bestBid", bestBid, "bestAsk", bestAsk, "spread", spread, "spreadPct",
				spreadPct + "%", "tight", spreadPct < 0.5));
		double totalBidVol = bids.stream().mapToDouble(Level2Entry::volume).sum();
		double totalAskVol = asks.stream().mapToDouble(Level2Entry::volume).sum();
		double obi = totalBidVol / (totalBidVol + totalAskVol);
		String obiEmoji = obi >= 0.60 ? "🟢" : obi >= 0.50 ? "🟡" : obi >= 0.40 ? "🟠" : "🔴";
		String obiSignal = obi >= 0.60 ? "STRONG BUY PRESSURE"
				: obi >= 0.50 ? "MILD BUY PRESSURE" : obi >= 0.40 ? "MILD SELL PRESSURE" : "STRONG SELL PRESSURE";
		resp.put("orderBookImbalance",
				Map.of("totalBidVolume", round0(totalBidVol), "totalAskVolume", round0(totalAskVol), "obiRatio",
						round4(obi), "obiPct", round2(obi * 100) + "%", "signal", obiEmoji + " " + obiSignal));
		double bidW = bids.get(0).volume();
		double askW = asks.get(0).volume();
		double weightedMid = round4((bestBid * askW + bestAsk * bidW) / (bidW + askW));
		double simpleMid = round4((bestBid + bestAsk) / 2.0);
		resp.put("midPrice", Map.of("simple", simpleMid, "weighted", weightedMid, "note",
				"Weighted mid skews toward the thinner side"));
		double avgBidVol = totalBidVol / bids.size();
		double avgAskVol = totalAskVol / asks.size();
		List<Map<String, Object>> bidWalls = bids.stream().filter(b -> b.volume() >= avgBidVol * 2.0).map(b -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("price", b.price());
			m.put("volume", round0(b.volume()));
			m.put("orders", b.orders());
			m.put("strength", round2(b.volume() / avgBidVol) + "× avg");
			m.put("role", "🛡️ BID WALL — support");
			return m;
		}).collect(Collectors.toList());
		List<Map<String, Object>> askWalls = asks.stream().filter(a -> a.volume() >= avgAskVol * 2.0).map(a -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("price", a.price());
			m.put("volume", round0(a.volume()));
			m.put("orders", a.orders());
			m.put("strength", round2(a.volume() / avgAskVol) + "× avg");
			m.put("role", "🧱 ASK WALL — resistance");
			return m;
		}).collect(Collectors.toList());
		resp.put("walls", Map.of("bidWalls", bidWalls, "askWalls", askWalls));
		double top3Bid = bids.stream().limit(3).mapToDouble(Level2Entry::volume).sum();
		double top3Ask = asks.stream().limit(3).mapToDouble(Level2Entry::volume).sum();
		double absRatio = round4(top3Bid / top3Ask);
		resp.put("depthAbsorption", Map.of("top3BidVolume", round0(top3Bid), "top3AskVolume", round0(top3Ask),
				"absorptionRatio", absRatio, "signal", absRatio >= 1.2 ? "✅ Buyers absorbing more — bullish near-term"
						: absRatio <= 0.8 ? "❌ Sellers absorbing more — bearish near-term" : "⚖️ Balanced absorption"));
		double uiDivergence = round2(Math.abs(req.bidPressurePct() - (obi * 100)));
		resp.put("pressureDivergence",
				Map.of("uiBidPressure", req.bidPressurePct() + "%", "uiAskPressure", req.askPressurePct() + "%",
						"volumeOBIPct", round2(obi * 100) + "%", "divergence", uiDivergence + "%", "note",
						uiDivergence > 10
								? "⚠️ UI pressure diverges from OBI by " + uiDivergence + "% — hidden orders possible"
								: "✅ UI pressure aligns with volume OBI"));
		int score = 0;
		List<String> reasons = new ArrayList<>();
		if (obi >= 0.55) {
			score += 2;
			reasons.add("OBI bullish: " + round2(obi * 100) + "%");
		} else if (obi < 0.45) {
			score -= 2;
			reasons.add("OBI bearish: " + round2(obi * 100) + "%");
		}
		if (absRatio >= 1.2) {
			score += 2;
			reasons.add("Absorption: buyers winning");
		} else if (absRatio <= 0.8) {
			score -= 2;
			reasons.add("Absorption: sellers winning");
		}
		if (!bidWalls.isEmpty()) {
			score += 1;
			reasons.add("Bid wall = strong support below");
		}
		if (!askWalls.isEmpty()) {
			score -= 1;
			reasons.add("Ask wall = resistance above");
		}
		if (spreadPct < 0.3) {
			score += 1;
			reasons.add("Tight spread: liquid market");
		} else if (spreadPct > 1.0) {
			score -= 1;
			reasons.add("Wide spread: slippage risk");
		}
		if (req.bidPressurePct() >= 50) {
			score += 1;
			reasons.add("UI bar: bid dominant");
		} else {
			score -= 1;
			reasons.add("UI bar: ask dominant");
		}
		double nearestBidWall = bidWalls.isEmpty() ? bids.get(bids.size() - 1).price()
				: ((Number) bidWalls.get(0).get("price")).doubleValue();
		String action, actionEmoji, strategy;
		double entryPrice;
		if (score >= 3) {
			action = "BUY — AGGRESSIVE";
			actionEmoji = "🟢";
			entryPrice = bestAsk;
			strategy = "Lift the ask. Strong confluence across all factors.";
		} else if (score >= 1) {
			action = "BUY — PASSIVE LIMIT";
			actionEmoji = "🟡";
			entryPrice = weightedMid;
			strategy = "Queue at weighted mid — let price come to you.";
		} else if (score <= -3) {
			action = "SKIP — SELL DOMINANT";
			actionEmoji = "🔴";
			entryPrice = 0;
			strategy = "Do not enter. Wait for OBI shift above 50%.";
		} else {
			action = "WAIT — NEUTRAL";
			actionEmoji = "⚪";
			entryPrice = bestBid;
			strategy = "Queue at best bid only. No clear edge — reduce size.";
		}
		double stopLoss2 = round4(nearestBidWall * 0.995);
		double riskR = entryPrice > 0 ? round4(entryPrice - stopLoss2) : 0;
		Map<String, Object> rec = new LinkedHashMap<>();
		rec.put("action", actionEmoji + " " + action);
		rec.put("score", score + " / 7  (≥3 Buy · ≤-3 Skip · else Wait)");
		rec.put("strategy", strategy);
		rec.put("entryPrice", entryPrice > 0 ? entryPrice : "N/A");
		rec.put("stopLoss", stopLoss2 > 0 ? stopLoss2 : "N/A");
		rec.put("stopNote", "0.5% below nearest bid wall at " + nearestBidWall);
		rec.put("riskPerUnit", riskR > 0 ? riskR : "N/A");
		rec.put("target1", entryPrice > 0 ? round4(entryPrice + riskR * 1.5) : "N/A");
		rec.put("target1Note", "R:R 1:1.5 — take 50% off here");
		rec.put("target2", entryPrice > 0 ? round4(entryPrice + riskR * 3.0) : "N/A");
		rec.put("target2Note", "R:R 1:3.0 — trail remainder");
		rec.put("scoringBreakdown", reasons);
		resp.put("recommendation", rec);
		return resp;
	}

	// =========================================================================
	// MA CALCULATION — 5-filter combined strategy
	//
	// STEP 1: MA5 > MA20 > MA50 (hard gate)
	// STEP 2: Formula1 — 0% < ((Price-MA20)/MA20×100) < 5%
	// STEP 3: Formula2 — ((MA20-MA50)/MA50×100) → grade S++/A/B/C
	// STEP 4: RSI14 — 50–65 ideal, >80 skip, <40 skip
	// STEP 5: VolumeRatio — >1.2× pass, <0.8× skip
	// =========================================================================
	public Map<String, Object> maCalculate(MaCalculationRequest req) {
		double price = req.getMarketPrice();
		double ma5 = req.getMa5();
		double ma20 = req.getMa20();
		double ma50 = req.getMa50();
		double ma200 = req.getMa200();
		TimeframeConfig tf = TimeframeConfig.of(req.getTimeframe());
		List<String> warnings = new ArrayList<>();
		List<String> confluences = new ArrayList<>();
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("input", buildInput(req, tf));
		// ── STEP 1 GATE A: Price must be above MA20 ───────────────────────────
		if (price < ma20) {
			return buildSkip(resp,
					String.format("STEP 1 FAIL — Price (%.4f) below MA20 (%.4f). Support broken.", price, ma20),
					Map.of("priceVsMA20", String.format("%.4f below MA20 (%.4f)", ma20 - price, ma20), "note",
							"Price below MA20 = downtrend. Wait for reclaim."));
		}
		// ── STEP 1 GATE B: MA5 must be above MA20 (golden cross) ─────────────
		if (ma5 < ma20) {
			return buildSkip(resp,
					String.format("STEP 1 FAIL — MA5 (%.4f) below MA20 (%.4f). No golden cross yet.", ma5, ma20),
					Map.of("ma5AboveMA20", false, "gap",
							String.format("MA5 is %.4f (%.2f%%) below MA20", ma20 - ma5, (ma20 - ma5) / ma20 * 100),
							"note", "Wait for MA5 ≥ MA20 golden cross."));
		}
		// ── STEP 1 GATE C: MA20 must be above MA50 ───────────────────────────
		if (ma20 < ma50) {
			return buildSkip(resp,
					String.format("STEP 1 FAIL — MA20 (%.4f) below MA50 (%.4f). Medium trend bearish.", ma20, ma50),
					Map.of("ma20AboveMA50", false, "gap",
							String.format("MA20 is %.4f (%.2f%%) below MA50", ma50 - ma20, (ma50 - ma20) / ma50 * 100),
							"note", "MA5 > MA20 > MA50 stack must be fully aligned."));
		}
		confluences.add("✅ STEP 1 PASS — MA5 > MA20 > MA50 fully aligned");
		// ── STEP 2: Price vs MA20 ─────────────────────────────────────────────
		double pctAboveMA20 = (price - ma20) / ma20 * 100;
		double pctAboveMA5 = (price - ma5) / ma5 * 100;
		double ma5AboveMA20 = (ma5 - ma20) / ma20 * 100;
		double pctAboveMA50 = (price - ma50) / ma50 * 100;
		PriceZone priceZone = resolvePriceZone(pctAboveMA20);
		resolveStep2(priceZone, pctAboveMA20, ma20, confluences, warnings);
		// ── STEP 3: MA20 vs MA50 trend maturity ──────────────────────────────
		double ma20VsMa50Pct = (ma20 - ma50) / ma50 * 100;
		TrendGrade trendGrade = resolveTrendGrade(ma20VsMa50Pct);
		resolveStep3(trendGrade, ma20VsMa50Pct, confluences, warnings);
		// ── STEP 4: RSI14 ─────────────────────────────────────────────────────
		RsiZone rsiZone = RsiZone.UNKNOWN;
		if (req.getRsi14() != null) {
			rsiZone = resolveRsiZone(req.getRsi14(), confluences, warnings);
		} else {
			warnings.add("⚠️ STEP 4 — RSI14 not provided, momentum unconfirmed");
		}
		// ── STEP 5: Volume Ratio ──────────────────────────────────────────────
		VolumeZone volumeZone = VolumeZone.UNKNOWN;
		if (req.getVolumeRatio() != null) {
			volumeZone = resolveVolumeZone(req.getVolumeRatio(), confluences, warnings);
		} else {
			warnings.add("⚠️ STEP 5 — Volume ratio not provided, conviction unconfirmed");
		}
		// ── Market Regime (MA200) ─────────────────────────────────────────────
		MarketRegime regime = resolveRegime(price, ma200, warnings, confluences);
		// ── Combined signal score ─────────────────────────────────────────────
		SignalScore signalScore = resolveSignalScore(priceZone, trendGrade, rsiZone, volumeZone, regime);
		RiskLevel riskLevel = resolveRiskLevel(priceZone, trendGrade, rsiZone, volumeZone, regime);
		resp.put("decision", riskLevel.decision);
		resp.put("emoji", riskLevel.emoji);
		resp.put("risk", riskLevel.label);
		resp.put("signalQuality", signalScore.grade());
		resp.put("signalScore", signalScore.score() + " / " + signalScore.maxScore());
		resp.put("marketRegime", regime.name());
		resp.put("trendGrade", trendGrade.grade);
		resp.put("rsiZone", rsiZone.label);
		resp.put("volumeZone", volumeZone.label);
		resp.put("advice", buildAdvice(riskLevel, priceZone, trendGrade, rsiZone, volumeZone, tf, pctAboveMA20, ma5,
				ma20, ma50, ma200));
		resp.put("warnings", warnings);
		resp.put("confluences", confluences);
		resp.put("filterSteps",
				buildFilterSteps(priceZone, trendGrade, rsiZone, volumeZone, regime, req, pctAboveMA20, ma20VsMa50Pct));
		resp.put("calculation",
				buildCalculation(price, ma20, ma50, ma200, pctAboveMA20, pctAboveMA5, ma5AboveMA20, pctAboveMA50));
		resp.put("maAlignment", buildMaAlignment(price, ma5, ma20, ma50, ma200));
		if ("SKIP".equals(riskLevel.decision)) {
			return resp;
		}
		// ── Entry / SL / TP ───────────────────────────────────────────────────
		double entryIdeal = pctAboveMA5 <= 0.5 ? round4(price) : round4(ma5);
		double entryMax = round4(price);
		double stopLoss = resolveStopLoss(price, ma20, ma50, tf, req.getAtr14(), warnings);
		double risk_R = round4(entryIdeal - stopLoss);
		double tp1 = round4(entryIdeal + risk_R * 1.0);
		double tp2 = round4(entryIdeal + risk_R * 2.0);
		double tp3 = round4(entryIdeal + risk_R * 3.5);
		resp.put("positionSize", resolvePositionSize(priceZone, trendGrade, rsiZone, volumeZone));
		resp.put("tradePlan", buildTradePlan(price, ma20, ma50, tf, entryIdeal, entryMax, stopLoss, risk_R, tp1, tp2,
				tp3, req.getAtr14()));
		return resp;
	}

	// =========================================================================
	// TREND DISTANCE
	// =========================================================================
	public Map<String, Object> trendDistance(TrendDistanceRequest req) {
		double price = req.getMarketPrice();
		double ma20 = req.getMa20();
		double ma50 = req.getMa50();
		TimeframeConfig tf = TimeframeConfig.of(req.getTimeframe());
		List<String> warnings = new ArrayList<>();
		List<String> confluences = new ArrayList<>();
		double priceVsMa20Pct = (price - ma20) / ma20 * 100;
		double ma20VsMa50Pct = (ma20 - ma50) / ma50 * 100;
		PriceZone priceZone = resolvePriceZone(priceVsMa20Pct);
		TrendGrade trendGrade = resolveTrendGrade(ma20VsMa50Pct);
		RsiZone rsiZone = RsiZone.UNKNOWN;
		VolumeZone volumeZone = VolumeZone.UNKNOWN;
		if (req.getRsi14() != null)
			rsiZone = resolveRsiZone(req.getRsi14(), confluences, warnings);
		else
			warnings.add("⚠️ RSI14 not provided — momentum unconfirmed");
		if (req.getVolumeRatio() != null)
			volumeZone = resolveVolumeZone(req.getVolumeRatio(), confluences, warnings);
		else
			warnings.add("⚠️ Volume ratio not provided — conviction unconfirmed");
		EntryDecision decision = resolveDecision(priceZone, trendGrade, rsiZone, volumeZone);
		SignalScore signalScore = resolveSignalScore(priceZone, trendGrade, rsiZone, volumeZone, MarketRegime.BULL);
		Map<String, Object> resp = new LinkedHashMap<>();
		if (req.getTicker() != null)
			resp.put("ticker", req.getTicker().toUpperCase());
		resp.put("timeframe", tf.label());
		resp.put("decision", decision.label);
		resp.put("emoji", decision.emoji);
		resp.put("grade", trendGrade.grade);
		resp.put("signalScore", signalScore.score() + " / " + signalScore.maxScore());
		resp.put("signalQuality", signalScore.grade());
		resp.put("summary", buildSummary(priceVsMa20Pct, ma20VsMa50Pct, priceZone, trendGrade, tf));
		resp.put("formula1", buildFormula1(price, ma20, priceVsMa20Pct, priceZone));
		resp.put("formula2", buildFormula2(ma20, ma50, ma20VsMa50Pct, trendGrade));
		resp.put("entryGuidance",
				buildEntryGuidance(decision, trendGrade, priceZone, rsiZone, volumeZone, ma20, ma50, tf));
		resp.put("warnings", warnings);
		resp.put("confluences", confluences);
		return resp;
	}

	// =========================================================================
	// STEP RESOLVERS
	// =========================================================================
	private void resolveStep2(PriceZone zone, double pct, double ma20, List<String> confluences,
			List<String> warnings) {
		switch (zone) {
		case IDEAL -> confluences
				.add(String.format("✅ STEP 2 PASS — Price %.2f%% above MA20 (%.4f) — ideal zone 0–2%%", pct, ma20));
		case ACCEPTABLE ->
			confluences.add(String.format("🟢 STEP 2 PASS — Price %.2f%% above MA20 — acceptable 2–5%%", pct));
		case STRETCHED -> warnings
				.add(String.format("🟡 STEP 2 WARN — Price %.2f%% above MA20 — stretched 5–10%%, reduce size", pct));
		case OVEREXTENDED -> warnings
				.add(String.format("🔴 STEP 2 FAIL — Price %.2f%% above MA20 — overextended >10%%, do not chase", pct));
		default -> warnings.add("🔴 STEP 2 FAIL — Price below MA20");
		}
	}

	private void resolveStep3(TrendGrade grade, double pct, List<String> confluences, List<String> warnings) {
		switch (grade) {
		case S_PLUS_PLUS -> confluences.add(
				String.format("🚀 STEP 3 PASS — MA20 vs MA50: %.2f%% — S++ fresh trend, highest probability", pct));
		case A -> confluences.add(String.format("✅ STEP 3 PASS — MA20 vs MA50: %.2f%% — Grade A healthy trend", pct));
		case B ->
			warnings.add(String.format("⚠️ STEP 3 WARN — MA20 vs MA50: %.2f%% — Grade B maturing, reduce size", pct));
		case C ->
			warnings.add(String.format("🟡 STEP 3 WARN — MA20 vs MA50: %.2f%% — Grade C late stage, tight SL", pct));
		case FLAT -> warnings
				.add(String.format("➡️ STEP 3 WARN — MA20 vs MA50: %.2f%% — just crossed, wait for separation", pct));
		case EXHAUSTED ->
			warnings.add(String.format("🛑 STEP 3 FAIL — MA20 vs MA50: %.2f%% — exhausted >10%%, skip", pct));
		default -> warnings.add("🔻 STEP 3 FAIL — MA20 below MA50, downtrend");
		}
	}

	private RsiZone resolveRsiZone(double rsi, List<String> confluences, List<String> warnings) {
		if (rsi > 80) {
			warnings.add(String.format("🔴 STEP 4 FAIL — RSI14 %.1f extremely overbought (>80), skip", rsi));
			return RsiZone.EXTREME_OB;
		}
		if (rsi > 70) {
			warnings.add(String.format("🟡 STEP 4 WARN — RSI14 %.1f overbought (70–80), reduce size 50%%", rsi));
			return RsiZone.OVERBOUGHT;
		}
		if (rsi >= 50 && rsi <= 65) {
			confluences.add(String.format("✅ STEP 4 PASS — RSI14 %.1f ideal momentum zone (50–65)", rsi));
			return RsiZone.IDEAL;
		}
		if (rsi > 65) {
			confluences.add(String.format("🟢 STEP 4 PASS — RSI14 %.1f strong momentum (65–70), still valid", rsi));
			return RsiZone.STRONG;
		}
		if (rsi >= 40) {
			warnings.add(String.format("🟡 STEP 4 WARN — RSI14 %.1f recovering (40–50), wait for cross above 50", rsi));
			return RsiZone.RECOVERING;
		}
		warnings.add(String.format("🔴 STEP 4 FAIL — RSI14 %.1f weak momentum (<40), MA alignment may be false", rsi));
		return RsiZone.WEAK;
	}

	private VolumeZone resolveVolumeZone(double ratio, List<String> confluences, List<String> warnings) {
		if (ratio >= 2.0) {
			confluences.add(String.format("🚀 STEP 5 PASS — Volume ratio %.2f× surge — institutional interest", ratio));
			return VolumeZone.SURGE;
		}
		if (ratio >= 1.5) {
			confluences.add(String.format("✅ STEP 5 PASS — Volume ratio %.2f× strong accumulation", ratio));
			return VolumeZone.STRONG;
		}
		if (ratio >= 1.2) {
			confluences.add(String.format("🟢 STEP 5 PASS — Volume ratio %.2f× above average, buyers present", ratio));
			return VolumeZone.ABOVE_AVG;
		}
		if (ratio >= 0.8) {
			warnings.add(String.format("⚪ STEP 5 NEUTRAL — Volume ratio %.2f× normal, no strong conviction", ratio));
			return VolumeZone.NORMAL;
		}
		warnings.add(String.format("🔴 STEP 5 FAIL — Volume ratio %.2f× weak (<0.8×), no buyers, avoid", ratio));
		return VolumeZone.WEAK;
	}

	private SignalScore resolveSignalScore(PriceZone priceZone, TrendGrade trendGrade, RsiZone rsiZone,
			VolumeZone volumeZone, MarketRegime regime) {
		int score = 1; // STEP 1 MA alignment always passes here
		score += switch (priceZone) {
		case IDEAL -> 2;
		case ACCEPTABLE -> 1;
		case STRETCHED -> 0;
		default -> -1;
		};
		score += switch (trendGrade) {
		case S_PLUS_PLUS -> 2;
		case A -> 1;
		case B -> 0;
		case C -> -1;
		default -> -2;
		};
		score += switch (rsiZone) {
		case IDEAL -> 2;
		case STRONG -> 1;
		case RECOVERING -> 0;
		case OVERBOUGHT -> -1;
		case EXTREME_OB, WEAK -> -2;
		case UNKNOWN -> 0;
		};
		score += switch (volumeZone) {
		case SURGE, STRONG -> 2;
		case ABOVE_AVG -> 1;
		case NORMAL -> 0;
		case WEAK -> -1;
		case UNKNOWN -> 0;
		};
		if (regime == MarketRegime.BULL)
			score += 1;
		if (regime == MarketRegime.BEAR)
			score -= 2;
		int clamped = Math.max(0, score);
		String grade = clamped >= 9 ? "S++" : clamped >= 7 ? "A" : clamped >= 5 ? "B" : clamped >= 3 ? "C" : "INVALID";
		return new SignalScore(clamped, 10, grade);
	}

	private RiskLevel resolveRiskLevel(PriceZone priceZone, TrendGrade trendGrade, RsiZone rsiZone,
			VolumeZone volumeZone, MarketRegime regime) {
		if (regime == MarketRegime.BEAR)
			return RiskLevel.HIGH_SKIP;
		if (trendGrade == TrendGrade.EXHAUSTED || trendGrade == TrendGrade.BEARISH)
			return RiskLevel.HIGH_SKIP;
		if (priceZone == PriceZone.OVEREXTENDED)
			return RiskLevel.HIGH_SKIP;
		if (rsiZone == RsiZone.EXTREME_OB || rsiZone == RsiZone.WEAK)
			return RiskLevel.HIGH_SKIP;
		if (volumeZone == VolumeZone.WEAK)
			return RiskLevel.HIGH_SKIP;
		if (priceZone == PriceZone.STRETCHED || trendGrade == TrendGrade.C || trendGrade == TrendGrade.FLAT
				|| rsiZone == RsiZone.OVERBOUGHT || rsiZone == RsiZone.RECOVERING || volumeZone == VolumeZone.NORMAL)
			return RiskLevel.MEDIUM_PROCEED;
		return RiskLevel.LOW_PROCEED;
	}

	private EntryDecision resolveDecision(PriceZone zone, TrendGrade grade, RsiZone rsiZone, VolumeZone volumeZone) {
		if (grade == TrendGrade.BEARISH)
			return EntryDecision.SKIP_BEARISH;
		if (grade == TrendGrade.EXHAUSTED)
			return EntryDecision.SKIP_EXHAUSTED;
		if (zone == PriceZone.BELOW_MA20)
			return EntryDecision.SKIP_BELOW_MA20;
		if (rsiZone == RsiZone.EXTREME_OB)
			return EntryDecision.SKIP_RSI_OB;
		if (rsiZone == RsiZone.WEAK)
			return EntryDecision.SKIP_RSI_WEAK;
		if (volumeZone == VolumeZone.WEAK)
			return EntryDecision.SKIP_LOW_VOLUME;
		if (zone == PriceZone.OVEREXTENDED)
			return grade == TrendGrade.S_PLUS_PLUS ? EntryDecision.WAIT_PULLBACK : EntryDecision.SKIP_OVEREXTENDED;
		if (zone == PriceZone.STRETCHED || rsiZone == RsiZone.OVERBOUGHT)
			return EntryDecision.PROCEED_REDUCED;
		return EntryDecision.PROCEED_FULL;
	}

	private String resolvePositionSize(PriceZone priceZone, TrendGrade trendGrade, RsiZone rsiZone,
			VolumeZone volumeZone) {
		// All green — full position
		if (priceZone == PriceZone.IDEAL && (trendGrade == TrendGrade.S_PLUS_PLUS || trendGrade == TrendGrade.A)
				&& (rsiZone == RsiZone.IDEAL || rsiZone == RsiZone.STRONG) && (volumeZone == VolumeZone.SURGE
						|| volumeZone == VolumeZone.STRONG || volumeZone == VolumeZone.ABOVE_AVG))
			return "100%";
		if (priceZone == PriceZone.STRETCHED || rsiZone == RsiZone.OVERBOUGHT)
			return "50%";
		if (trendGrade == TrendGrade.C)
			return "40%";
		if (trendGrade == TrendGrade.B)
			return "60%";
		if (volumeZone == VolumeZone.NORMAL)
			return "70%";
		if (priceZone == PriceZone.ACCEPTABLE)
			return "80%";
		return "50%";
	}

	// =========================================================================
	// STOP LOSS
	// =========================================================================
	private double resolveStopLoss(double price, double ma20, double ma50, TimeframeConfig tf, Double atr,
			List<String> warnings) {
		if (atr != null && atr > 0) {
			double atrSl = round4(price - 1.5 * atr);
			double maSl = round4(Math.max(ma20, ma50) * (1 - tf.slBuffer()));
			double sl = Math.min(atrSl, maSl);
			warnings.add(String.format("✅ ATR14 SL: %.4f - (1.5 × %.4f) = %.4f", price, atr, sl));
			return sl;
		}
		warnings.add("⚠️ ATR14 not provided — using MA-buffer SL. Add ATR14 for dynamic SL.");
		double anchor = (Math.abs(price - ma50) < Math.abs(price - ma20)) ? ma50 : ma20;
		return round4(anchor * (1 - tf.slBuffer()));
	}

	// =========================================================================
	// REGIME
	// =========================================================================
	private MarketRegime resolveRegime(double price, double ma200, List<String> warnings, List<String> confluences) {
		double pct = (price - ma200) / ma200 * 100;
		if (price > ma200 * 1.02) {
			confluences.add(String.format("✅ Price %.2f%% above MA200 — BULL market regime", pct));
			return MarketRegime.BULL;
		}
		if (price < ma200 * 0.98) {
			warnings.add(String.format("🔴 Price %.2f%% below MA200 (%.4f) — BEAR regime", Math.abs(pct), ma200));
			return MarketRegime.BEAR;
		}
		warnings.add("⚠️ Price within 2% of MA200 — transitional / choppy zone");
		return MarketRegime.NEUTRAL;
	}

	// =========================================================================
	// RESPONSE BUILDERS
	// =========================================================================
	private Map<String, Object> buildFilterSteps(PriceZone priceZone, TrendGrade trendGrade, RsiZone rsiZone,
			VolumeZone volumeZone, MarketRegime regime, MaCalculationRequest req, double pctAboveMA20,
			double ma20VsMa50Pct) {
		Map<String, Object> steps = new LinkedHashMap<>();
		steps.put("step1", Map.of("name", "MA Alignment", "rule", "MA5 > MA20 > MA50", "status", "PASS ✅", "note",
				"Full bullish stack confirmed"));
		steps.put("step2",
				Map.of("name", "Price vs MA20 (Formula 1)", "rule", "0% < ((Price-MA20)/MA20×100) < 5%", "result",
						String.format("%.2f%%", pctAboveMA20), "zone", priceZone.label, "status",
						priceZone == PriceZone.IDEAL || priceZone == PriceZone.ACCEPTABLE ? "PASS ✅"
								: priceZone == PriceZone.STRETCHED ? "WARN ⚠️" : "FAIL 🔴"));
		steps.put("step3",
				Map.of("name", "Trend Maturity (Formula 2)", "rule", "1% < ((MA20-MA50)/MA50×100) < 8%", "result",
						String.format("%.2f%%", ma20VsMa50Pct), "grade", trendGrade.grade, "status",
						trendGrade == TrendGrade.S_PLUS_PLUS || trendGrade == TrendGrade.A ? "PASS ✅"
								: trendGrade == TrendGrade.B || trendGrade == TrendGrade.C ? "WARN ⚠️" : "FAIL 🔴"));
		steps.put("step4",
				req.getRsi14() != null
						? Map.of("name", "RSI14 Momentum", "rule", "50 ≤ RSI14 ≤ 70", "result",
								String.format("%.1f", req.getRsi14()), "zone", rsiZone.label, "status",
								rsiZone == RsiZone.IDEAL || rsiZone == RsiZone.STRONG ? "PASS ✅"
										: rsiZone == RsiZone.RECOVERING || rsiZone == RsiZone.OVERBOUGHT ? "WARN ⚠️"
												: "FAIL 🔴")
						: Map.of("name", "RSI14 Momentum", "status", "SKIP ⚪", "note", "Not provided"));
		steps.put("step5",
				req.getVolumeRatio() != null
						? Map.of("name", "Volume Ratio", "rule", "volumeRatio > 1.2×", "result",
								String.format("%.2f×", req.getVolumeRatio()), "zone", volumeZone.label, "status",
								volumeZone == VolumeZone.SURGE || volumeZone == VolumeZone.STRONG
										|| volumeZone == VolumeZone.ABOVE_AVG ? "PASS ✅"
												: volumeZone == VolumeZone.NORMAL ? "NEUTRAL ⚪" : "FAIL 🔴")
						: Map.of("name", "Volume Ratio", "status", "SKIP ⚪", "note", "Not provided"));
		return steps;
	}

	private Map<String, Object> buildInput(MaCalculationRequest req, TimeframeConfig tf) {
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("marketPrice", req.getMarketPrice());
		input.put("ma5", req.getMa5());
		input.put("ma20", req.getMa20());
		input.put("ma50", req.getMa50());
		input.put("ma200", req.getMa200());
		input.put("timeframe", tf.label());
		input.put("holdDuration", tf.holdDuration());
		if (req.getRsi14() != null)
			input.put("rsi14", req.getRsi14());
		if (req.getAtr14() != null)
			input.put("atr14", req.getAtr14());
		if (req.getVolumeRatio() != null)
			input.put("volumeRatio", req.getVolumeRatio() + "×");
		return input;
	}

	private Map<String, Object> buildSkip(Map<String, Object> resp, String reason, Map<String, Object> detail) {
		resp.put("decision", "SKIP");
		resp.put("emoji", "🚫");
		resp.put("reason", reason);
		resp.put("detail", detail);
		return resp;
	}

	private String buildAdvice(RiskLevel level, PriceZone priceZone, TrendGrade trendGrade, RsiZone rsiZone,
			VolumeZone volumeZone, TimeframeConfig tf, double pctAboveMA20, double ma5, double ma20, double ma50,
			double ma200) {
		String posSize = resolvePositionSize(priceZone, trendGrade, rsiZone, volumeZone);
		return switch (level) {
		case HIGH_SKIP -> String.format("[%s] SKIP — %s. Wait for all 5 filters to align.", tf.label(),
				buildSkipReason(priceZone, trendGrade, rsiZone, volumeZone));
		case MEDIUM_PROCEED -> String.format(
				"[%s] PROCEED with caution — %s zone, Grade %s, RSI %s, Vol %s. "
						+ "Reduce size to %s. Ideal entry: MA5 (%.4f). Hold: %s.",
				tf.label(), priceZone.label, trendGrade.grade, rsiZone.label, volumeZone.label, posSize, ma5,
				tf.holdDuration());
		case LOW_PROCEED -> String.format(
				"[%s] PROCEED — All filters green. %s zone ✅ Grade %s ✅ RSI %s ✅ Vol %s ✅. "
						+ "Position size: %s. MA200=%.4f confirms bull. Hold: %s.",
				tf.label(), priceZone.label, trendGrade.grade, rsiZone.label, volumeZone.label, posSize, ma200,
				tf.holdDuration());
		};
	}

	private String buildSkipReason(PriceZone priceZone, TrendGrade trendGrade, RsiZone rsiZone, VolumeZone volumeZone) {
		if (trendGrade == TrendGrade.EXHAUSTED)
			return "trend exhausted >10%";
		if (trendGrade == TrendGrade.BEARISH)
			return "MA20 below MA50 — downtrend";
		if (priceZone == PriceZone.OVEREXTENDED)
			return "price >10% above MA20";
		if (rsiZone == RsiZone.EXTREME_OB)
			return "RSI extremely overbought (>80)";
		if (rsiZone == RsiZone.WEAK)
			return "RSI weak momentum (<40)";
		if (volumeZone == VolumeZone.WEAK)
			return "volume ratio <0.8× — no buyers";
		return "multiple filters failed";
	}

	private Map<String, Object> buildFormula1(double price, double ma20, double pct, PriceZone zone) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("label", "Price vs MA20 Distance");
		f.put("formula", "((marketPrice - MA20) / MA20) × 100");
		f.put("calculation", String.format("((%.4f - %.4f) / %.4f) × 100", price, ma20, ma20));
		f.put("result", String.format("%.2f%%", pct));
		f.put("zone", zone.label);
		f.put("emoji", zone.emoji);
		f.put("meaning", zone.meaning);
		return f;
	}

	private Map<String, Object> buildFormula2(double ma20, double ma50, double pct, TrendGrade grade) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("label", "MA20 vs MA50 Trend Strength");
		f.put("formula", "((MA20 - MA50) / MA50) × 100");
		f.put("calculation", String.format("((%.4f - %.4f) / %.4f) × 100", ma20, ma50, ma50));
		f.put("result", String.format("%.2f%%", pct));
		f.put("grade", grade.grade);
		f.put("emoji", grade.emoji);
		f.put("meaning", grade.meaning);
		return f;
	}

	private String buildSummary(double f1, double f2, PriceZone zone, TrendGrade grade, TimeframeConfig tf) {
		return String.format("[%s] Price %.2f%% %s MA20 (%s). Trend maturity: %.2f%% → Grade %s — %s.", tf.label(),
				Math.abs(f1), f1 >= 0 ? "above" : "below", zone.label, f2, grade.grade, grade.meaning);
	}

	private Map<String, Object> buildEntryGuidance(EntryDecision decision, TrendGrade grade, PriceZone zone,
			RsiZone rsiZone, VolumeZone volumeZone, double ma20, double ma50, TimeframeConfig tf) {
		Map<String, Object> g = new LinkedHashMap<>();
		g.put("action", decision.label);
		g.put("positionSize", decision.positionSize);
		g.put("note", decision.note);
		g.put("rsiStatus", rsiZone.label);
		g.put("volumeStatus", volumeZone.label);
		g.put("waitFor", switch (zone) {
		case BELOW_MA20, OVEREXTENDED, STRETCHED -> String.format("Pullback to MA20 (%.4f) or MA50 (%.4f)", ma20, ma50);
		default -> "Current price is acceptable entry zone";
		});
		g.put("holdDuration", tf.holdDuration());
		g.put("gradeScale", "S++ (1–3%) → A (3–5%) → B (5–8%) → C (8–10%) → EXHAUSTED (>10%)");
		g.put("filterSummary", String.format("Step1:MA✅ | Step2:%s | Step3:%s | Step4:%s | Step5:%s", zone.emoji,
				grade.emoji, rsiZone.emoji, volumeZone.emoji));
		return g;
	}

	private Map<String, Object> buildCalculation(double price, double ma20, double ma50, double ma200,
			double pctAboveMA20, double pctAboveMA5, double ma5AboveMA20, double pctAboveMA50) {
		return Map.of("formula", "(marketPrice - MA) / MA × 100", "pctAboveMA20", String.format("%.2f%%", pctAboveMA20),
				"pctAboveMA5", String.format("%.2f%%", pctAboveMA5), "pctAboveMA50",
				String.format("%.2f%%", pctAboveMA50), "pctAboveMA200",
				String.format("%.2f%%", (price - ma200) / ma200 * 100), "ma5AboveMA20",
				String.format("%.2f%%", ma5AboveMA20), "result",
				String.format("(%.4f - %.4f) / %.4f × 100 = %.2f%%", price, ma20, ma20, pctAboveMA20), "thresholds",
				"≤2% Ideal | 2–5% Acceptable | 5–10% Stretched | >10% Overextended");
	}

	private Map<String, Object> buildMaAlignment(double price, double ma5, double ma20, double ma50, double ma200) {
		boolean p5 = price > ma5, p20 = price > ma20, p50 = price > ma50, p200 = price > ma200;
		boolean f5 = ma5 > ma20, m50 = ma20 > ma50, l200 = ma50 > ma200;
		String trend = (p5 && p20 && p50 && p200 && f5 && m50 && l200)
				? "✅ Strong Bull — Price > MA5 > MA20 > MA50 > MA200"
				: (p20 && p50 && p200) ? "✅ Bullish — Price > MA20 > MA50 > MA200"
						: (p20 && p50) ? "⚠️ Moderate — Price > MA20 > MA50 but below MA200"
								: "⚠️ Weak / Mixed — partial MA alignment only";
		return Map.of("priceAboveMA5", p5, "priceAboveMA20", p20, "priceAboveMA50", p50, "priceAboveMA200", p200,
				"ma5AboveMA20", f5, "ma20AboveMA50", m50, "ma50AboveMA200", l200, "trend", trend);
	}

	private Map<String, Object> buildTradePlan(double price, double ma20, double ma50, TimeframeConfig tf,
			double entryIdeal, double entryMax, double stopLoss, double risk_R, double tp1, double tp2, double tp3,
			Double atr) {
		Map<String, Object> plan = new LinkedHashMap<>();
		plan.put("entryIdeal", entryIdeal);
		plan.put("entryMax", entryMax);
		plan.put("entryNote",
				entryIdeal < price
						? String.format("Queue at MA5 (%.4f). Max entry if no pullback = %.4f.", entryIdeal, entryMax)
						: String.format("Enter at current price (%.4f) — already at MA5 level.", entryIdeal));
		plan.put("stopLoss", stopLoss);
		plan.put("stopLossNote",
				atr != null ? String.format("ATR14-based: %.4f - (1.5 × %.4f) = %.4f", price, atr, stopLoss)
						: String.format("MA-buffer: %.4f%% below anchor (MA20=%.4f / MA50=%.4f)", tf.slBuffer() * 100,
								ma20, ma50));
		plan.put("riskPerUnit", risk_R);
		plan.put("holdDuration", tf.holdDuration());
		plan.put("tp1", tp1);
		plan.put("tp1Note", String.format("Sell 40%% at %.4f  (R:R 1:1.0) — move SL to breakeven", tp1));
		plan.put("tp2", tp2);
		plan.put("tp2Note", String.format("Sell 40%% at %.4f  (R:R 1:2.0)", tp2));
		plan.put("tp3", tp3);
		plan.put("tp3Note", String.format("Sell 20%% at %.4f  (R:R 1:3.5) — trail or hold", tp3));
		return plan;
	}

	// =========================================================================
	// HELPERS
	// =========================================================================
	private PriceZone resolvePriceZone(double pct) {
		if (pct < 0)
			return PriceZone.BELOW_MA20;
		if (pct <= 2.0)
			return PriceZone.IDEAL;
		if (pct <= 5.0)
			return PriceZone.ACCEPTABLE;
		if (pct <= 10.0)
			return PriceZone.STRETCHED;
		return PriceZone.OVEREXTENDED;
	}

	private TrendGrade resolveTrendGrade(double pct) {
		if (pct < 0)
			return TrendGrade.BEARISH;
		if (pct <= 1.0)
			return TrendGrade.FLAT;
		if (pct <= 3.0)
			return TrendGrade.S_PLUS_PLUS;
		if (pct <= 5.0)
			return TrendGrade.A;
		if (pct <= 8.0)
			return TrendGrade.B;
		if (pct <= 10.0)
			return TrendGrade.C;
		return TrendGrade.EXHAUSTED;
	}

	private double round4(double v) {
		return Math.round(v * 10000.0) / 10000.0;
	}

	private double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	private double round0(double v) {
		return Math.round(v);
	}

	// =========================================================================
	// ENUMS & RECORDS
	// =========================================================================
	private record SignalScore(int score, int maxScore, String grade) {
	}

	@Getter
	@RequiredArgsConstructor
	enum PriceZone {
		BELOW_MA20("Below MA20", "🚫", "Support broken — downtrend"),
		IDEAL("Ideal Zone", "🎯", "0–2% above MA20 — best entry zone"),
		ACCEPTABLE("Acceptable", "🟢", "2–5% above MA20 — valid entry"),
		STRETCHED("Stretched", "🟡", "5–10% above MA20 — reduce size"),
		OVEREXTENDED("Overextended", "🔴", ">10% above MA20 — do not chase");

		private final String label, emoji, meaning;
	}

	@Getter
	@RequiredArgsConstructor
	enum TrendGrade {
		BEARISH("BEARISH", "🔻", "MA20 below MA50 — downtrend, avoid longs"),
		FLAT("FLAT", "➡️", "<1% gap — crossover zone, wait for separation"),
		S_PLUS_PLUS("S++", "🚀", "1–3% gap — trend just began, highest probability"),
		A("A", "✅", "3–5% gap — healthy trend, good entry"), B("B", "⚠️", "5–8% gap — maturing trend, smaller size"),
		C("C", "🟡", "8–10% gap — late trend, tight SL required"),
		EXHAUSTED("EXHAUSTED", "🛑", ">10% gap — trend exhausted, do not chase");

		private final String grade, emoji, meaning;
	}

	@Getter
	@RequiredArgsConstructor
	enum RsiZone {
		EXTREME_OB("Extremely Overbought (>80)", "🔴", "Hard skip — reversal imminent"),
		OVERBOUGHT("Overbought (70–80)", "🟡", "Reduce size 50%"),
		STRONG("Strong Momentum (65–70)", "🟢", "Valid — watch closely"),
		IDEAL("Ideal Zone (50–65)", "✅", "Best momentum zone"),
		RECOVERING("Recovering (40–50)", "⚪", "Wait for RSI to cross 50"),
		WEAK("Weak (<40)", "🔴", "Skip — momentum failing"), UNKNOWN("Not Provided", "⚪", "Unconfirmed");

		private final String label, emoji, meaning;
	}

	@Getter
	@RequiredArgsConstructor
	enum VolumeZone {
		SURGE("Surge (≥2.0×)", "🚀", "Institutional / news driven"),
		STRONG("Strong (1.5–2.0×)", "✅", "Clear accumulation"),
		ABOVE_AVG("Above Avg (1.2–1.5×)", "🟢", "Buyers stepping in"),
		NORMAL("Normal (0.8–1.2×)", "⚪", "No strong conviction"), WEAK("Weak (<0.8×)", "🔴", "No buyers — avoid"),
		UNKNOWN("Not Provided", "⚪", "Unconfirmed");

		private final String label, emoji, meaning;
	}

	@Getter
	@RequiredArgsConstructor
	enum EntryDecision {
		PROCEED_FULL("PROCEED ✅", "🚀", "100%", "All 5 filters aligned — full position"),
		PROCEED_REDUCED("PROCEED (reduced) 🟡", "🟡", "50%", "Some filters yellow — reduce size"),
		WAIT_PULLBACK("WAIT ⏳", "⏳", "0%", "Fresh trend (S++) but price overextended — wait for MA20"),
		SKIP_BEARISH("SKIP 🔻", "🔻", "0%", "MA20 below MA50 — no long trades"),
		SKIP_EXHAUSTED("SKIP 🛑", "🛑", "0%", "Trend >10% exhausted — high reversal risk"),
		SKIP_BELOW_MA20("SKIP 🚫", "🚫", "0%", "Price below MA20 — support broken"),
		SKIP_OVEREXTENDED("SKIP 🔴", "🔴", "0%", "Price >10% above MA20 — do not chase"),
		SKIP_RSI_OB("SKIP 🔴", "🔴", "0%", "RSI >80 — extremely overbought"),
		SKIP_RSI_WEAK("SKIP 🔴", "🔴", "0%", "RSI <40 — momentum failing"),
		SKIP_LOW_VOLUME("SKIP 🔴", "🔴", "0%", "Volume ratio <0.8× — no buyers");

		private final String label, emoji, positionSize, note;
	}

	@Getter
	@RequiredArgsConstructor
	enum RiskLevel {
		LOW_PROCEED("Low Risk", "🟢", "PROCEED"), MEDIUM_PROCEED("Medium Risk", "🟡", "PROCEED"),
		HIGH_SKIP("High Risk", "🔴", "SKIP");

		private final String label, emoji, decision;
	}

	enum MarketRegime {
		BULL, BEAR, NEUTRAL
	}

	enum TrendStrength {
		STRONG, MODERATE, WEAK
	}

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
}