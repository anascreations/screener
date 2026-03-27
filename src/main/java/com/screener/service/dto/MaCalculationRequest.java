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

	// ── MA200 removed — replaced by KDJ + MACD momentum indicators ────

	/** RSI14 — optional, enhances hold-day prediction */
	private Double rsi14;

	/** ATR14 — optional, used for dynamic stop-loss calculation */
	private Double atr14;

	/** Volume ratio vs average — optional but strongly recommended */
	private Double volumeRatio;

	// ── KDJ Oscillator ────────────────────────────────────────────────

	/** KDJ K line (fast stochastic) */
	private Double kdjK;

	/** KDJ D line (smoothed K) */
	private Double kdjD;

	/** KDJ J line = 3K − 2D; oscillates wider, leads reversals */
	private Double kdjJ;

}