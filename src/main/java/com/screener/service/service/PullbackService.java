package com.screener.service.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.screener.service.dto.PullbackRequest;
import com.screener.service.dto.PullbackResult;

@Service
public class PullbackService {
	// ── Thresholds ─────────────────────────────────────────────────────────
	private static final double PULLBACK_MIN_PCT = 1.0; // min drop to call it pullback
	private static final double PULLBACK_MAX_PCT = 8.0; // max drop before it's reversal
	private static final double MA5_TOUCH_BUFFER = 0.3; // % tolerance to call MA5 "touched"
	private static final double CONSOLIDATION_BAND = 0.5; // % range = consolidating

	public PullbackResult detect(PullbackRequest req) {
		List<Double> prices = req.priceHistory();
		if (prices == null || prices.size() < 5) {
			return buildInsufficientData();
		}
		double current = req.currentPrice();
		double ma5 = req.ma5();
		double ma20 = req.ma20();
		// ── 1. Find recent swing high (peak within last N candles) ──────────
		int lookback = Math.min(prices.size(), 20);
		double recentHigh = findRecentHigh(prices, lookback);
		double recentLow = findRecentLow(prices, lookback);
		// ── 2. Measure pullback depth from swing high ───────────────────────
		double pullbackDepth = recentHigh > 0 ? (recentHigh - current) / recentHigh * 100 : 0;
		// ── 3. Measure price momentum (last 3 candles direction) ────────────
		Momentum momentum = calcMomentum(prices);
		// ── 4. Detect if price is near MA5 (pullback touch zone) ────────────
		double distToMA5Pct = Math.abs(current - ma5) / ma5 * 100;
		boolean touchingMA5 = distToMA5Pct <= MA5_TOUCH_BUFFER;
		// ── 5. Detect if price is near MA20 (deep pullback zone) ────────────
		double distToMA20Pct = Math.abs(current - ma20) / ma20 * 100;
		boolean touchingMA20 = distToMA20Pct <= MA5_TOUCH_BUFFER;
		// ── 6. Price structure: higher highs + higher lows = uptrend ────────
		boolean higherHighs = isHigherHighs(prices);
		boolean higherLows = isHigherLows(prices);
		// ── 7. Volatility (standard deviation of last N closes) ─────────────
		double volatility = calcVolatility(prices, lookback);
		// ── 8. Classify phase ────────────────────────────────────────────────
		Phase phase = classifyPhase(current, recentHigh, recentLow, pullbackDepth, momentum, ma5, ma20, higherHighs,
				higherLows, volatility);
		// ── 9. Determine entry signal ────────────────────────────────────────
		boolean entryNow = determineEntry(phase, touchingMA5, touchingMA20, pullbackDepth, ma5, ma20, current);
		double pullbackTarget = calcPullbackTarget(ma5, ma20, pullbackDepth);
		// ── 10. Build result ─────────────────────────────────────────────────
		return buildResult(phase, pullbackDepth, pullbackTarget, entryNow, current, ma5, ma20, recentHigh, recentLow,
				momentum, touchingMA5, touchingMA20, higherHighs, higherLows, volatility, distToMA5Pct, distToMA20Pct);
	}

	// ── Phase classifier ───────────────────────────────────────────────────
	private Phase classifyPhase(double current, double recentHigh, double recentLow, double pullbackDepth,
			Momentum momentum, double ma5, double ma20, boolean higherHighs, boolean higherLows, double volatility) {
		// REVERSAL: price broke below MA20 or deep drop > 8%
		if (current < ma20 || pullbackDepth > PULLBACK_MAX_PCT) {
			return Phase.REVERSAL;
		}
		// PULLBACK: price dropped from recent high 1–8%, momentum turning down,
		// but still above MA20
		if (pullbackDepth >= PULLBACK_MIN_PCT && pullbackDepth <= PULLBACK_MAX_PCT && current >= ma20
				&& momentum != Momentum.STRONGLY_UP) {
			return Phase.PULLBACK;
		}
		// CONSOLIDATING: price moving sideways (low volatility, small range)
		double rangeWidth = recentHigh > 0 && recentLow > 0 ? (recentHigh - recentLow) / recentHigh * 100 : 0;
		if (volatility < 0.5 && rangeWidth < CONSOLIDATION_BAND * 3) {
			return Phase.CONSOLIDATING;
		}
		// ADVANCING: price making new highs, momentum up, above both MAs
		if (momentum == Momentum.STRONGLY_UP && higherHighs && current > ma5 && current > ma20) {
			return Phase.ADVANCING;
		}
		return Phase.CONSOLIDATING;
	}

	// ── Entry decision ─────────────────────────────────────────────────────
	private boolean determineEntry(Phase phase, boolean touchingMA5, boolean touchingMA20, double pullbackDepth,
			double ma5, double ma20, double current) {
		return switch (phase) {
		// Best entry: pullback touching MA5 (ideal) or MA20 (deep)
		case PULLBACK -> touchingMA5 || touchingMA20;
		// Enter only if price just reclaimed MA5 from consolidation
		case CONSOLIDATING -> current > ma5 && current < ma5 * 1.005;
		// Advancing: late entry, only if < 2% above MA5
		case ADVANCING -> (current - ma5) / ma5 * 100 < 2.0;
		// Reversal: never enter
		case REVERSAL -> false;
		};
	}

	// ── Pullback target price ──────────────────────────────────────────────
	private double calcPullbackTarget(double ma5, double ma20, double pullbackDepth) {
		// If pullback < 3% → target MA5 retest
		// If pullback >= 3% → target halfway between MA5 and MA20
		if (pullbackDepth < 3.0)
			return round4(ma5);
		return round4((ma5 + ma20) / 2.0);
	}

	// ── Build final result ─────────────────────────────────────────────────
	private PullbackResult buildResult(Phase phase, double pullbackDepth, double pullbackTarget, boolean entryNow,
			double current, double ma5, double ma20, double recentHigh, double recentLow, Momentum momentum,
			boolean touchingMA5, boolean touchingMA20, boolean higherHighs, boolean higherLows, double volatility,
			double distToMA5Pct, double distToMA20Pct) {
		String emoji, signal, advice;
		switch (phase) {
		case PULLBACK -> {
			emoji = entryNow ? "🟢" : "🟡";
			signal = entryNow ? "PULLBACK TOUCHING MA5 — ENTER NOW" : "PULLBACK IN PROGRESS — WAIT FOR MA5 TOUCH";
			advice = entryNow ? String.format(
					"Price pulling back and touching MA5 (%.4f). " + "Ideal entry zone. Enter with full size.", ma5)
					: String.format("Pullback detected (%.2f%% from high). "
							+ "Wait for price to reach MA5 (%.4f) before entering. "
							+ "Do NOT chase current price (%.4f).", pullbackDepth, ma5, current);
		}
		case ADVANCING -> {
			emoji = "🔵";
			signal = "ADVANCING — EXTENDED, WAIT FOR PULLBACK";
			advice = String.format("Price advancing strongly. No pullback yet. "
					+ "Ideal entry on next pullback to MA5 (%.4f). " + "%.2f%% above MA5 — entering now is chasing.",
					ma5, distToMA5Pct);
		}
		case CONSOLIDATING -> {
			emoji = "⚪";
			signal = entryNow ? "BREAKOUT FROM CONSOLIDATION — ENTER" : "CONSOLIDATING — WAIT FOR BREAKOUT";
			advice = entryNow ? String.format("Price breaking out of consolidation above MA5 (%.4f). Enter now.", ma5)
					: String.format("Price consolidating between %.4f – %.4f. " + "Wait for breakout above MA5 (%.4f).",
							recentLow, recentHigh, ma5);
		}
		case REVERSAL -> {
			emoji = "🔴";
			signal = "REVERSAL — DO NOT ENTER";
			advice = String.format(
					"Price dropped %.2f%% from high%s. "
							+ "Trend structure broken. Wait for price to reclaim MA20 (%.4f) and MA5 (%.4f).",
					pullbackDepth, current < ma20 ? " and broke below MA20" : "", ma20, ma5);
		}
		default -> {
			emoji = "⚪";
			signal = "UNCLEAR";
			advice = "Insufficient data to determine phase.";
		}
		}
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("recentHigh", round4(recentHigh));
		detail.put("recentLow", round4(recentLow));
		detail.put("pullbackDepthPct", round2(pullbackDepth) + "%");
		detail.put("distanceToMA5", round2(distToMA5Pct) + "%");
		detail.put("distanceToMA20", round2(distToMA20Pct) + "%");
		detail.put("touchingMA5", touchingMA5);
		detail.put("touchingMA20", touchingMA20);
		detail.put("momentum", momentum.label());
		detail.put("higherHighs", higherHighs);
		detail.put("higherLows", higherLows);
		detail.put("volatility", round4(volatility));
		detail.put("thresholds", Map.of("pullbackMin", PULLBACK_MIN_PCT + "%", "pullbackMax", PULLBACK_MAX_PCT + "%",
				"ma5TouchBuffer", MA5_TOUCH_BUFFER + "%", "consolidationBand", CONSOLIDATION_BAND + "%"));
		return new PullbackResult(phase == Phase.PULLBACK, phase.label(), signal, emoji, round2(pullbackDepth),
				pullbackTarget, entryNow, advice, detail);
	}

	// ── Helpers ────────────────────────────────────────────────────────────
	private double findRecentHigh(List<Double> prices, int lookback) {
		return prices.subList(Math.max(0, prices.size() - lookback), prices.size()).stream()
				.mapToDouble(Double::doubleValue).max().orElse(0);
	}

	private double findRecentLow(List<Double> prices, int lookback) {
		return prices.subList(Math.max(0, prices.size() - lookback), prices.size()).stream()
				.mapToDouble(Double::doubleValue).min().orElse(0);
	}

	private Momentum calcMomentum(List<Double> prices) {
		if (prices.size() < 3)
			return Momentum.NEUTRAL;
		int size = prices.size();
		double c1 = prices.get(size - 1); // latest
		double c2 = prices.get(size - 2);
		double c3 = prices.get(size - 3);
		boolean last2up = c1 > c2;
		boolean last3up = c2 > c3;
		boolean last2down = c1 < c2;
		boolean last3down = c2 < c3;
		if (last2up && last3up)
			return Momentum.STRONGLY_UP;
		if (last2up && !last3up)
			return Momentum.TURNING_UP;
		if (last2down && last3down)
			return Momentum.STRONGLY_DOWN;
		if (last2down && !last3down)
			return Momentum.TURNING_DOWN;
		return Momentum.NEUTRAL;
	}

	// Higher highs: each 5-candle peak is higher than the previous
	private boolean isHigherHighs(List<Double> prices) {
		if (prices.size() < 10)
			return false;
		int size = prices.size();
		double high1 = Collections.max(prices.subList(size - 5, size));
		double high2 = Collections.max(prices.subList(size - 10, size - 5));
		return high1 > high2;
	}

	// Higher lows: each 5-candle trough is higher than the previous
	private boolean isHigherLows(List<Double> prices) {
		if (prices.size() < 10)
			return false;
		int size = prices.size();
		double low1 = Collections.min(prices.subList(size - 5, size));
		double low2 = Collections.min(prices.subList(size - 10, size - 5));
		return low1 > low2;
	}

	private double calcVolatility(List<Double> prices, int lookback) {
		List<Double> window = prices.subList(Math.max(0, prices.size() - lookback), prices.size());
		double mean = window.stream().mapToDouble(Double::doubleValue).average().orElse(0);
		double variance = window.stream().mapToDouble(p -> (p - mean) * (p - mean)).average().orElse(0);
		return Math.sqrt(variance);
	}

	private PullbackResult buildInsufficientData() {
		return new PullbackResult(false, "UNKNOWN", "INSUFFICIENT DATA", "⚫", 0, 0, false,
				"Need at least 5 price history points to detect pullback.",
				Map.of("required", "minimum 5 closes", "recommended", "20 closes"));
	}

	private double round4(double v) {
		return Math.round(v * 10000.0) / 10000.0;
	}

	private double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	// ── Enums ──────────────────────────────────────────────────────────────
	enum Phase {
		ADVANCING("Advancing"), PULLBACK("Pullback"), CONSOLIDATING("Consolidating"), REVERSAL("Reversal");

		private final String label;

		Phase(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	enum Momentum {
		STRONGLY_UP("⬆️⬆️ Strongly Up"), TURNING_UP("↗️ Turning Up"), NEUTRAL("➡️ Neutral"),
		TURNING_DOWN("↘️ Turning Down"), STRONGLY_DOWN("⬇️⬇️ Strongly Down");

		private final String label;

		Momentum(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}
}