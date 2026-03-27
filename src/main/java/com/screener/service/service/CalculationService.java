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
import com.screener.service.enums.EntryDecision;
import com.screener.service.enums.KdjZone;
import com.screener.service.enums.MarketRegime;
import com.screener.service.enums.PriceZone;
import com.screener.service.enums.RiskLevel;
import com.screener.service.enums.RsiZone;
import com.screener.service.enums.TrendGrade;
import com.screener.service.enums.VolumeZone;
import com.screener.service.model.Level2Entry;

@Service
public class CalculationService {
	// ══════════════════════════════════════════════════════════════════
	// LEVEL 2 ORDER BOOK
	// ══════════════════════════════════════════════════════════════════
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

	// ══════════════════════════════════════════════════════════════════
	// MA CALCULATOR — 6-Filter Stack (MA200 replaced by KDJ + MACD)
	// ══════════════════════════════════════════════════════════════════
	public Map<String, Object> maCalculate(MaCalculationRequest req) {
		double price = req.getMarketPrice();
		double ma5 = req.getMa5();
		double ma20 = req.getMa20();
		double ma50 = req.getMa50();
		TimeframeConfig tf = TimeframeConfig.of(req.getTimeframe());
		List<String> warnings = new ArrayList<>();
		List<String> confluences = new ArrayList<>();
		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("input", buildInput(req, tf));
		// ── STEP 1: MA alignment hard-stops ───────────────────────────
		if (price < ma20)
			return buildSkip(resp,
					String.format("STEP 1 FAIL — Price (%.4f) below MA20 (%.4f). Support broken.", price, ma20),
					Map.of("priceVsMA20", String.format("%.4f below MA20 (%.4f)", ma20 - price, ma20), "note",
							"Price below MA20 = downtrend. Wait for reclaim."));
		if (ma5 < ma20)
			return buildSkip(resp,
					String.format("STEP 1 FAIL — MA5 (%.4f) below MA20 (%.4f). No golden cross yet.", ma5, ma20),
					Map.of("ma5AboveMA20", false, "gap",
							String.format("MA5 is %.4f (%.2f%%) below MA20", ma20 - ma5, (ma20 - ma5) / ma20 * 100),
							"note", "Wait for MA5 ≥ MA20 golden cross."));
		if (ma20 < ma50)
			return buildSkip(resp,
					String.format("STEP 1 FAIL — MA20 (%.4f) below MA50 (%.4f). Medium trend bearish.", ma20, ma50),
					Map.of("ma20AboveMA50", false, "gap",
							String.format("MA20 is %.4f (%.2f%%) below MA50", ma50 - ma20, (ma50 - ma20) / ma50 * 100),
							"note", "MA5 > MA20 > MA50 stack must be fully aligned."));
		confluences.add("✅ STEP 1 PASS — MA5 > MA20 > MA50 fully aligned");
		// ── Core distance calculations ─────────────────────────────────
		double pctAboveMA20 = (price - ma20) / ma20 * 100;
		double pctAboveMA5 = (price - ma5) / ma5 * 100;
		double ma5AboveMA20 = (ma5 - ma20) / ma20 * 100;
		double pctAboveMA50 = (price - ma50) / ma50 * 100;
		double ma20VsMa50Pct = (ma20 - ma50) / ma50 * 100;
		PriceZone priceZone = resolvePriceZone(pctAboveMA20);
		TrendGrade trendGrade = resolveTrendGrade(ma20VsMa50Pct);
		resolveStep2(priceZone, pctAboveMA20, ma20, confluences, warnings);
		resolveStep3(trendGrade, ma20VsMa50Pct, confluences, warnings);
		// ── STEP 4: KDJ (replaces RSI as gating filter; RSI now optional add-on) ──
		KdjZone kdjZone = resolveKdjZone(req.getKdjK(), req.getKdjD(), req.getKdjJ(), confluences, warnings);
		checkKdjDivergence(price, ma5, req.getKdjJ(), warnings);
		// ── STEP 6: Volume conviction ──────────────────────────────────
		VolumeZone volumeZone = VolumeZone.UNKNOWN;
		if (req.getVolumeRatio() != null)
			volumeZone = resolveVolumeZone(req.getVolumeRatio(), confluences, warnings);
		else
			warnings.add("⚠️ STEP 6 — Volume ratio not provided, conviction unconfirmed");
		// ── RSI14 optional add-on (informational, not a gating filter) ──
		RsiZone rsiZone = RsiZone.UNKNOWN;
		if (req.getRsi14() != null)
			rsiZone = resolveRsiZone(req.getRsi14(), confluences, warnings);
		// ── Scoring & risk ─────────────────────────────────────────────
		SignalScore signalScore = resolveSignalScore(priceZone, trendGrade, rsiZone, volumeZone, kdjZone);
		RiskLevel riskLevel = resolveRiskLevel(priceZone, trendGrade, rsiZone, volumeZone, kdjZone);
		HoldCalculation hold = resolveHoldDays(pctAboveMA20, ma20VsMa50Pct, req.getRsi14(), req.getVolumeRatio(),
				req.getAtr14(), req.getMarketPrice(), tf);
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
		resp.put("holdDays", buildHoldDaysMap(hold));
		resp.put("advice", buildAdvice(riskLevel, priceZone, trendGrade, rsiZone, volumeZone, kdjZone, tf, pctAboveMA20,
				ma5, ma20, ma50, hold));
		resp.put("warnings", warnings);
		resp.put("confluences", confluences);
		resp.put("filterSteps", buildFilterSteps(priceZone, trendGrade, rsiZone, volumeZone, kdjZone, req, pctAboveMA20,
				ma20VsMa50Pct));
		resp.put("calculation",
				buildCalculation(price, ma20, ma50, pctAboveMA20, pctAboveMA5, ma5AboveMA20, pctAboveMA50));
		resp.put("maAlignment", buildMaAlignment(price, ma5, ma20, ma50));
		if ("SKIP".equals(riskLevel.decision))
			return resp;
		// ── Trade plan ─────────────────────────────────────────────────
		double entryIdeal = pctAboveMA5 <= 0.5 ? round4(price) : round4(ma5);
		double entryMax = round4(price);
		double stopLoss = resolveStopLoss(price, ma20, ma50, tf, req.getAtr14(), warnings);
		double risk_R = round4(entryIdeal - stopLoss);
		double tp1 = round4(entryIdeal + risk_R * 1.0);
		double tp2 = round4(entryIdeal + risk_R * 2.0);
		double tp3 = round4(entryIdeal + risk_R * 3.5);
		resp.put("positionSize", resolvePositionSize(priceZone, trendGrade, rsiZone, volumeZone, kdjZone));
		resp.put("tradePlan", buildTradePlan(price, ma20, ma50, tf, entryIdeal, entryMax, stopLoss, risk_R, tp1, tp2,
				tp3, req.getAtr14(), hold));
		return resp;
	}

	// ══════════════════════════════════════════════════════════════════
	// TREND DISTANCE — unchanged, still uses MarketRegime internally
	// ══════════════════════════════════════════════════════════════════
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
		SignalScore signalScore = resolveSignalScoreLegacy(priceZone, trendGrade, rsiZone, volumeZone,
				MarketRegime.BULL);
		HoldCalculation hold = resolveHoldDays(priceVsMa20Pct, ma20VsMa50Pct, req.getRsi14(), req.getVolumeRatio(),
				null, req.getMarketPrice(), tf);
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
	// KDJ — STEP 4 (new)
	// ══════════════════════════════════════════════════════════════════
	private KdjZone resolveKdjZone(Double k, Double d, Double j, List<String> confluences, List<String> warnings) {
		boolean accelerating = (k - d) > 2.0; // gap widening = conviction
		if (j >= 50) {
			if (accelerating)
				confluences.add(String.format(
						"✅ STEP 4 PASS — KDJ Bullish Strong ACCELERATING: K=%.3f > D=%.3f gap=%.3f, J=%.3f", k, d,
						k - d, j));
			else
				warnings.add(String.format(
						"⚠️ STEP 4 WARN — KDJ Bullish but NARROWING: K=%.3f > D=%.3f gap=%.3f, J=%.3f — cross may flip",
						k, d, k - d, j));
			return accelerating ? KdjZone.BULLISH_STRONG : KdjZone.BULLISH_WEAK;
		}
		if (k == null || d == null || j == null) {
			warnings.add("⚠️ STEP 4 — KDJ not provided. Oscillator momentum unconfirmed.");
			return KdjZone.UNKNOWN;
		}
		if (j < 20) {
			confluences.add(String.format(
					"💡 STEP 4 PASS — KDJ Oversold: K=%.3f D=%.3f J=%.3f (J<20). Bounce zone — confirm K>D cross.", k,
					d, j));
			return KdjZone.OVERSOLD;
		}
		if (j > 100) {
			warnings.add(
					String.format("🔴 STEP 4 FAIL — KDJ J=%.3f extreme extension (>100). Hard reversal imminent.", j));
			return KdjZone.OVERBOUGHT; // treat same as overbought but log differently
		}
		if (j > 80) {
			warnings.add(String.format("🟡 STEP 4 WARN — KDJ Overbought: K=%.3f D=%.3f J=%.3f (J>80). Reduce size.", k,
					d, j));
			return KdjZone.OVERBOUGHT;
		}
		double diff = k - d;
		if (k > d) {
			if (j >= 50) {
				confluences.add(String.format(
						"✅ STEP 4 PASS — KDJ Bullish Strong: K=%.1f > D=%.1f, J=%.1f — full upward alignment.", k, d,
						j));
				return KdjZone.BULLISH_STRONG;
			} else {
				confluences.add(String.format(
						"🟢 STEP 4 PASS — KDJ Bullish Weak: K=%.1f > D=%.1f, J=%.1f — golden cross, momentum building.",
						k, d, j));
				return KdjZone.BULLISH_WEAK;
			}
		}
		if (Math.abs(diff) < 2.0) {
			warnings.add(String.format(
					"⚪ STEP 4 NEUTRAL — KDJ K=%.1f ≈ D=%.1f, J=%.1f — near cross, watch for direction.", k, d, j));
			return KdjZone.NEUTRAL;
		}
		warnings.add(String.format(
				"🔴 STEP 4 FAIL — KDJ Bearish: K=%.1f < D=%.1f, J=%.1f — downward pressure, avoid entry.", k, d, j));
		return KdjZone.BEARISH;
	}

	// Add to resolveKdjZone signature or as a separate check called in maCalculate:
	private void checkKdjDivergence(double price, double ma5, double kdjJ, List<String> warnings) {
		// Proxy: if price > ma5 by >3% but J < 50, momentum not confirming the price
		// move
		double pctAboveMA5 = (price - ma5) / ma5 * 100;
		if (pctAboveMA5 > 3.0 && kdjJ < 50) {
			warnings.add(String.format("⚠️ DIVERGENCE WARNING — Price %.2f%% above MA5 but KDJ J=%.3f <50. "
					+ "Price leading momentum — high reversal risk. Reduce size 50%%.", pctAboveMA5, kdjJ));
		}
	}

	// ══════════════════════════════════════════════════════════════════
	// SIGNAL SCORE — new version with KDJ + MACD (max 12)
	// ══════════════════════════════════════════════════════════════════
	private SignalScore resolveSignalScore(PriceZone priceZone, TrendGrade trendGrade, RsiZone rsiZone,
			VolumeZone volumeZone, KdjZone kdjZone) {
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
		// RSI is now optional add-on (+1/-1 only)
		score += switch (rsiZone) {
		case IDEAL, STRONG -> 1;
		case RECOVERING -> 0;
		case OVERBOUGHT -> -1;
		case EXTREME_OB, WEAK -> -1;
		case UNKNOWN -> 0;
		};
		score += switch (volumeZone) {
		case SURGE, STRONG -> 2;
		case ABOVE_AVG -> 1;
		case NORMAL -> 0;
		case WEAK -> -1;
		case UNKNOWN -> 0;
		};
		// KDJ momentum — primary oscillator (+2 to -2)
		score += switch (kdjZone) {
		case BULLISH_STRONG -> 2;
		case BULLISH_WEAK, OVERSOLD -> 1;
		case NEUTRAL -> 0;
		case OVERBOUGHT -> -1;
		case BEARISH -> -2;
		case UNKNOWN -> 0;
		};
		int maxScore = 10;
		int clamped = Math.max(0, score);
		String grade = clamped >= 9 ? "S++" : clamped >= 7 ? "A" : clamped >= 5 ? "B" : clamped >= 3 ? "C" : "WEAK";
		return new SignalScore(clamped, maxScore, grade);
	}

	/**
	 * Legacy version — kept for trendDistance which does not have KDJ/MACD inputs
	 */
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

	// ══════════════════════════════════════════════════════════════════
	// RISK LEVEL — updated to use KDJ + MACD instead of MA200 regime
	// ══════════════════════════════════════════════════════════════════
	private RiskLevel resolveRiskLevel(PriceZone priceZone, TrendGrade trendGrade, RsiZone rsiZone,
			VolumeZone volumeZone, KdjZone kdjZone) {
		// Hard skips
		if (trendGrade == TrendGrade.EXHAUSTED || trendGrade == TrendGrade.BEARISH)
			return RiskLevel.HIGH_SKIP;
		if (priceZone == PriceZone.OVEREXTENDED)
			return RiskLevel.HIGH_SKIP;
		if (rsiZone == RsiZone.EXTREME_OB || rsiZone == RsiZone.WEAK)
			return RiskLevel.HIGH_SKIP;
		if (volumeZone == VolumeZone.WEAK)
			return RiskLevel.HIGH_SKIP;
		if (kdjZone == KdjZone.OVERBOUGHT && priceZone == PriceZone.STRETCHED)
			return RiskLevel.HIGH_SKIP; // double overbought = hard no
		if (volumeZone == VolumeZone.UNKNOWN)
			return RiskLevel.MEDIUM_PROCEED;
		// Medium risk (any caution factor)
		if (priceZone == PriceZone.STRETCHED || trendGrade == TrendGrade.C || trendGrade == TrendGrade.FLAT
				|| rsiZone == RsiZone.OVERBOUGHT || rsiZone == RsiZone.RECOVERING || volumeZone == VolumeZone.NORMAL
				|| kdjZone == KdjZone.OVERBOUGHT || kdjZone == KdjZone.NEUTRAL)
			return RiskLevel.MEDIUM_PROCEED;
		return RiskLevel.LOW_PROCEED;
	}

	// ══════════════════════════════════════════════════════════════════
	// POSITION SIZE — updated with KDJ + MACD momentum
	// ══════════════════════════════════════════════════════════════════
	private String resolvePositionSize(PriceZone priceZone, TrendGrade trendGrade, RsiZone rsiZone,
			VolumeZone volumeZone, KdjZone kdjZone) {
		boolean momentumStrong = (kdjZone == KdjZone.BULLISH_STRONG);
		if (priceZone == PriceZone.IDEAL && (trendGrade == TrendGrade.S_PLUS_PLUS || trendGrade == TrendGrade.A)
				&& (rsiZone == RsiZone.IDEAL || rsiZone == RsiZone.STRONG || rsiZone == RsiZone.UNKNOWN
						|| rsiZone == RsiZone.RECOVERING) // add RECOVERING
				&& (volumeZone == VolumeZone.SURGE || volumeZone == VolumeZone.STRONG
						|| volumeZone == VolumeZone.ABOVE_AVG)
				&& momentumStrong)
			return "100%";
		if (kdjZone == KdjZone.OVERBOUGHT)
			return "0% — SKIP";
		if (priceZone == PriceZone.STRETCHED || rsiZone == RsiZone.OVERBOUGHT || kdjZone == KdjZone.NEUTRAL)
			return "50%";
		if (trendGrade == TrendGrade.C)
			return "40%";
		if (trendGrade == TrendGrade.B)
			return "60%";
		if (!momentumStrong)
			return "70%";
		if (volumeZone == VolumeZone.NORMAL)
			return "70%";
		if (priceZone == PriceZone.ACCEPTABLE)
			return "80%";
		return "50%";
	}

	// ══════════════════════════════════════════════════════════════════
	// HOLD DAYS — unchanged (also used by trendDistance)
	// ══════════════════════════════════════════════════════════════════
	public HoldCalculation resolveHoldDays(double priceVsMa20Pct, double ma20VsMa50Pct, Double rsi14,
			Double volumeRatio, Double atr14, Double marketPrice, TimeframeConfig tf) {
		int score = 0;
		List<String> reasons = new ArrayList<>();
		if (priceVsMa20Pct >= 0 && priceVsMa20Pct <= 1.0) {
			score += 3;
			reasons.add("F1 ideal (0–1%) → maximum upside room");
		} else if (priceVsMa20Pct <= 2.0) {
			score += 2;
			reasons.add("F1 ideal (1–2%) → strong upside room");
		} else if (priceVsMa20Pct <= 5.0) {
			score += 1;
			reasons.add("F1 acceptable (2–5%) → moderate room");
		} else {
			score += 0;
			reasons.add("F1 stretched (>5%) → limited upside, slow move expected");
		}
		if (ma20VsMa50Pct >= 1.0 && ma20VsMa50Pct <= 2.0) {
			score += 3;
			reasons.add("F2 S++ early (1–2%) → peak momentum, fastest move");
		} else if (ma20VsMa50Pct <= 3.0) {
			score += 3;
			reasons.add("F2 S++ (2–3%) → high momentum");
		} else if (ma20VsMa50Pct <= 5.0) {
			score += 2;
			reasons.add("F2 Grade A (3–5%) → good momentum");
		} else if (ma20VsMa50Pct <= 8.0) {
			score += 1;
			reasons.add("F2 Grade B (5–8%) → momentum fading");
		} else {
			score += 0;
			reasons.add("F2 Grade C/Exhausted (>8%) → very late, slow grind expected");
		}
		if (rsi14 != null) {
			if (rsi14 >= 50 && rsi14 <= 60) {
				score += 2;
				reasons.add(String.format("RSI %.1f ideal (50–60) → room to accelerate", rsi14));
			} else if (rsi14 > 60 && rsi14 <= 65) {
				score += 2;
				reasons.add(String.format("RSI %.1f strong (60–65) → momentum confirmed", rsi14));
			} else if (rsi14 > 65 && rsi14 <= 70) {
				score += 1;
				reasons.add(String.format("RSI %.1f high (65–70) → nearing overbought, may slow", rsi14));
			} else if (rsi14 >= 45 && rsi14 < 50) {
				score += 1;
				reasons.add(String.format("RSI %.1f recovering (45–50) → needs 1 extra day", rsi14));
			} else if (rsi14 > 70) {
				score += 0;
				reasons.add(String.format("RSI %.1f overbought (>70) → likely consolidate first", rsi14));
			} else {
				score += 0;
				reasons.add(String.format("RSI %.1f weak (<45) → slow momentum", rsi14));
			}
		} else {
			score += 1;
			reasons.add("RSI not provided — neutral assumption (+1)");
		}
		if (volumeRatio != null) {
			if (volumeRatio >= 2.0) {
				score += 2;
				reasons.add(String.format("Volume %.2f× surge → institutional, fast move", volumeRatio));
			} else if (volumeRatio >= 1.5) {
				score += 2;
				reasons.add(String.format("Volume %.2f× strong → clear accumulation", volumeRatio));
			} else if (volumeRatio >= 1.2) {
				score += 1;
				reasons.add(String.format("Volume %.2f× above avg → buyers present", volumeRatio));
			} else if (volumeRatio >= 0.8) {
				score += 0;
				reasons.add(String.format("Volume %.2f× average → no urgency, may drift", volumeRatio));
			} else {
				score += 0;
				reasons.add(String.format("Volume %.2f× weak → low conviction, slow", volumeRatio));
			}
		} else {
			score += 1;
			reasons.add("Volume not provided — neutral assumption (+1)");
		}
		HoldRange range = scoreToHoldRange(score);
		if (atr14 != null && marketPrice != null && marketPrice > 0) {
			double atrPct = (atr14 / marketPrice) * 100;
			if (atrPct >= 3.0) {
				range = range.shiftFaster(1);
				reasons.add(String.format("ATR %.2f%% → high volatility, TP hit faster", atrPct));
			} else if (atrPct >= 1.5) {
				reasons.add(String.format("ATR %.2f%% → normal volatility", atrPct));
			} else {
				range = range.shiftSlower(1);
				reasons.add(String.format("ATR %.2f%% → low volatility, allow 1 extra day", atrPct));
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
		case "15M" -> new HoldRange(1, 1, 1);
		case "30M" -> new HoldRange(1, 1, 1);
		case "1H" -> new HoldRange(1, 2, 1);
		case "4H" -> new HoldRange(1, 3, 2);
		case "WEEKLY" -> new HoldRange(range.min() * 5, range.max() * 7, range.target() * 5);
		default -> range;
		};
	}

	private String buildExitRule(HoldRange range, double ma20VsMa50Pct) {
		String hardStop = "Exit immediately if price closes below MA20";
		String timeStop = String.format("If TP1 not reached by day %d — exit regardless, thesis failed", range.max());
		String gradeNote = ma20VsMa50Pct <= 3.0 ? "S++ setup: if no move by day 2, reduce 50% and re-evaluate"
				: "Grade A/B: give full " + range.max() + " days before cutting";
		return hardStop + " · " + timeStop + " · " + gradeNote;
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

	// ══════════════════════════════════════════════════════════════════
	// EXISTING STEP RESOLVERS (steps 2, 3, RSI, Volume — unchanged)
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
			warnings.add(String.format("🔴 RSI %.1f extremely overbought (>80) — skip", rsi));
			return RsiZone.EXTREME_OB;
		}
		if (rsi > 70) {
			warnings.add(String.format("🟡 RSI %.1f overbought (70–80) — reduce size 50%%", rsi));
			return RsiZone.OVERBOUGHT;
		}
		if (rsi >= 50 && rsi <= 65) {
			confluences.add(String.format("✅ RSI %.1f ideal momentum zone (50–65)", rsi));
			return RsiZone.IDEAL;
		}
		if (rsi > 65) {
			confluences.add(String.format("🟢 RSI %.1f strong momentum (65–70), still valid", rsi));
			return RsiZone.STRONG;
		}
		if (rsi >= 40) {
			warnings.add(String.format("🟡 RSI %.1f recovering (40–50) — wait for cross above 50", rsi));
			return RsiZone.RECOVERING;
		}
		warnings.add(String.format("🔴 RSI %.1f weak momentum (<40) — MA alignment may be false breakout", rsi));
		return RsiZone.WEAK;
	}

	private VolumeZone resolveVolumeZone(double ratio, List<String> confluences, List<String> warnings) {
		if (ratio >= 2.0) {
			confluences.add(String.format("🚀 STEP 6 PASS — Volume %.2f× surge — institutional interest", ratio));
			return VolumeZone.SURGE;
		}
		if (ratio >= 1.5) {
			confluences.add(String.format("✅ STEP 6 PASS — Volume %.2f× strong accumulation", ratio));
			return VolumeZone.STRONG;
		}
		if (ratio >= 1.2) {
			confluences.add(String.format("🟢 STEP 6 PASS — Volume %.2f× above average, buyers present", ratio));
			return VolumeZone.ABOVE_AVG;
		}
		if (ratio >= 0.8) {
			warnings.add(String.format("⚪ STEP 6 NEUTRAL — Volume %.2f× normal, no strong conviction", ratio));
			return VolumeZone.NORMAL;
		}
		warnings.add(String.format("🔴 STEP 6 FAIL — Volume %.2f× weak (<0.8×) — no buyers, avoid", ratio));
		return VolumeZone.WEAK;
	}

	// ══════════════════════════════════════════════════════════════════
	// FILTER STEPS — 6-step audit (MA200 / regime removed)
	// ══════════════════════════════════════════════════════════════════
	private Map<String, Object> buildFilterSteps(PriceZone priceZone, TrendGrade trendGrade, RsiZone rsiZone,
			VolumeZone volumeZone, KdjZone kdjZone, MaCalculationRequest req, double pctAboveMA20,
			double ma20VsMa50Pct) {
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
		steps.put("step4", req.getKdjK() != null ? Map.of("name", "KDJ Momentum", "rule", "K>D, J<80",
//                        "result", String.format("K=%.1f D=%.1f J=%.1f", req.getKdjK(), req.getKdjD(), req.getKdjJ()),
				"result", String.format("K=%.3f D=%.3f J=%.3f", req.getKdjK(), req.getKdjD(), req.getKdjJ()), "zone",
				kdjZone.label, "status",
				kdjZone == KdjZone.BULLISH_STRONG || kdjZone == KdjZone.BULLISH_WEAK || kdjZone == KdjZone.OVERSOLD
						? "PASS ✅"
						: kdjZone == KdjZone.NEUTRAL || kdjZone == KdjZone.OVERBOUGHT ? "WARN ⚠️" : "FAIL 🔴")
				: Map.of("name", "KDJ Momentum", "status", "SKIP ⚪", "note", "Not provided"));
		steps.put("step5",
				req.getVolumeRatio() != null
						? Map.of("name", "Volume Conviction", "rule", "ratio > 1.2×", "result",
								String.format("%.2f×", req.getVolumeRatio()), "zone", volumeZone.label, "status",
								volumeZone == VolumeZone.SURGE || volumeZone == VolumeZone.STRONG
										|| volumeZone == VolumeZone.ABOVE_AVG ? "PASS ✅"
												: volumeZone == VolumeZone.NORMAL ? "NEUTRAL ⚪" : "FAIL 🔴")
						: Map.of("name", "Volume Conviction", "status", "SKIP ⚪", "note", "Not provided"));
		return steps;
	}

	// ══════════════════════════════════════════════════════════════════
	// BUILD HELPERS
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
		;
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
			VolumeZone volumeZone, KdjZone kdjZone, TimeframeConfig tf, double pctAboveMA20, double ma5, double ma20,
			double ma50, HoldCalculation hold) {
		String posSize = resolvePositionSize(priceZone, trendGrade, rsiZone, volumeZone, kdjZone);
		return switch (level) {
		case HIGH_SKIP -> String.format("[%s] SKIP — %s. Wait for KDJ K>D to align before entering.", tf.label(),
				buildSkipReason(priceZone, trendGrade, rsiZone, volumeZone, kdjZone));
		case MEDIUM_PROCEED -> String.format(
				"[%s] PROCEED with caution — %s zone, Grade %s, KDJ: %s, Vol: %s. "
						+ "Reduce size to %s. Ideal entry: MA5 (%.4f). Hold: %s (target day %d).",
				tf.label(), priceZone.label, trendGrade.grade, kdjZone.label, volumeZone.label, posSize, ma5,
				hold.label(), hold.targetDays());
		case LOW_PROCEED -> String.format(
				"[%s] PROCEED — All filters green. %s zone ✅  Grade %s ✅  KDJ: %s ✅  Vol: %s ✅. "
						+ "Position size: %s. Hold: %s (target day %d).",
				tf.label(), priceZone.label, trendGrade.grade, kdjZone.label, volumeZone.label, posSize, hold.label(),
				hold.targetDays());
		};
	}

	private String buildSkipReason(PriceZone priceZone, TrendGrade trendGrade, RsiZone rsiZone, VolumeZone volumeZone,
			KdjZone kdjZone) {
		if (trendGrade == TrendGrade.EXHAUSTED)
			return "trend exhausted >10% — old trend";
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
			return "KDJ bearish — K<D, downward pressure";
		return "multiple filters failed — wait for full alignment";
	}

	/** Removes MA200 from calculation display (no longer required) */
	private Map<String, Object> buildCalculation(double price, double ma20, double ma50, double pctAboveMA20,
			double pctAboveMA5, double ma5AboveMA20, double pctAboveMA50) {
		return Map.of("formula", "(marketPrice - MA) / MA × 100", "pctAboveMA20", String.format("%.2f%%", pctAboveMA20),
				"pctAboveMA5", String.format("%.2f%%", pctAboveMA5), "pctAboveMA50",
				String.format("%.2f%%", pctAboveMA50), "ma5AboveMA20", String.format("%.2f%%", ma5AboveMA20), "result",
				String.format("(%.4f - %.4f) / %.4f × 100 = %.2f%%", price, ma20, ma20, pctAboveMA20), "thresholds",
				"≤2% Ideal | 2–5% Acceptable | 5–10% Stretched | >10% Overextended");
	}

	/** MA200 removed from alignment map */
	private Map<String, Object> buildMaAlignment(double price, double ma5, double ma20, double ma50) {
		boolean p5 = price > ma5;
		boolean p20 = price > ma20;
		boolean p50 = price > ma50;
		boolean f5 = ma5 > ma20;
		boolean m50 = ma20 > ma50;
		String trend = (p5 && p20 && p50 && f5 && m50) ? "✅ Strong Bull — Price > MA5 > MA20 > MA50"
				: (p20 && p50) ? "✅ Bullish — Price > MA20 > MA50"
						: p20 ? "⚠️ Moderate — Price > MA20 but below MA50" : "⚠️ Weak — Price below MA20";
		return Map.of("priceAboveMA5", p5, "priceAboveMA20", p20, "priceAboveMA50", p50, "ma5AboveMA20", f5,
				"ma20AboveMA50", m50, "trend", trend);
	}

	private double resolveStopLoss(double price, double ma20, double ma50, TimeframeConfig tf, Double atr,
			List<String> warnings) {
		double maSl = round4(Math.max(ma20, ma50) * (1 - tf.slBuffer()));
		if (atr != null && atr > 0) {
			double atrSl = round4(price - 1.5 * atr);
			// Use the TIGHTER of the two — professional risk management
			double sl = Math.max(atrSl, maSl); // changed from Math.min to Math.max
			warnings.add(String.format("✅ ATR14 SL: %.4f - (1.5×%.4f) = %.4f vs MA-SL %.4f → using tighter: %.4f",
					price, atr, atrSl, maSl, sl));
			return sl;
		}
		warnings.add("⚠️ ATR14 not provided — using MA-buffer SL only. Add ATR14 for tighter dynamic SL.");
		return maSl;
	}

	private Map<String, Object> buildTradePlan(double price, double ma20, double ma50, TimeframeConfig tf,
			double entryIdeal, double entryMax, double stopLoss, double risk_R, double tp1, double tp2, double tp3,
			Double atr, HoldCalculation hold) {
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
		plan.put("holdDuration", hold.label());
		plan.put("targetDay", hold.targetDays());
		plan.put("exitRule", hold.exitRule());
		plan.put("tp1", tp1);
		plan.put("tp1Note", String.format("Sell 40%% at %.4f  (R:R 1:1.0) — move SL to breakeven", tp1));
		plan.put("tp2", tp2);
		plan.put("tp2Note", String.format("Sell 40%% at %.4f  (R:R 1:2.0)", tp2));
		plan.put("tp3", tp3);
		plan.put("tp3Note", String.format("Sell 20%% at %.4f  (R:R 1:3.5) — trail or hold", tp3));
		return plan;
	}

	// ── trendDistance helpers (unchanged) ─────────────────────────────
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
		return String.format(
				"[%s] Price %.2f%% %s MA20 (%s). Trend maturity: %.2f%% → Grade %s — %s. Expected hold: %s (target day %d).",
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
		g.put("filterSummary", String.format("Step1:MA✅ | Step2:%s | Step3:%s | Step4:%s | Step5:%s", zone.emoji,
				grade.emoji, rsiZone.emoji, volumeZone.emoji));
		return g;
	}

	// ══════════════════════════════════════════════════════════════════
	// ZONE / GRADE RESOLVERS (unchanged)
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

	// ══════════════════════════════════════════════════════════════════
	// UTIL / RECORDS
	// ══════════════════════════════════════════════════════════════════
	private double round4(double v) {
		return Math.round(v * 10000.0) / 10000.0;
	}

	private double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	private double round0(double v) {
		return Math.round(v);
	}

	private record HoldRange(int min, int max, int target) {
		HoldRange shiftFaster(int n) {
			return new HoldRange(Math.max(1, min - n), Math.max(1, max - n), Math.max(1, target - n));
		}

		HoldRange shiftSlower(int n) {
			return new HoldRange(min + n, max + n, target + n);
		}
	}

	public record HoldCalculation(int minDays, int maxDays, int targetDays, String basis, String exitRule) {
		public String label() {
			return minDays == maxDays ? minDays + " day" + (minDays > 1 ? "s" : "") : minDays + "–" + maxDays + " days";
		}
	}

	private record SignalScore(int score, int maxScore, String grade) {
	}

	public record TimeframeConfig(String label, double lowThreshold, double highThreshold, double slBuffer,
			String holdDuration) {
		public static TimeframeConfig of(String raw) {
			return switch (raw == null ? "DAILY"
					: raw.toUpperCase().trim().replace("-", "").replace("_", "").replace(" ", "")) {
			case "15M", "15MIN", "15MINUTE", "15MINUTES" ->
				new TimeframeConfig("15M", 1.0, 3.0, 0.0010, "15–60 minutes");
			case "30M", "30MIN", "30MINUTE", "30MINUTES" ->
				new TimeframeConfig("30M", 1.5, 3.5, 0.0010, "30 min – 2 hours");
			case "1H", "60M", "1HOUR", "HOURLY" -> new TimeframeConfig("1H", 1.5, 4.0, 0.0015, "1–4 hours");
			case "2H", "2HOUR" -> new TimeframeConfig("2H", 2.0, 5.0, 0.0020, "2–8 hours");
			case "4H", "4HOUR" -> new TimeframeConfig("4H", 2.0, 5.0, 0.0030, "4–24 hours");
			case "WEEKLY", "1W", "W", "WEEK" -> new TimeframeConfig("WEEKLY", 5.0, 12.0, 0.0050, "2–6 weeks");
			default -> new TimeframeConfig("DAILY", 3.0, 7.0, 0.0010, "2–5 days");
			};
		}
	}
}