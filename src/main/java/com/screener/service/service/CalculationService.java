package com.screener.service.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.screener.service.dto.HoldCalculation;
import com.screener.service.dto.HoldRange;
import com.screener.service.dto.Level2Request;
import com.screener.service.dto.MaCalculationRequest;
import com.screener.service.dto.SignalScore;
import com.screener.service.dto.TimeframeConfig;
import com.screener.service.dto.TrendDistanceRequest;
import com.screener.service.enums.EntryDecision;
import com.screener.service.enums.KdjZone;
import com.screener.service.enums.MacdMomentumZone;
import com.screener.service.enums.MarketRegime;
import com.screener.service.enums.PriceZone;
import com.screener.service.enums.RiskLevel;
import com.screener.service.enums.RsiZone;
import com.screener.service.enums.TrendGrade;
import com.screener.service.enums.VolumeZone;
import com.screener.service.model.Level2Entry;

import lombok.extern.slf4j.Slf4j;

@Slf4j
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
		double bidW = bids.get(0).volume(), askW = asks.get(0).volume();
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

	// ══════════════════════════════════════════════════════════════════
	// MA CALCULATOR — 6-Filter Stack
	// ══════════════════════════════════════════════════════════════════
	public Map<String, Object> maCalculate(MaCalculationRequest req) {
		double price = req.getMarketPrice();
		double ma5 = req.getMa5();
		double ma20 = req.getMa20();
		double ma50 = req.getMa50();
		// ATR is needed early for queue price planner
		Double atr14 = req.getAtr14();
		TimeframeConfig tf = TimeframeConfig.of(req.getTimeframe());
		List<String> warnings = new ArrayList<>();
		List<String> confluences = new ArrayList<>();
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("input", buildInput(req, tf));
		// ── STEP 1: MA alignment hard-stops — null-safe ───────────────
		if (price < ma20)
			return buildSkip(resp,
					String.format("STEP 1 FAIL — Price (%.4f) below MA20 (%.4f). Support broken.", price, ma20),
					Map.of("priceVsMA20", String.format("%.4f below MA20", ma20 - price), "note",
							"Price below MA20 = downtrend. Wait for MA20 reclaim."));
		if (ma5 < ma20)
			return buildSkip(resp,
					String.format("STEP 1 FAIL — MA5 (%.4f) below MA20 (%.4f). No golden cross yet.", ma5, ma20),
					Map.of("gap",
							String.format("MA5 is %.4f (%.2f%%) below MA20", ma20 - ma5, (ma20 - ma5) / ma20 * 100),
							"note", "Wait for MA5 ≥ MA20 golden cross."));
		if (ma20 < ma50)
			return buildSkip(resp,
					String.format("STEP 1 FAIL — MA20 (%.4f) below MA50 (%.4f). Medium trend bearish.", ma20, ma50),
					Map.of("gap",
							String.format("MA20 is %.4f (%.2f%%) below MA50", ma50 - ma20, (ma50 - ma20) / ma50 * 100),
							"note", "MA5 > MA20 > MA50 stack must be fully aligned."));
		confluences.add("✅ STEP 1 PASS — MA5 > MA20 > MA50 fully aligned");
		// ── Distance calculations ──────────────────────────────────────
		double pctAboveMA20 = (price - ma20) / ma20 * 100;
		double pctAboveMA5 = (price - ma5) / ma5 * 100;
		double ma5AboveMA20 = (ma5 - ma20) / ma20 * 100;
		double pctAboveMA50 = (price - ma50) / ma50 * 100;
		double ma20VsMa50Pct = (ma20 - ma50) / ma50 * 100;
		PriceZone priceZone = resolvePriceZone(pctAboveMA20);
		TrendGrade trendGrade = resolveTrendGrade(ma20VsMa50Pct);
		resolveStep2(priceZone, pctAboveMA20, ma20, confluences, warnings);
		resolveStep3(trendGrade, ma20VsMa50Pct, confluences, warnings);
		// ── STEP 4: KDJ — null-safe, no duplicate branches ────────────
		KdjZone kdjZone = resolveKdjZone(req.getKdjK(), req.getKdjD(), req.getKdjJ(), confluences, warnings);
		// KDJ divergence check — only when both price gap AND KDJ are available
		checkKdjDivergence(price, ma5, req.getKdjJ(), warnings);
		// ── STEP 5: MACD DIF / DEA / Histogram ───────────────────────
		MacdMomentumZone macdZone = resolveMacdZone(req.getMacdDif(), req.getMacdDea(), req.getMacdHistogram(),
				confluences, warnings);
		// ── STEP 6: Volume conviction ──────────────────────────────────
		VolumeZone volumeZone = VolumeZone.UNKNOWN;
		if (req.getVolumeRatio() != null)
			volumeZone = resolveVolumeZone(req.getVolumeRatio(), confluences, warnings);
		else
			warnings.add("⚠️ STEP 6 — Volume ratio not provided, conviction unconfirmed");
		// ── RSI14 optional add-on ─────────────────────────────────────
		RsiZone rsiZone = RsiZone.UNKNOWN;
		if (req.getRsi14() != null)
			rsiZone = resolveRsiZone(req.getRsi14(), confluences, warnings);
		// ── Scoring & risk ─────────────────────────────────────────────
		SignalScore signalScore = resolveSignalScore(priceZone, trendGrade, rsiZone, volumeZone, kdjZone, macdZone);
		RiskLevel riskLevel = resolveRiskLevel(priceZone, trendGrade, rsiZone, volumeZone, kdjZone, macdZone);
		HoldCalculation hold = resolveHoldDays(pctAboveMA20, ma20VsMa50Pct, req.getRsi14(), req.getVolumeRatio(), atr14,
				price, tf);
		// ── Response assembly ──────────────────────────────────────────
		resp.put("decision", riskLevel.decision);
		resp.put("emoji", riskLevel.emoji);
		resp.put("risk", riskLevel.label);
		resp.put("signalQuality", signalScore.grade());
		resp.put("signalScore", signalScore.score() + " / " + signalScore.maxScore());
		resp.put("trendGrade", trendGrade.grade);
		resp.put("rsiZone", rsiZone.label);
		resp.put("volumeZone", volumeZone.label);
		resp.put("kdjZone", kdjZone.label);
		resp.put("kdjEmoji", kdjZone.emoji);
		resp.put("kdjMeaning", kdjZone.meaning);
		resp.put("macdZone", macdZone.label);
		resp.put("macdEmoji", macdZone.emoji);
		resp.put("macdMeaning", macdZone.meaning);
		resp.put("holdDays", buildHoldDaysMap(hold));
		resp.put("advice",
				buildAdvice(riskLevel, priceZone, trendGrade, rsiZone, volumeZone, kdjZone, macdZone, tf, ma5, hold));
		resp.put("warnings", warnings);
		resp.put("confluences", confluences);
		resp.put("filterSteps", buildFilterSteps(priceZone, trendGrade, rsiZone, volumeZone, kdjZone, macdZone, req,
				pctAboveMA20, ma20VsMa50Pct));
		resp.put("calculation", buildCalculation(price, ma20, pctAboveMA20, pctAboveMA5, ma5AboveMA20, pctAboveMA50));
		resp.put("maAlignment", buildMaAlignment(price, ma5, ma20, ma50));
		if ("SKIP".equals(riskLevel.decision))
			return resp;
		// ── Trade plan + Queue Price Planner ──────────────────────────
		double entryIdeal = pctAboveMA5 <= 0.5 ? round4(price) : round4(ma5);
		double entryMax = round4(price);
		double stopLoss = resolveStopLoss(price, ma20, ma50, tf, atr14, warnings);
		double risk_R = round4(entryIdeal - stopLoss);
		double tp1 = round4(entryIdeal + risk_R * 1.0);
		double tp2 = round4(entryIdeal + risk_R * 2.0);
		double tp3 = round4(entryIdeal + risk_R * 3.5);
		resp.put("positionSize", resolvePositionSize(priceZone, trendGrade, rsiZone, volumeZone, kdjZone, macdZone));
		resp.put("tradePlan", buildTradePlan(price, ma5, ma20, ma50, tf, entryIdeal, entryMax, stopLoss, risk_R, tp1,
				tp2, tp3, atr14, hold));
		Map<String, Object> queuePricePlanner = buildQueuePricePlanner(price, ma5, stopLoss, risk_R, tp1, tp2, tp3,
				atr14);
		resp.put("queuePricePlanner", queuePricePlanner);
		return resp;
	}

	// ══════════════════════════════════════════════════════════════════
	// QUEUE PRICE PLANNER — answers "what if MA5 never fills?"
	//
	// Rule: gap = currentPrice − MA5
	// Level 1 (AGGRESSIVE) gap < 1× ATR → buy now, no queue needed
	// Level 2 (MODERATE) gap 1–2× ATR → queue at 38.2% fib pullback
	// Level 3 (CONSERVATIVE) gap > 2× ATR → queue at MA5 (original)
	// SPLIT FILL 50% now + 50% at MA5 (always available)
	// ══════════════════════════════════════════════════════════════════
	private Map<String, Object> buildQueuePricePlanner(double price, double ma5, double stopLoss, double baseRisk,
			double tp1, double tp2, double tp3, Double atr14) {
		Map<String, Object> planner = new LinkedHashMap<>();
		double gap = round4(price - ma5);
		// ── Without ATR: show gap in % only, give qualitative advice ──
		if (atr14 == null || atr14 <= 0) {
			double gapPct = round4((gap / ma5) * 100);
			planner.put("note",
					"Provide ATR14 for precise queue level selection. Gap = " + gap + " (" + gapPct + "% above MA5)");
			planner.put("splitFill", buildSplitFill(price, ma5, stopLoss, baseRisk, tp1, tp2, tp3));
			planner.put("qualitativeGuide",
					Map.of("gap%", String.format("%.2f%%", gapPct), "rule",
							"< 1% → enter now | 1–3% → queue at Fib 38.2% | > 3% → queue at MA5 or use split",
							"conservative", String.format("Queue at MA5: %.4f", ma5), "splitAdvice",
							"Enter 50% now + queue 50% at MA5. If MA5 never fills, you still caught half the move."));
			return planner;
		}
		// ── With ATR: precise 3-level decision ────────────────────────
		double atrMultiple = gap / atr14; // how many ATRs is the gap?
		double fib382 = round4(price - gap * 0.382); // 38.2% pullback
		double fib500 = round4(price - gap * 0.500); // 50% pullback (midpoint)
		double fib618 = round4(price - gap * 0.618); // 61.8% pullback ≈ MA5 zone
		// Recalculate R:R from each queue level (SL stays same)
		double risk1 = round4(price - stopLoss);
		double risk2 = round4(fib382 - stopLoss);
		double risk3 = round4(ma5 - stopLoss);
		planner.put("gapAnalysis", Map.of("currentPrice", price, "ma5", ma5, "gap", gap, "atr14", atr14, "gapInATRs",
				String.format("%.2f× ATR", atrMultiple), "interpretation",
				atrMultiple < 1.0 ? "Gap < 1× ATR — MA5 likely won't fill. Enter now."
						: atrMultiple < 2.0 ? "Gap 1–2× ATR — Moderate chance of pullback. Split or Fib queue."
								: "Gap > 2× ATR — High chance of at least partial pullback. Queue recommended."));
		// ── Level 1: AGGRESSIVE — enter at current price ──────────────
		Map<String, Object> level1 = new LinkedHashMap<>();
		level1.put("label", "AGGRESSIVE — Enter Now");
		level1.put("emoji", atrMultiple < 1.0 ? "🟢 RECOMMENDED" : "🟡 USE ONLY IF MOMENTUM STRONG");
		level1.put("queuePrice", price);
		level1.put("rationale", atrMultiple < 1.0 ? "Gap (" + gap
				+ ") is smaller than 1 ATR — price is unlikely to pullback to MA5. Entering now captures the move."
				: "Gap is larger than 1 ATR. Entering now means accepting higher risk and lower R:R. Only if KDJ + MACD both strongly bullish.");
		level1.put("stopLoss", stopLoss);
		level1.put("riskPerUnit", risk1);
		level1.put("tp1", tp1);
		level1.put("tp2", tp2);
		level1.put("tp3", tp3);
		level1.put("rrRatio", risk1 > 0 ? "1:" + round2((tp2 - price) / risk1) : "N/A");
		level1.put("positionNote", "Full planned size");
		planner.put("level1_aggressive", level1);
		// ── Level 2: MODERATE — queue at Fib 38.2% ────────────────────
		Map<String, Object> level2 = new LinkedHashMap<>();
		level2.put("label", "MODERATE — Queue at Fib 38.2% Pullback");
		level2.put("emoji", atrMultiple >= 1.0 && atrMultiple < 2.0 ? "🟢 RECOMMENDED" : "⚪ OPTIONAL");
		level2.put("queuePrice", fib382);
		level2.put("fibMidpoint", fib500);
		level2.put("rationale",
				String.format(
						"38.2%% Fibonacci retracement of the gap (%.4f→%.4f). "
								+ "This is where first natural hesitation occurs in a trending stock. "
								+ "Better R:R than entering now, more likely to fill than waiting at MA5.",
						price, ma5));
		level2.put("stopLoss", stopLoss);
		level2.put("riskPerUnit", risk2);
		level2.put("tp1", round4(fib382 + risk2 * 1.0));
		level2.put("tp2", round4(fib382 + risk2 * 2.0));
		level2.put("tp3", round4(fib382 + risk2 * 3.5));
		level2.put("rrRatio", risk2 > 0 ? "1:" + round2((fib382 + risk2 * 2.0 - fib382) / risk2) : "N/A");
		level2.put("missFillRisk", "If price does not pullback to " + fib382
				+ ", you miss the trade at this level. Use split fill as backup.");
		planner.put("level2_moderate", level2);
		// ── Level 3: CONSERVATIVE — queue at MA5 ──────────────────────
		Map<String, Object> level3 = new LinkedHashMap<>();
		level3.put("label", "CONSERVATIVE — Queue at MA5 (Original)");
		level3.put("emoji", atrMultiple >= 2.0 ? "🟢 RECOMMENDED" : "⚪ BEST R:R BUT MAY NOT FILL");
		level3.put("queuePrice", ma5);
		level3.put("fib618", fib618);
		level3.put("rationale",
				String.format(
						"MA5 (%.4f) is the strongest dynamic support in an uptrend. "
								+ "Best risk/reward because stop-loss is just below MA20. "
								+ "BUT: gap is %.2f× ATR — %s chance of filling.",
						ma5, atrMultiple, atrMultiple < 1.0 ? "LOW" : atrMultiple < 2.0 ? "MODERATE" : "HIGH"));
		level3.put("stopLoss", stopLoss);
		level3.put("riskPerUnit", risk3);
		level3.put("tp1", round4(ma5 + risk3 * 1.0));
		level3.put("tp2", round4(ma5 + risk3 * 2.0));
		level3.put("tp3", round4(ma5 + risk3 * 3.5));
		level3.put("rrRatio", risk3 > 0 ? "1:" + round2((ma5 + risk3 * 2.0 - ma5) / risk3) : "N/A");
		level3.put("cancelNote",
				"If price breaks above entryMax without touching MA5 — cancel this queue and reassess.");
		planner.put("level3_conservative", level3);
		// ── Split Fill — always available, the safest compromise ───────
		planner.put("splitFill", buildSplitFill(price, ma5, stopLoss, baseRisk, tp1, tp2, tp3));
		// ── Recommendation ────────────────────────────────────────────
		String rec, recEmoji;
		if (atrMultiple < 1.0) {
			rec = "Gap is less than 1 ATR. MA5 pullback is unlikely before the next leg up. Use LEVEL 1 (enter now) for the full position, or SPLIT FILL as a compromise.";
			recEmoji = "🟢";
		} else if (atrMultiple < 2.0) {
			rec = "Gap is 1–2× ATR. A partial pullback is possible. Use LEVEL 2 (Fib 38.2%) for the best balance between fill probability and R:R. Have LEVEL 1 ready as backup if momentum surges.";
			recEmoji = "🟡";
		} else {
			rec = "Gap is over 2× ATR. A real pullback to at least Fib 38.2% is statistically likely. Use LEVEL 3 (MA5) for full position if you can monitor. Otherwise SPLIT FILL to guarantee catching the move.";
			recEmoji = "🔵";
		}
		planner.put("recommendation",
				Map.of("emoji", recEmoji, "advice", rec, "gapInATRs", String.format("%.2f× ATR", atrMultiple)));
		return planner;
	}

	private Map<String, Object> buildSplitFill(double price, double ma5, double stopLoss, double baseRisk, double tp1,
			double tp2, double tp3) {
		Map<String, Object> split = new LinkedHashMap<>();
		split.put("label", "SPLIT FILL — Always Available");
		split.put("emoji", "✂️");
		split.put("part1",
				Map.of("size", "50% of planned position", "entryPrice", price, "action",
						"Enter immediately at market/limit current price", "rationale",
						"Guarantees you participate if price never pulls back"));
		split.put("part2",
				Map.of("size", "50% of planned position", "entryPrice", ma5, "action",
						"Limit order at MA5 — leave it working", "cancelRule",
						"Cancel if price runs more than 3% above current price without touching MA5", "rationale",
						"Lowers average cost if pullback occurs"));
		split.put("averageEntry", round4((price + ma5) / 2.0));
		split.put("splitAdvantage",
				"You never fully miss the move. Worst case: 50% in at a higher price. Best case: full position at a better average.");
		return split;
	}

	// ══════════════════════════════════════════════════════════════════
	// STEP 4 — KDJ (fixed: null check FIRST, no duplicate branches)
	// ══════════════════════════════════════════════════════════════════
	/**
	 * BUG FIX: The original email version computed (k - d) > 2.0 BEFORE checking
	 * for null, causing a guaranteed NPE when KDJ is not provided. Fixed by
	 * returning UNKNOWN immediately when any value is null.
	 */
	private KdjZone resolveKdjZone(Double k, Double d, Double j, List<String> confluences, List<String> warnings) {
		// ── NULL CHECK FIRST — always ──────────────────────────────────
		if (k == null || d == null || j == null) {
			warnings.add("⚠️ STEP 4 — KDJ not provided. Oscillator momentum unconfirmed.");
			return KdjZone.UNKNOWN;
		}
		double diff = k - d;
		boolean accelerating = diff > 2.0; // safe to compute now
		// ── Extreme values first ───────────────────────────────────────
		if (j > 100) {
			warnings.add(String.format(
					"🔴 STEP 4 FAIL — KDJ J=%.3f extreme extension (>100). Hard reversal imminent. Skip entry.", j));
			return KdjZone.OVERBOUGHT;
		}
		if (j > 80) {
			warnings.add(String.format(
					"🟡 STEP 4 WARN — KDJ Overbought: K=%.3f D=%.3f J=%.3f (J>80). Reduce size 50%%.", k, d, j));
			return KdjZone.OVERBOUGHT;
		}
		if (j < 20) {
			confluences.add(String.format(
					"💡 STEP 4 PASS — KDJ Oversold: K=%.3f D=%.3f J=%.3f (J<20). Bounce zone — wait for K>D confirm.",
					k, d, j));
			return KdjZone.OVERSOLD;
		}
		// ── Normal range — K vs D direction is the signal ─────────────
		if (k > d) {
			if (j >= 50) {
				if (accelerating)
					confluences.add(String.format(
							"✅ STEP 4 PASS — KDJ Bullish Strong ACCELERATING: K=%.3f > D=%.3f gap=%.3f J=%.3f", k, d,
							diff, j));
				else
					warnings.add(String.format(
							"⚠️ STEP 4 WARN — KDJ Bullish but NARROWING: K=%.3f > D=%.3f gap=%.3f J=%.3f — cross may flip soon",
							k, d, diff, j));
				return accelerating ? KdjZone.BULLISH_STRONG : KdjZone.BULLISH_WEAK;
			} else {
				// K > D but J still below 50 — early golden cross
				confluences.add(String.format(
						"🟢 STEP 4 PASS — KDJ Bullish Weak: K=%.3f > D=%.3f J=%.3f — early golden cross, momentum building.",
						k, d, j));
				return KdjZone.BULLISH_WEAK;
			}
		}
		// K ≈ D — undecided
		if (Math.abs(diff) < 2.0) {
			warnings.add(String.format(
					"⚪ STEP 4 NEUTRAL — KDJ K=%.3f ≈ D=%.3f gap=%.3f J=%.3f — near cross, watch for direction.", k, d,
					diff, j));
			return KdjZone.NEUTRAL;
		}
		// K < D — bearish
		warnings.add(String.format(
				"🔴 STEP 4 FAIL — KDJ Bearish: K=%.3f < D=%.3f gap=%.3f J=%.3f — downward pressure, avoid entry.", k, d,
				Math.abs(diff), j));
		return KdjZone.BEARISH;
	}

	/**
	 * BUG FIX: Original version called this with req.getKdjJ() without null guard.
	 * Now safely skips the check when KDJ is not provided.
	 */
	private void checkKdjDivergence(double price, double ma5, Double kdjJ, List<String> warnings) {
		if (kdjJ == null)
			return; // guard — was missing in original
		double pctAboveMA5 = (price - ma5) / ma5 * 100;
		if (pctAboveMA5 > 3.0 && kdjJ < 50) {
			warnings.add(String.format(
					"⚠️ DIVERGENCE WARNING — Price %.2f%% above MA5 but KDJ J=%.3f <50. "
							+ "Price is leading momentum — elevated reversal risk. Reduce size 50%%.",
					pctAboveMA5, kdjJ));
		}
	}

	// ══════════════════════════════════════════════════════════════════
	// STEP 5 — MACD (restored — was entirely missing from email version)
	// ══════════════════════════════════════════════════════════════════
	private MacdMomentumZone resolveMacdZone(Double dif, Double dea, Double hist, List<String> confluences,
			List<String> warnings) {
		if (dif == null || dea == null || hist == null) {
			warnings.add("⚠️ STEP 5 — MACD (DIF/DEA/Histogram) not provided. Trend momentum unconfirmed.");
			return MacdMomentumZone.UNKNOWN;
		}
		// Near-cross: histogram magnitude tiny regardless of direction
		if (Math.abs(hist) < 0.0005 && Math.abs(dif - dea) < 0.001) {
			warnings.add(String.format(
					"⚠️ STEP 5 WARN — MACD Near Cross: DIF=%.4f DEA=%.4f Hist=%.4f — watch for golden cross breakout.",
					dif, dea, hist));
			return MacdMomentumZone.NEAR_CROSS;
		}
		if (dif > dea && hist > 0) {
			if (dif > 0 && dea > 0) {
				confluences.add(String.format(
						"🚀 STEP 5 PASS — MACD Strong Bull: DIF=%.4f > DEA=%.4f (both above zero) Hist=%.4f — peak momentum.",
						dif, dea, hist));
				return MacdMomentumZone.STRONG_BULL;
			}
			confluences.add(String.format(
					"✅ STEP 5 PASS — MACD Bullish: DIF=%.4f > DEA=%.4f Hist=%.4f — upward momentum active.", dif, dea,
					hist));
			return MacdMomentumZone.BULL;
		}
		if (dif < 0 && dea < 0) {
			warnings.add(String.format(
					"🔻 STEP 5 FAIL — MACD Strong Bear: DIF=%.4f < DEA=%.4f (both below zero) Hist=%.4f — confirmed downtrend.",
					dif, dea, hist));
			return MacdMomentumZone.STRONG_BEAR;
		}
		warnings.add(String.format("🔴 STEP 5 FAIL — MACD Bearish: DIF=%.4f < DEA=%.4f Hist=%.4f — downward pressure.",
				dif, dea, hist));
		return MacdMomentumZone.BEARISH;
	}

	// ══════════════════════════════════════════════════════════════════
	// SIGNAL SCORE — updated to include MACD (was missing in email version)
	// Max = 12 (was 10 in email version — incorrect without MACD)
	// ══════════════════════════════════════════════════════════════════
	private SignalScore resolveSignalScore(PriceZone priceZone, TrendGrade trendGrade, RsiZone rsiZone,
			VolumeZone volumeZone, KdjZone kdjZone, MacdMomentumZone macdZone) {
		int score = 1; // baseline
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
		// RSI optional add-on (±1 only)
		score += switch (rsiZone) {
		case IDEAL, STRONG -> 1;
		case RECOVERING -> 0;
		case OVERBOUGHT -> -1;
		case EXTREME_OB, WEAK -> -1;
		case UNKNOWN -> 0;
		};
		// Volume
		score += switch (volumeZone) {
		case SURGE, STRONG -> 2;
		case ABOVE_AVG -> 1;
		case NORMAL -> 0;
		case WEAK -> -1;
		case UNKNOWN -> 0;
		};
		// KDJ — primary oscillator (±2)
		score += switch (kdjZone) {
		case BULLISH_STRONG -> 2;
		case BULLISH_WEAK, OVERSOLD -> 1;
		case NEUTRAL -> 0;
		case OVERBOUGHT -> -1;
		case BEARISH -> -2;
		case UNKNOWN -> 0;
		};
		// MACD — trend momentum (±3) ← was missing in email version
		score += switch (macdZone) {
		case STRONG_BULL -> 3;
		case BULL -> 2;
		case NEAR_CROSS -> 1;
		case BEARISH -> -1;
		case STRONG_BEAR -> -2;
		case UNKNOWN -> 0;
		};
		int maxScore = 12;
		int clamped = Math.max(0, score);
		String grade = clamped >= 10 ? "S++" : clamped >= 8 ? "A" : clamped >= 6 ? "B" : clamped >= 4 ? "C" : "WEAK";
		return new SignalScore(clamped, maxScore, grade);
	}

	// ══════════════════════════════════════════════════════════════════
	// RISK LEVEL — includes MACD guard (was missing in email version)
	// ══════════════════════════════════════════════════════════════════
	private RiskLevel resolveRiskLevel(PriceZone priceZone, TrendGrade trendGrade, RsiZone rsiZone,
			VolumeZone volumeZone, KdjZone kdjZone, MacdMomentumZone macdZone) {
		// Hard skips
		if (trendGrade == TrendGrade.EXHAUSTED || trendGrade == TrendGrade.BEARISH)
			return RiskLevel.HIGH_SKIP;
		if (priceZone == PriceZone.OVEREXTENDED)
			return RiskLevel.HIGH_SKIP;
		if (rsiZone == RsiZone.EXTREME_OB || rsiZone == RsiZone.WEAK)
			return RiskLevel.HIGH_SKIP;
		if (volumeZone == VolumeZone.WEAK)
			return RiskLevel.HIGH_SKIP;
		if (macdZone == MacdMomentumZone.STRONG_BEAR)
			return RiskLevel.HIGH_SKIP;
		// Double overbought = hard no (from email version — kept)
		if (kdjZone == KdjZone.OVERBOUGHT && priceZone == PriceZone.STRETCHED)
			return RiskLevel.HIGH_SKIP;
		// Unknown volume → be cautious
		if (volumeZone == VolumeZone.UNKNOWN)
			return RiskLevel.MEDIUM_PROCEED;
		// Medium risk
		if (priceZone == PriceZone.STRETCHED || trendGrade == TrendGrade.C || trendGrade == TrendGrade.FLAT
				|| rsiZone == RsiZone.OVERBOUGHT || rsiZone == RsiZone.RECOVERING || volumeZone == VolumeZone.NORMAL
				|| kdjZone == KdjZone.OVERBOUGHT || kdjZone == KdjZone.NEUTRAL || kdjZone == KdjZone.BEARISH
				|| macdZone == MacdMomentumZone.BEARISH || macdZone == MacdMomentumZone.NEAR_CROSS)
			return RiskLevel.MEDIUM_PROCEED;
		return RiskLevel.LOW_PROCEED;
	}

	// ══════════════════════════════════════════════════════════════════
	// POSITION SIZE — includes MACD
	// ══════════════════════════════════════════════════════════════════
	private String resolvePositionSize(PriceZone priceZone, TrendGrade trendGrade, RsiZone rsiZone,
			VolumeZone volumeZone, KdjZone kdjZone, MacdMomentumZone macdZone) {
		if (kdjZone == KdjZone.OVERBOUGHT && priceZone == PriceZone.STRETCHED)
			return "0% — SKIP";
		if (macdZone == MacdMomentumZone.STRONG_BEAR)
			return "0% — SKIP";
		boolean kdjStrong = kdjZone == KdjZone.BULLISH_STRONG;
		boolean macdStrong = macdZone == MacdMomentumZone.STRONG_BULL || macdZone == MacdMomentumZone.BULL;
		if (priceZone == PriceZone.IDEAL && (trendGrade == TrendGrade.S_PLUS_PLUS || trendGrade == TrendGrade.A)
				&& (rsiZone == RsiZone.IDEAL || rsiZone == RsiZone.STRONG || rsiZone == RsiZone.UNKNOWN
						|| rsiZone == RsiZone.RECOVERING)
				&& (volumeZone == VolumeZone.SURGE || volumeZone == VolumeZone.STRONG
						|| volumeZone == VolumeZone.ABOVE_AVG)
				&& kdjStrong && macdStrong)
			return "100%";
		if (kdjZone == KdjZone.OVERBOUGHT)
			return "30%";
		if (priceZone == PriceZone.STRETCHED || rsiZone == RsiZone.OVERBOUGHT || kdjZone == KdjZone.NEUTRAL)
			return "50%";
		if (trendGrade == TrendGrade.C)
			return "40%";
		if (trendGrade == TrendGrade.B)
			return "60%";
		if (!kdjStrong || !macdStrong)
			return "70%";
		if (volumeZone == VolumeZone.NORMAL)
			return "70%";
		if (priceZone == PriceZone.ACCEPTABLE)
			return "80%";
		return "50%";
	}

	// ══════════════════════════════════════════════════════════════════
	// STOP LOSS — using tighter of ATR vs MA-buffer (email version kept Math.max)
	// ══════════════════════════════════════════════════════════════════
	private double resolveStopLoss(double price, double ma20, double ma50, TimeframeConfig tf, Double atr,
			List<String> warnings) {
		double maSl = round4(Math.max(ma20, ma50) * (1 - tf.slBuffer()));
		if (atr != null && atr > 0) {
			double atrSl = round4(price - 1.5 * atr);
			// Math.max → tighter SL (higher price = closer to entry = smaller risk)
			double sl = Math.max(atrSl, maSl);
			warnings.add(String.format("✅ SL: ATR-based=%.4f  MA-based=%.4f  → using tighter (%.4f)", atrSl, maSl, sl));
			return sl;
		}
		warnings.add("⚠️ ATR14 not provided — using MA-buffer SL only. Add ATR14 for a tighter dynamic SL.");
		return maSl;
	}

	// ══════════════════════════════════════════════════════════════════
	// BUILD FILTER STEPS — step numbering corrected (volume = step 6)
	// ══════════════════════════════════════════════════════════════════
	private Map<String, Object> buildFilterSteps(PriceZone priceZone, TrendGrade trendGrade, RsiZone rsiZone,
			VolumeZone volumeZone, KdjZone kdjZone, MacdMomentumZone macdZone, MaCalculationRequest req,
			double pctAboveMA20, double ma20VsMa50Pct) {
		Map<String, Object> steps = new LinkedHashMap<>();
		steps.put("step1", Map.of("name", "MA Alignment", "rule", "MA5 > MA20 > MA50", "status", "PASS ✅", "note",
				"Full bullish stack confirmed"));
		steps.put("step2",
				Map.of("name", "Price vs MA20 (F1)", "rule", "0% < F1 < 5%", "result",
						String.format("%.2f%%", pctAboveMA20), "zone", priceZone.label, "status",
						priceZone == PriceZone.IDEAL || priceZone == PriceZone.ACCEPTABLE ? "PASS ✅"
								: priceZone == PriceZone.STRETCHED ? "WARN ⚠️" : "FAIL 🔴"));
		steps.put("step3",
				Map.of("name", "Trend Maturity (F2)", "rule", "1% < F2 < 8%", "result",
						String.format("%.2f%%", ma20VsMa50Pct), "grade", trendGrade.grade, "status",
						trendGrade == TrendGrade.S_PLUS_PLUS || trendGrade == TrendGrade.A ? "PASS ✅"
								: trendGrade == TrendGrade.B || trendGrade == TrendGrade.C ? "WARN ⚠️" : "FAIL 🔴"));
		// Step 4: KDJ
		steps.put("step4", req.getKdjK() != null
				? Map.of("name", "KDJ Momentum", "rule", "K>D, J 20–80", "result",
						String.format("K=%.3f D=%.3f J=%.3f", req.getKdjK(), req.getKdjD(), req.getKdjJ()), "zone",
						kdjZone.label, "status",
						kdjZone == KdjZone.BULLISH_STRONG || kdjZone == KdjZone.BULLISH_WEAK
								|| kdjZone == KdjZone.OVERSOLD
										? "PASS ✅"
										: kdjZone == KdjZone.NEUTRAL || kdjZone == KdjZone.OVERBOUGHT ? "WARN ⚠️"
												: "FAIL 🔴")
				: Map.of("name", "KDJ Momentum", "status", "SKIP ⚪", "note", "Not provided — momentum unconfirmed"));
		// Step 5: MACD — was labeled step5 but was Volume in email version (bug)
		steps.put("step5",
				req.getMacdDif() != null
						? Map.of("name", "MACD Momentum", "rule", "DIF>DEA, Histogram>0", "result",
								String.format("DIF=%.4f DEA=%.4f Hist=%.4f", req.getMacdDif(), req.getMacdDea(),
										req.getMacdHistogram()),
								"zone", macdZone.label, "status",
								macdZone == MacdMomentumZone.STRONG_BULL || macdZone == MacdMomentumZone.BULL ? "PASS ✅"
										: macdZone == MacdMomentumZone.NEAR_CROSS ? "WARN ⚠️" : "FAIL 🔴")
						: Map.of("name", "MACD Momentum", "status", "SKIP ⚪", "note",
								"Not provided — trend momentum unconfirmed"));
		// Step 6: Volume
		steps.put("step6",
				req.getVolumeRatio() != null
						? Map.of("name", "Volume Conviction", "rule", "ratio > 1.2×", "result",
								String.format("%.2f×", req.getVolumeRatio()), "zone", volumeZone.label, "status",
								volumeZone == VolumeZone.SURGE || volumeZone == VolumeZone.STRONG
										|| volumeZone == VolumeZone.ABOVE_AVG ? "PASS ✅"
												: volumeZone == VolumeZone.NORMAL ? "NEUTRAL ⚪" : "FAIL 🔴")
						: Map.of("name", "Volume Conviction", "status", "SKIP ⚪", "note",
								"Not provided — conviction unconfirmed"));
		return steps;
	}

	// ══════════════════════════════════════════════════════════════════
	// TRADE PLAN — includes queueNote referencing planner
	// ══════════════════════════════════════════════════════════════════
	private Map<String, Object> buildTradePlan(double price, double ma5, double ma20, double ma50, TimeframeConfig tf,
			double entryIdeal, double entryMax, double stopLoss, double risk_R, double tp1, double tp2, double tp3,
			Double atr, HoldCalculation hold) {
		Map<String, Object> plan = new LinkedHashMap<>();
		plan.put("entryIdeal", entryIdeal);
		plan.put("entryMax", entryMax);
		plan.put("entryNote",
				entryIdeal < price ? String.format(
						"Primary: queue at MA5 (%.4f). See queuePricePlanner for ATR-tiered alternatives.", entryIdeal)
						: String.format("Enter at current price (%.4f) — already at MA5 level.", entryIdeal));
		plan.put("stopLoss", stopLoss);
		plan.put("stopLossNote", atr != null
				? String.format("Tighter of: ATR-based (price − 1.5×ATR) vs MA-buffer (%.4f%%)", tf.slBuffer() * 100)
				: String.format("MA-buffer: %.4f%% below max(MA20=%.4f, MA50=%.4f)", tf.slBuffer() * 100, ma20, ma50));
		plan.put("riskPerUnit", risk_R);
		plan.put("holdDuration", hold.label());
		plan.put("targetDay", hold.targetDays());
		plan.put("exitRule", hold.exitRule());
		plan.put("tp1", tp1);
		plan.put("tp1Note", String.format("Sell 40%% at %.4f  (R:R 1:1.0) — move SL to breakeven", tp1));
		plan.put("tp2", tp2);
		plan.put("tp2Note", String.format("Sell 40%% at %.4f  (R:R 1:2.0)", tp2));
		plan.put("tp3", tp3);
		plan.put("tp3Note", String.format("Sell 20%% at %.4f  (R:R 1:3.5) — trail or hold", tp3));
		plan.put("queueNote", "⬇ See queuePricePlanner below for 3-level ATR-based queue alternatives to MA5.");
		return plan;
	}

	// ══════════════════════════════════════════════════════════════════
	// TREND DISTANCE (unchanged — legacy signal score)
	// ══════════════════════════════════════════════════════════════════
	public Map<String, Object> trendDistance(TrendDistanceRequest req) {
		double price = req.getMarketPrice();
		double ma20 = req.getMa20();
		double ma50 = req.getMa50();
		TimeframeConfig tf = TimeframeConfig.of(req.getTimeframe());
		List<String> warnings = new ArrayList<>(), confluences = new ArrayList<>();
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
		SignalScore signalScore = resolveSignalScoreLegacy(priceZone, trendGrade, rsiZone, volumeZone,
				MarketRegime.BULL);
		HoldCalculation hold = resolveHoldDays(priceVsMa20Pct, ma20VsMa50Pct, req.getRsi14(), req.getVolumeRatio(),
				null, price, tf);
		Map<String, Object> resp = new LinkedHashMap<>();
		if (req.getTicker() != null)
			resp.put("ticker", req.getTicker().toUpperCase());
		resp.put("timeframe", tf.label());
		resp.put("decision", decision.label);
		resp.put("emoji", decision.emoji);
		resp.put("grade", trendGrade.grade);
		resp.put("signalScore", signalScore.score() + " / " + signalScore.maxScore());
		resp.put("signalQuality", signalScore.grade());
		resp.put("holdDays", buildHoldDaysMap(hold));
		resp.put("summary", buildSummary(priceVsMa20Pct, ma20VsMa50Pct, priceZone, trendGrade, tf, hold));
		resp.put("formula1", buildFormula1(price, ma20, priceVsMa20Pct, priceZone));
		resp.put("formula2", buildFormula2(ma20, ma50, ma20VsMa50Pct, trendGrade));
		resp.put("entryGuidance",
				buildEntryGuidance(decision, trendGrade, priceZone, rsiZone, volumeZone, ma20, ma50, tf, hold));
		resp.put("warnings", warnings);
		resp.put("confluences", confluences);
		return resp;
	}

	// ══════════════════════════════════════════════════════════════════
	// SHARED RESOLVERS
	// ══════════════════════════════════════════════════════════════════
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
			warnings.add(String.format("🔴 RSI %.1f extremely overbought (>80)", rsi));
			return RsiZone.EXTREME_OB;
		}
		if (rsi > 70) {
			warnings.add(String.format("🟡 RSI %.1f overbought (70–80) — reduce 50%%", rsi));
			return RsiZone.OVERBOUGHT;
		}
		if (rsi >= 50 && rsi <= 65) {
			confluences.add(String.format("✅ RSI %.1f ideal (50–65)", rsi));
			return RsiZone.IDEAL;
		}
		if (rsi > 65) {
			confluences.add(String.format("🟢 RSI %.1f strong (65–70)", rsi));
			return RsiZone.STRONG;
		}
		if (rsi >= 40) {
			warnings.add(String.format("🟡 RSI %.1f recovering (40–50)", rsi));
			return RsiZone.RECOVERING;
		}
		warnings.add(String.format("🔴 RSI %.1f weak momentum (<40)", rsi));
		return RsiZone.WEAK;
	}

	private VolumeZone resolveVolumeZone(double ratio, List<String> confluences, List<String> warnings) {
		if (ratio >= 2.0) {
			confluences.add(String.format("🚀 STEP 6 PASS — Volume %.2f× surge", ratio));
			return VolumeZone.SURGE;
		}
		if (ratio >= 1.5) {
			confluences.add(String.format("✅ STEP 6 PASS — Volume %.2f× strong", ratio));
			return VolumeZone.STRONG;
		}
		if (ratio >= 1.2) {
			confluences.add(String.format("🟢 STEP 6 PASS — Volume %.2f× above avg", ratio));
			return VolumeZone.ABOVE_AVG;
		}
		if (ratio >= 0.8) {
			warnings.add(String.format("⚪ STEP 6 NEUTRAL — Volume %.2f× normal", ratio));
			return VolumeZone.NORMAL;
		}
		warnings.add(String.format("🔴 STEP 6 FAIL — Volume %.2f× weak (<0.8×) — no buyers", ratio));
		return VolumeZone.WEAK;
	}

	private SignalScore resolveSignalScoreLegacy(PriceZone priceZone, TrendGrade trendGrade, RsiZone rsiZone,
			VolumeZone volumeZone, MarketRegime regime) {
		int score = 1;
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

	public HoldCalculation resolveHoldDays(double priceVsMa20Pct, double ma20VsMa50Pct, Double rsi14,
			Double volumeRatio, Double atr14, Double marketPrice, TimeframeConfig tf) {
		int score = 0;
		List<String> reasons = new ArrayList<>();
		if (priceVsMa20Pct <= 1.0) {
			score += 3;
			reasons.add("F1 ideal (0–1%) → max upside room");
		} else if (priceVsMa20Pct <= 2.0) {
			score += 2;
			reasons.add("F1 ideal (1–2%) → strong upside room");
		} else if (priceVsMa20Pct <= 5.0) {
			score += 1;
			reasons.add("F1 acceptable (2–5%) → moderate room");
		} else {
			score += 0;
			reasons.add("F1 stretched (>5%) → limited upside");
		}
		if (ma20VsMa50Pct >= 1.0 && ma20VsMa50Pct <= 3.0) {
			score += 3;
			reasons.add("F2 S++ → peak momentum");
		} else if (ma20VsMa50Pct <= 5.0) {
			score += 2;
			reasons.add("F2 Grade A → good momentum");
		} else if (ma20VsMa50Pct <= 8.0) {
			score += 1;
			reasons.add("F2 Grade B → fading");
		} else {
			score += 0;
			reasons.add("F2 Grade C+ → slow grind");
		}
		if (rsi14 != null) {
			if (rsi14 >= 50 && rsi14 <= 65) {
				score += 2;
				reasons.add(String.format("RSI %.1f ideal", rsi14));
			} else if (rsi14 > 65 && rsi14 <= 70) {
				score += 1;
				reasons.add(String.format("RSI %.1f strong", rsi14));
			} else if (rsi14 >= 45 && rsi14 < 50) {
				score += 1;
				reasons.add(String.format("RSI %.1f recovering", rsi14));
			} else {
				score += 0;
				reasons.add(String.format("RSI %.1f outside ideal", rsi14));
			}
		} else {
			score += 1;
			reasons.add("RSI not provided — neutral (+1)");
		}
		if (volumeRatio != null) {
			if (volumeRatio >= 1.5) {
				score += 2;
				reasons.add(String.format("Vol %.2f× strong", volumeRatio));
			} else if (volumeRatio >= 1.2) {
				score += 1;
				reasons.add(String.format("Vol %.2f× above avg", volumeRatio));
			} else {
				score += 0;
				reasons.add(String.format("Vol %.2f× average/weak", volumeRatio));
			}
		} else {
			score += 1;
			reasons.add("Volume not provided — neutral (+1)");
		}
		HoldRange range = scoreToHoldRange(score);
		if (atr14 != null && marketPrice != null && marketPrice > 0) {
			double atrPct = (atr14 / marketPrice) * 100;
			if (atrPct >= 3.0) {
				range = range.shiftFaster(1);
				reasons.add(String.format("ATR %.2f%% high → TP faster", atrPct));
			} else if (atrPct >= 1.5) {
				reasons.add(String.format("ATR %.2f%% normal", atrPct));
			} else {
				range = range.shiftSlower(1);
				reasons.add(String.format("ATR %.2f%% low → 1 extra day", atrPct));
			}
		}
		range = applyTimeframeMultiplier(range, tf);
		return new HoldCalculation(range.min(), range.max(), range.target(), String.join(" · ", reasons),
				buildExitRule(range, ma20VsMa50Pct));
	}

	private HoldRange scoreToHoldRange(int score) {
		if (score >= 9)
			return new HoldRange(1, 2, 1);
		if (score >= 7)
			return new HoldRange(1, 3, 2);
		if (score >= 5)
			return new HoldRange(2, 4, 3);
		if (score >= 3)
			return new HoldRange(3, 6, 4);
		return new HoldRange(5, 10, 7);
	}

	private HoldRange applyTimeframeMultiplier(HoldRange range, TimeframeConfig tf) {
		return switch (tf.label()) {
		case "15M", "30M" -> new HoldRange(1, 1, 1);
		case "1H" -> new HoldRange(1, 2, 1);
		case "4H" -> new HoldRange(1, 3, 2);
		case "WEEKLY" -> new HoldRange(range.min() * 5, range.max() * 7, range.target() * 5);
		default -> range;
		};
	}

	private String buildExitRule(HoldRange range, double ma20VsMa50Pct) {
		return "Exit if price closes below MA20 · "
				+ String.format("If TP1 not reached by day %d → exit, thesis failed · ", range.max())
				+ (ma20VsMa50Pct <= 3.0 ? "S++: no move by day 2 → cut 50% and re-evaluate"
						: "Grade A/B: give full " + range.max() + " days before cutting");
	}

	// ══════════════════════════════════════════════════════════════════
	// SMALLER BUILD HELPERS
	// ══════════════════════════════════════════════════════════════════
	private Map<String, Object> buildInput(MaCalculationRequest req, TimeframeConfig tf) {
		Map<String, Object> input = new LinkedHashMap<>();
		input.put("marketPrice", req.getMarketPrice());
		input.put("ma5", req.getMa5());
		input.put("ma20", req.getMa20());
		input.put("ma50", req.getMa50());
		input.put("timeframe", tf.label());
		input.put("holdDuration", tf.holdDuration());
		if (req.getRsi14() != null)
			input.put("rsi14", req.getRsi14());
		if (req.getAtr14() != null)
			input.put("atr14", req.getAtr14());
		if (req.getVolumeRatio() != null)
			input.put("volumeRatio", req.getVolumeRatio() + "×");
		if (req.getKdjK() != null)
			input.put("kdj", String.format("K=%.3f  D=%.3f  J=%.3f", req.getKdjK(), req.getKdjD(), req.getKdjJ()));
		if (req.getMacdDif() != null)
			input.put("macd", String.format("DIF=%.4f  DEA=%.4f  Hist=%.4f", req.getMacdDif(), req.getMacdDea(),
					req.getMacdHistogram()));
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
			VolumeZone volumeZone, KdjZone kdjZone, MacdMomentumZone macdZone, TimeframeConfig tf, double ma5,
			HoldCalculation hold) {
		String posSize = resolvePositionSize(priceZone, trendGrade, rsiZone, volumeZone, kdjZone, macdZone);
		return switch (level) {
		case HIGH_SKIP -> String.format("[%s] SKIP — %s. All 6 filters must align before entering.", tf.label(),
				buildSkipReason(priceZone, trendGrade, rsiZone, volumeZone, kdjZone, macdZone));
		case MEDIUM_PROCEED -> String.format(
				"[%s] PROCEED with caution — %s zone, Grade %s, KDJ: %s, MACD: %s, Vol: %s. "
						+ "Reduce to %s. See queuePricePlanner for best entry price. Hold: %s (target day %d).",
				tf.label(), priceZone.label, trendGrade.grade, kdjZone.label, macdZone.label, volumeZone.label, posSize,
				hold.label(), hold.targetDays());
		case LOW_PROCEED -> String.format(
				"[%s] PROCEED — All 6 filters green. %s ✅  Grade %s ✅  KDJ: %s ✅  MACD: %s ✅  Vol: %s ✅. "
						+ "Position: %s. See queuePricePlanner for tiered queue levels. Hold: %s (target day %d).",
				tf.label(), priceZone.label, trendGrade.grade, kdjZone.label, macdZone.label, volumeZone.label, posSize,
				hold.label(), hold.targetDays());
		};
	}

	private String buildSkipReason(PriceZone priceZone, TrendGrade trendGrade, RsiZone rsiZone, VolumeZone volumeZone,
			KdjZone kdjZone, MacdMomentumZone macdZone) {
		if (trendGrade == TrendGrade.EXHAUSTED)
			return "trend exhausted >10%";
		if (trendGrade == TrendGrade.BEARISH)
			return "MA20 below MA50 — downtrend";
		if (priceZone == PriceZone.OVEREXTENDED)
			return "price >10% above MA20 — don't chase";
		if (rsiZone == RsiZone.EXTREME_OB)
			return "RSI extremely overbought (>80)";
		if (rsiZone == RsiZone.WEAK)
			return "RSI weak momentum (<40)";
		if (volumeZone == VolumeZone.WEAK)
			return "volume ratio <0.8× — no buyers";
		if (kdjZone == KdjZone.BEARISH)
			return "KDJ bearish — K<D downward pressure";
		if (macdZone == MacdMomentumZone.STRONG_BEAR)
			return "MACD strong bear — DIF<DEA<0 confirmed downtrend";
		return "multiple filters failed — wait for full alignment";
	}

	private Map<String, Object> buildHoldDaysMap(HoldCalculation hold) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("label", hold.label());
		m.put("minDays", hold.minDays());
		m.put("maxDays", hold.maxDays());
		m.put("targetDays", hold.targetDays());
		m.put("basis", hold.basis());
		m.put("exitRule", hold.exitRule());
		return m;
	}

	private Map<String, Object> buildCalculation(double price, double ma20, double pctAboveMA20, double pctAboveMA5,
			double ma5AboveMA20, double pctAboveMA50) {
		return Map.of("formula", "(marketPrice - MA) / MA × 100", "pctAboveMA20", String.format("%.2f%%", pctAboveMA20),
				"pctAboveMA5", String.format("%.2f%%", pctAboveMA5), "pctAboveMA50",
				String.format("%.2f%%", pctAboveMA50), "ma5AboveMA20", String.format("%.2f%%", ma5AboveMA20), "result",
				String.format("(%.4f - %.4f) / %.4f × 100 = %.2f%%", price, ma20, ma20, pctAboveMA20), "thresholds",
				"≤2% Ideal | 2–5% Acceptable | 5–10% Stretched | >10% Overextended");
	}

	private Map<String, Object> buildMaAlignment(double price, double ma5, double ma20, double ma50) {
		boolean p5 = price > ma5, p20 = price > ma20, p50 = price > ma50, f5 = ma5 > ma20, m50 = ma20 > ma50;
		String trend = (p5 && p20 && p50 && f5 && m50) ? "✅ Strong Bull — Price > MA5 > MA20 > MA50"
				: (p20 && p50) ? "✅ Bullish — Price > MA20 > MA50"
						: p20 ? "⚠️ Moderate — Price > MA20 but below MA50" : "⚠️ Weak — Price below MA20";
		return Map.of("priceAboveMA5", p5, "priceAboveMA20", p20, "priceAboveMA50", p50, "ma5AboveMA20", f5,
				"ma20AboveMA50", m50, "trend", trend);
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

	private String buildSummary(double f1, double f2, PriceZone zone, TrendGrade grade, TimeframeConfig tf,
			HoldCalculation hold) {
		return String.format("[%s] Price %.2f%% %s MA20 (%s). Trend: %.2f%% → Grade %s — %s. Hold: %s (target day %d).",
				tf.label(), Math.abs(f1), f1 >= 0 ? "above" : "below", zone.label, f2, grade.grade, grade.meaning,
				hold.label(), hold.targetDays());
	}

	private Map<String, Object> buildEntryGuidance(EntryDecision decision, TrendGrade grade, PriceZone zone,
			RsiZone rsiZone, VolumeZone volumeZone, double ma20, double ma50, TimeframeConfig tf,
			HoldCalculation hold) {
		Map<String, Object> g = new LinkedHashMap<>();
		g.put("action", decision.label);
		g.put("positionSize", decision.positionSize);
		g.put("note", decision.note);
		g.put("rsiStatus", rsiZone.label);
		g.put("volumeStatus", volumeZone.label);
		g.put("holdDays", hold.label());
		g.put("targetDay", hold.targetDays());
		g.put("exitRule", hold.exitRule());
		g.put("waitFor", switch (zone) {
		case BELOW_MA20, OVEREXTENDED, STRETCHED -> String.format("Pullback to MA20 (%.4f) or MA50 (%.4f)", ma20, ma50);
		default -> "Current price is acceptable entry zone";
		});
		g.put("holdDuration", tf.holdDuration());
		g.put("gradeScale", "S++ (1–3%) → A (3–5%) → B (5–8%) → C (8–10%) → EXHAUSTED (>10%)");
		g.put("filterSummary", String.format("Step1:MA✅ | Step2:%s | Step3:%s | RSI:%s | Vol:%s", zone.emoji,
				grade.emoji, rsiZone.emoji, volumeZone.emoji));
		return g;
	}

	// ══════════════════════════════════════════════════════════════════
	// ZONE / GRADE RESOLVERS
	// ══════════════════════════════════════════════════════════════════
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

}