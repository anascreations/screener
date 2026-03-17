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
		double stopLoss = round4(nearestBidWall * 0.995);
		double riskR = entryPrice > 0 ? round4(entryPrice - stopLoss) : 0;
		Map<String, Object> rec = new LinkedHashMap<>();
		rec.put("action", actionEmoji + " " + action);
		rec.put("score", score + " / 7  (≥3 Buy · ≤-3 Skip · else Wait)");
		rec.put("strategy", strategy);
		rec.put("entryPrice", entryPrice > 0 ? entryPrice : "N/A");
		rec.put("stopLoss", stopLoss > 0 ? stopLoss : "N/A");
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

		// ── Echo inputs ───────────────────────────────────────────────────────
		resp.put("input", buildInput(req, tf));

		// ── Gate 1: Price vs MA20 ─────────────────────────────────────────────
		if (price < ma20) {
			return buildSkip(resp, "Price below MA20 — support broken. Wait for reclaim.",
					Map.of("priceVsMA20", String.format("%.4f below MA20 (%.4f)", ma20 - price, ma20), "note",
							"Price below MA20 = downtrend. Wait for price to reclaim MA20."));
		}

		// ── Gate 2: MA5 vs MA20 (golden cross) ───────────────────────────────
		if (ma5 < ma20) {
			return buildSkip(resp, String.format("MA5 (%.4f) below MA20 (%.4f) — no golden cross yet.", ma5, ma20),
					Map.of("ma5AboveMA20", false, "gap",
							String.format("MA5 is %.4f (%.2f%%) below MA20", ma20 - ma5, (ma20 - ma5) / ma20 * 100),
							"note", "Wait for MA5 ≥ MA20 golden cross."));
		}

		// ── Gate 3: MA20 vs MA50 (medium trend) ──────────────────────────────
		if (ma20 < ma50) {
			warnings.add(String.format("MA20 (%.4f) below MA50 (%.4f) — medium trend bearish. Reduce size or skip.",
					ma20, ma50));
		} else {
			confluences.add(String.format("MA20 (%.4f) > MA50 (%.4f) — medium trend bullish", ma20, ma50));
		}

		// ── Market Regime (MA200) ─────────────────────────────────────────────
		MarketRegime regime = resolveRegime(price, ma200, warnings, confluences);

		// ── Trend Strength ────────────────────────────────────────────────────
		TrendStrength strength = resolveTrendStrength(ma5, ma20, ma50, confluences);

		// ── Signal Quality ────────────────────────────────────────────────────
		String signalQuality = resolveSignalQuality(regime, strength, warnings);

		// ── Risk level (existing frontend keys preserved) ─────────────────────
		double pctAboveMA20 = (price - ma20) / ma20 * 100;
		double pctAboveMA5 = (price - ma5) / ma5 * 100;
		double ma5AboveMA20 = (ma5 - ma20) / ma20 * 100;
		double pctAboveMA50 = (price - ma50) / ma50 * 100;

		RiskLevel riskLevel = resolveRiskLevel(pctAboveMA20, regime);

		resp.put("decision", riskLevel.decision);
		resp.put("emoji", riskLevel.emoji);
		resp.put("risk", riskLevel.label);
		resp.put("signalQuality", signalQuality);
		resp.put("marketRegime", regime.name());
		resp.put("trendStrength", strength.name());
		resp.put("advice", buildAdvice(riskLevel, tf, pctAboveMA20, ma5, ma20, ma50, ma200));
		resp.put("warnings", warnings);
		resp.put("confluences", confluences);

		// ── Calculation breakdown (existing frontend keys preserved) ──────────
		resp.put("calculation",
				buildCalculation(price, ma20, ma50, ma200, pctAboveMA20, pctAboveMA5, ma5AboveMA20, pctAboveMA50));

		// ── MA alignment ──────────────────────────────────────────────────────
		resp.put("maAlignment", buildMaAlignment(price, ma5, ma20, ma50, ma200));

		if ("SKIP".equals(riskLevel.decision)) {
			return resp;
		}

		// ── Optional enrichment ───────────────────────────────────────────────
		if (req.getVolume() != null && req.getAvgVolume20() != null) {
			checkVolume(req.getVolume(), req.getAvgVolume20(), confluences, warnings);
		}
		if (req.getRsi14() != null) {
			checkRsi(req.getRsi14(), confluences, warnings);
		}

		// ── Entry / SL / TP ───────────────────────────────────────────────────
		double entryIdeal = pctAboveMA5 <= 0.5 ? round4(price) : round4(ma5);
		double entryMax = round4(price);
		double stopLoss = resolveStopLoss(price, ma20, ma50, tf, req.getAtr14(), warnings);
		double risk_R = round4(entryIdeal - stopLoss);
		double tp1 = round4(entryIdeal + risk_R * 1.0);
		double tp2 = round4(entryIdeal + risk_R * 2.0);
		double tp3 = round4(entryIdeal + risk_R * 3.5);

		resp.put("tradePlan",
				buildTradePlan(price, ma20, ma50, tf, entryIdeal, entryMax, stopLoss, risk_R, tp1, tp2, tp3));

		return resp;
	}

	public Map<String, Object> trendDistance(TrendDistanceRequest req) {
		double price = req.getMarketPrice();
		double ma20 = req.getMa20();
		double ma50 = req.getMa50();
		TimeframeConfig tf = TimeframeConfig.of(req.getTimeframe());

		// ── Formula 1: Price vs MA20 ──────────────────────────────────────────────
		// ((marketPrice - MA20) / MA20) * 100
		double priceVsMa20Pct = ((price - ma20) / ma20) * 100;

		// ── Formula 2: MA20 vs MA50 ───────────────────────────────────────────────
		// ((MA20 - MA50) / MA50) * 100
		double ma20VsMa50Pct = ((ma20 - ma50) / ma50) * 100;

		PriceZone priceZone = resolvePriceZone(priceVsMa20Pct);
		TrendGrade trendGrade = resolveTrendGrade(ma20VsMa50Pct);
		EntryDecision decision = resolveDecision(priceZone, trendGrade);

		Map<String, Object> resp = new LinkedHashMap<>();
		if (req.getTicker() != null)
			resp.put("ticker", req.getTicker().toUpperCase());
		resp.put("timeframe", tf.label());
		resp.put("decision", decision.label);
		resp.put("emoji", decision.emoji);
		resp.put("grade", trendGrade.grade);
		resp.put("summary", buildSummary(priceVsMa20Pct, ma20VsMa50Pct, priceZone, trendGrade, tf));

		resp.put("formula1", buildFormula1(price, ma20, priceVsMa20Pct, priceZone));
		resp.put("formula2", buildFormula2(ma20, ma50, ma20VsMa50Pct, trendGrade));
		resp.put("entryGuidance", buildEntryGuidance(decision, trendGrade, priceZone, ma20, ma50, tf));

		return resp;
	}

	private PriceZone resolvePriceZone(double pct) {
		if (pct < 0)
			return PriceZone.BELOW_MA20;
		if (pct <= 2.0)
			return PriceZone.IDEAL; // 0%–2% sweet spot
		if (pct <= 5.0)
			return PriceZone.ACCEPTABLE; // 2%–5% still ok
		if (pct <= 10.0)
			return PriceZone.STRETCHED; // 5%–10% reduce size
		return PriceZone.OVEREXTENDED; // >10% do not chase
	}

	private TrendGrade resolveTrendGrade(double pct) {
		if (pct < 0)
			return TrendGrade.BEARISH; // MA20 < MA50 — downtrend
		if (pct <= 1.0)
			return TrendGrade.FLAT; // <1% crossover zone
		if (pct <= 3.0)
			return TrendGrade.S_PLUS_PLUS; // 1%–3% trend just began ★
		if (pct <= 5.0)
			return TrendGrade.A; // 3%–5% healthy trend
		if (pct <= 8.0)
			return TrendGrade.B; // 5%–8% maturing trend
		if (pct <= 10.0)
			return TrendGrade.C; // 8%–10% late trend
		return TrendGrade.EXHAUSTED; // >10% do not chase
	}

	private EntryDecision resolveDecision(PriceZone zone, TrendGrade grade) {
		if (grade == TrendGrade.BEARISH)
			return EntryDecision.SKIP_BEARISH;
		if (grade == TrendGrade.EXHAUSTED)
			return EntryDecision.SKIP_EXHAUSTED;
		if (zone == PriceZone.BELOW_MA20)
			return EntryDecision.SKIP_BELOW_MA20;
		if (zone == PriceZone.OVEREXTENDED) {
			return grade == TrendGrade.S_PLUS_PLUS ? EntryDecision.WAIT_PULLBACK // fresh trend but price stretched
					: EntryDecision.SKIP_OVEREXTENDED;
		}
		if (zone == PriceZone.STRETCHED)
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

	private String buildSummary(double f1, double f2, PriceZone zone, TrendGrade grade, TimeframeConfig tf) {
		return String.format(
				"[%s] Price is %.2f%% %s MA20 (%s). Trend maturity: %.2f%% (MA20 vs MA50) → Grade %s — %s.", tf.label(),
				Math.abs(f1), f1 >= 0 ? "above" : "below", zone.label, f2, grade.grade, grade.meaning);
	}

	private Map<String, Object> buildEntryGuidance(EntryDecision decision, TrendGrade grade, PriceZone zone,
			double ma20, double ma50, TimeframeConfig tf) {
		Map<String, Object> g = new LinkedHashMap<>();
		g.put("action", decision.label);
		g.put("positionSize", decision.positionSize);
		g.put("note", decision.note);
		g.put("waitFor", switch (zone) {
		case BELOW_MA20, OVEREXTENDED, STRETCHED -> String.format("Pullback to MA20 (%.4f) or MA50 (%.4f)", ma20, ma50);
		default -> "Current price is acceptable entry zone";
		});
		g.put("holdDuration", tf.holdDuration());
		g.put("gradeScale", "S++ (1-3%) → A (3-5%) → B (5-8%) → C (8-10%) → EXHAUSTED (>10%)");
		return g;
	}

	// ─────────────────────────────────────────────────────────────────────────────
	// Enums
	// ─────────────────────────────────────────────────────────────────────────────

	@Getter
	@RequiredArgsConstructor
	enum PriceZone {
		BELOW_MA20("Below MA20", "🚫", "Support broken — downtrend"),
		IDEAL("Ideal Zone", "🎯", "0–2% above MA20 — best entry zone"),
		ACCEPTABLE("Acceptable", "🟢", "2–5% above MA20 — valid entry"),
		STRETCHED("Stretched", "🟡", "5–10% above MA20 — reduce size"),
		OVEREXTENDED("Overextended", "🔴", ">10% above MA20 — do not chase");

		private final String label;
		private final String emoji;
		private final String meaning;
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

		private final String grade;
		private final String emoji;
		private final String meaning;
	}

	@Getter
	@RequiredArgsConstructor
	enum EntryDecision {
		PROCEED_FULL("PROCEED ✅", "🚀", "100%", "Ideal zone + fresh trend — full position"),
		PROCEED_REDUCED("PROCEED (reduced) 🟡", "🟡", "50%", "Stretched price — reduce size by 50%"),
		WAIT_PULLBACK("WAIT ⏳", "⏳", "0%", "Fresh trend (S++) but price overextended — wait for pullback to MA20"),
		SKIP_BEARISH("SKIP 🔻", "🔻", "0%", "MA20 below MA50 — no long trades"),
		SKIP_EXHAUSTED("SKIP 🛑", "🛑", "0%", "Trend >10% exhausted — high reversal risk"),
		SKIP_BELOW_MA20("SKIP 🚫", "🚫", "0%", "Price below MA20 — support broken"),
		SKIP_OVEREXTENDED("SKIP 🔴", "🔴", "0%", "Price >10% above MA20 — do not chase");

		private final String label;
		private final String emoji;
		private final String positionSize;
		private final String note;
	}

	private MarketRegime resolveRegime(double price, double ma200, List<String> warnings, List<String> confluences) {
		double pct = (price - ma200) / ma200 * 100;
		if (price > ma200 * 1.02) {
			confluences.add(String.format("Price %.2f%% above MA200 — BULL market regime", pct));
			return MarketRegime.BULL;
		} else if (price < ma200 * 0.98) {
			warnings.add(String.format("Price %.2f%% below MA200 (%.4f) — BEAR regime, long trades high risk",
					Math.abs(pct), ma200));
			return MarketRegime.BEAR;
		} else {
			warnings.add("Price within 2% of MA200 — transitional / choppy zone");
			return MarketRegime.NEUTRAL;
		}
	}

	private TrendStrength resolveTrendStrength(double ma5, double ma20, double ma50, List<String> confluences) {
		boolean shortAligned = ma5 > ma20;
		boolean mediumAligned = ma20 > ma50;
		if (shortAligned && mediumAligned) {
			confluences.add("MA5 > MA20 > MA50 — full bullish stack aligned ✅");
			return TrendStrength.STRONG;
		} else if (shortAligned || mediumAligned) {
			confluences.add(shortAligned ? "MA5 > MA20 — short-term bullish, MA50 not yet confirmed"
					: "MA20 > MA50 — medium trend intact, MA5 lagging");
			return TrendStrength.MODERATE;
		}
		return TrendStrength.WEAK;
	}

	private String resolveSignalQuality(MarketRegime regime, TrendStrength strength, List<String> warnings) {
		if (regime == MarketRegime.BEAR) {
			warnings.add("Signal quality INVALID — BEAR regime detected");
			return "INVALID";
		}
		return switch (strength) {
		case STRONG -> regime == MarketRegime.BULL ? "A" : "B";
		case MODERATE -> "B";
		case WEAK -> {
			warnings.add("Weak MA alignment — C grade, reduce position size");
			yield "C";
		}
		};
	}

	private RiskLevel resolveRiskLevel(double pctAboveMA20, MarketRegime regime) {
		if (regime == MarketRegime.BEAR)
			return RiskLevel.HIGH_SKIP;
		if (pctAboveMA20 > 10.0)
			return RiskLevel.HIGH_SKIP;
		if (pctAboveMA20 >= 5.0)
			return RiskLevel.MEDIUM_PROCEED;
		return RiskLevel.LOW_PROCEED;
	}

	private double resolveStopLoss(double price, double ma20, double ma50, TimeframeConfig tf, Double atr,
			List<String> warnings) {
		if (atr != null && atr > 0) {
			double atrSl = round4(price - 1.5 * atr);
			double maSl = round4(Math.max(ma20, ma50) * (1 - tf.slBuffer()));
			return Math.min(atrSl, maSl);
		}
		warnings.add("ATR not provided — using MA-buffer SL (less precise)");
		double anchor = (Math.abs(price - ma50) < Math.abs(price - ma20)) ? ma50 : ma20;
		return round4(anchor * (1 - tf.slBuffer()));
	}

	// =========================================================================
	// Response builders (preserves all existing frontend keys)
	// =========================================================================

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
		if (req.getVolume() != null)
			input.put("volume", req.getVolume());
		if (req.getAvgVolume20() != null)
			input.put("avgVolume20", req.getAvgVolume20());
		return input;
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

	private Map<String, Object> buildSkip(Map<String, Object> resp, String reason, Map<String, Object> detail) {
		resp.put("decision", "SKIP");
		resp.put("emoji", "🚫");
		resp.put("reason", reason);
		resp.put("detail", detail);
		return resp;
	}

	private String buildAdvice(RiskLevel level, TimeframeConfig tf, double pctAboveMA20, double ma5, double ma20,
			double ma50, double ma200) {
		return switch (level) {
		case HIGH_SKIP -> String.format(
				"[%s] Price is %.2f%% above MA20 — overextended (>10%%) or BEAR regime. "
						+ "Do NOT enter. Wait for pullback to MA5 (%.4f) or MA20 (%.4f).",
				tf.label(), pctAboveMA20, ma5, ma20);
		case MEDIUM_PROCEED -> String.format(
				"[%s] Price is %.2f%% above MA20 — medium-risk zone (5%%–10%%). "
						+ "Reduce size 30–50%%. Ideal: wait for pullback to MA5 (%.4f). "
						+ "MA50=%.4f acting as trend support. Expected hold: %s.",
				tf.label(), pctAboveMA20, ma5, ma50, tf.holdDuration());
		case LOW_PROCEED -> String.format(
				"[%s] Price is %.2f%% above MA20 — low-risk zone (≤5%%). "
						+ "Price close to MA20 support (%.4f). MA50=%.4f, MA200=%.4f confirm uptrend. "
						+ "Full position allowed. Expected hold: %s.",
				tf.label(), pctAboveMA20, ma20, ma50, ma200, tf.holdDuration());
		};
	}

	private Map<String, Object> buildCalculation(double price, double ma20, double ma50, double ma200,
			double pctAboveMA20, double pctAboveMA5, double ma5AboveMA20, double pctAboveMA50) {
		return Map.of("formula", "(marketPrice - MA) / MA × 100", "pctAboveMA20", String.format("%.2f%%", pctAboveMA20),
				"pctAboveMA5", String.format("%.2f%%", pctAboveMA5), "pctAboveMA50",
				String.format("%.2f%%", pctAboveMA50), "pctAboveMA200",
				String.format("%.2f%%", (price - ma200) / ma200 * 100), "ma5AboveMA20",
				String.format("%.2f%%", ma5AboveMA20), "result",
				String.format("(%.4f - %.4f) / %.4f × 100 = %.2f%%", price, ma20, ma20, pctAboveMA20), "thresholds",
				"≤5% = Low Risk PROCEED | 5–10% = Medium Risk PROCEED | >10% = High Risk SKIP");
	}

	private Map<String, Object> buildMaAlignment(double price, double ma5, double ma20, double ma50, double ma200) {
		boolean p5 = price > ma5;
		boolean p20 = price > ma20;
		boolean p50 = price > ma50;
		boolean p200 = price > ma200;
		boolean f5 = ma5 > ma20;
		boolean m50 = ma20 > ma50;
		boolean l200 = ma50 > ma200;

		String trend;
		if (p5 && p20 && p50 && p200 && f5 && m50 && l200)
			trend = "✅ Strong Bull — Price > MA5 > MA20 > MA50 > MA200";
		else if (p20 && p50 && p200)
			trend = "✅ Bullish — Price > MA20 > MA50 > MA200";
		else if (p20 && p50)
			trend = "⚠️ Moderate — Price > MA20 > MA50 but below MA200";
		else
			trend = "⚠️ Weak / Mixed — partial MA alignment only";

		return Map.of("priceAboveMA5", p5, "priceAboveMA20", p20, "priceAboveMA50", p50, "priceAboveMA200", p200,
				"ma5AboveMA20", f5, "ma20AboveMA50", m50, "ma50AboveMA200", l200, "trend", trend);
	}

	private Map<String, Object> buildTradePlan(double price, double ma20, double ma50, TimeframeConfig tf,
			double entryIdeal, double entryMax, double stopLoss, double risk_R, double tp1, double tp2, double tp3) {
		Map<String, Object> plan = new LinkedHashMap<>();
		plan.put("entryIdeal", entryIdeal);
		plan.put("entryMax", entryMax);
		plan.put("entryNote",
				entryIdeal < price
						? String.format("Queue at MA5 (%.4f). Max entry if no pullback = %.4f.", entryIdeal, entryMax)
						: String.format("Enter at current price (%.4f) — already at MA5 level.", entryIdeal));
		plan.put("stopLoss", stopLoss);
		plan.put("stopLossNote",
				String.format("%.4f%% buffer below MA anchor (MA20=%.4f / MA50=%.4f). Break = exit immediately.",
						tf.slBuffer() * 100, ma20, ma50));
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

	private void checkVolume(double volume, double avgVolume, List<String> confluences, List<String> warnings) {
		double ratio = volume / avgVolume;
		if (ratio >= 1.5)
			confluences.add(String.format("Volume %.1fx avg — strong conviction ✅", ratio));
		else if (ratio < 0.7)
			warnings.add(String.format("Volume only %.1fx avg — weak conviction ⚠️", ratio));
	}

	private void checkRsi(double rsi, List<String> confluences, List<String> warnings) {
		if (rsi >= 70)
			warnings.add(String.format("RSI %.1f — overbought, TP targets may not reach ⚠️", rsi));
		else if (rsi <= 30)
			confluences.add(String.format("RSI %.1f — oversold bounce candidate ✅", rsi));
		else if (rsi >= 50)
			confluences.add(String.format("RSI %.1f — bullish momentum zone ✅", rsi));
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

	enum MarketRegime {
		BULL, BEAR, NEUTRAL
	}

	enum TrendStrength {
		STRONG, MODERATE, WEAK
	}

	@Getter
	@RequiredArgsConstructor
	enum RiskLevel {
		LOW_PROCEED("Low Risk", "🟢", "PROCEED"), MEDIUM_PROCEED("Medium Risk", "🟡", "PROCEED"),
		HIGH_SKIP("High Risk", "🔴", "SKIP");

		private final String label;
		private final String emoji;
		private final String decision;
	}

}