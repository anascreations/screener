package com.screener.service.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MaCalculationRequest {
	@NotNull
	private String market;
	private String timeframe = "DAILY";
	@NotNull
	private Double marketPrice;
	@NotNull
	private Double ma5;
	@NotNull
	private Double ma20;
	@NotNull
	private Double ma50;
	// ── RSI14 — optional add-on, enhances hold-day prediction ─────────
	private Double rsi14;
	// ── ATR14 — optional, dynamic stop-loss calculation ───────────────
	private Double atr14;
	// ── Volume ratio vs average — optional but strongly recommended ───
	private Double volumeRatio;
	// ── KDJ Oscillator ────────────────────────────────────────────────
	/** KDJ K line (fast stochastic) */
	private Double kdjK;
	/** KDJ D line (smoothed K) */
	private Double kdjD;
	/** KDJ J line = 3K − 2D; oscillates wider, leads reversals */
	private Double kdjJ;
	// ── MACD (DIF / DEA / Histogram) ─────────────────────────────────
	/** DIF = EMA12 − EMA26 (fast MACD line) */
	private Double macdDif;
	/** DEA = 9-day EMA of DIF (signal line) */
	private Double macdDea;
	/**
	 * MACD Histogram = DIF − DEA (enter the raw bar value from your chart). Some
	 * platforms display 2×(DIF−DEA) — halve it before entering.
	 */
	private Double macdHistogram;
	// ── Bollinger Bands (BB% and Width only — mid band = MA20) ────────
	/**
	 * BB% (percent bandwidth position) = (Price − Lower) / (Upper − Lower). Range
	 * 0–1: 0 = at lower band, 1 = at upper band. Above 1 = exceeded upper. Enter as
	 * decimal: e.g. 0.65 for 65%.
	 */
	private Double bbPct;
	/**
	 * BB Width % = ((Upper − Lower) / Middle) × 100. Measures band
	 * expansion/compression. Typical range 2–15%. Enter as percentage: e.g. 4.2 for
	 * 4.2%.
	 */
	private Double bbWidth;
}