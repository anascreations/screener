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
	// ── Price Context (optional — OHLC & Market Profile) ────────────
	/** Today's opening price — used for gap analysis */
	private Double openPrice;
	/** Previous session close — % change confirmation */
	private Double prevClose;
	/** Today's intraday high */
	private Double dayHigh;
	/** Today's intraday low */
	private Double dayLow;
	/** 52-week high — distance from peak */
	private Double wk52High;
	/** 52-week low — distance from base */
	private Double wk52Low;
	/**
	 * Beta vs market index. > 1.5 = high volatility, reduce position size > 2.0 =
	 * very high, extra caution
	 */
	private Double beta;
	/**
	 * Bid/Ask ratio percentage from Moomoo. >= 60% = strong demand / buyers in
	 * control < 40% = sellers dominating
	 */
	private Double bidAskRatio;

	// ── Helpers ─────────────────────────────────────────────────────
	public boolean hasOhlc() {
		return openPrice != null && dayHigh != null && dayLow != null;
	}

	public boolean hasPrevClose() {
		return prevClose != null;
	}

	public boolean has52Week() {
		return wk52High != null && wk52Low != null;
	}

	public boolean hasBeta() {
		return beta != null;
	}

	public boolean hasBidAsk() {
		return bidAskRatio != null;
	}

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