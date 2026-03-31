package com.screener.service.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class EmaCalculationRequest {

	/** MY = Bursa Malaysia, US = NYSE/NASDAQ */
	@NotNull
	private String market;

	/** 15M | 30M | 1H | 4H | DAILY | WEEKLY */
	@NotNull
	private String timeframe;

	/** Current market price */
	@NotNull
	@Positive
	private Double marketPrice;

	// ── Core EMA Stack ──────────────────────────────────────────────
	/** EMA 8 — fast momentum EMA */
	@NotNull
	@Positive
	private Double ema8;

	/** EMA 21 — short-term trend EMA */
	@NotNull
	@Positive
	private Double ema21;

	/** EMA 55 — medium-term trend EMA */
	@NotNull
	@Positive
	private Double ema55;

	// ── KDJ Oscillator (optional — Step 4) ─────────────────────────
	private Double kdjK;
	private Double kdjD;
	private Double kdjJ;

	// ── MACD (optional — Step 5) ────────────────────────────────────
	private Double macdDif;
	private Double macdDea;
	private Double macdHistogram;

	// ── Volume + extras (optional) ──────────────────────────────────
	/** Volume ratio vs 20-day average. >= 1.5 is surge, >= 2.0 is strong surge */
	private Double volumeRatio;

	/** RSI 14 — add-on confirmation only, not a gate */
	private Double rsi14;

	/**
	 * ATR 14 — used for stop-loss precision. Falls back to 1.5% of price if absent
	 */
	private Double atr14;

	// ── Helpers ─────────────────────────────────────────────────────
	public boolean hasKdj() {
		return kdjK != null && kdjD != null && kdjJ != null;
	}

	public boolean hasMacd() {
		return macdDif != null && macdDea != null;
	}

	public boolean hasVolume() {
		return volumeRatio != null;
	}

	public boolean hasRsi() {
		return rsi14 != null;
	}

	public boolean hasAtr() {
		return atr14 != null;
	}
}