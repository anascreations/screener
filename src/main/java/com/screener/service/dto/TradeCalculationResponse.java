package com.screener.service.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TradeCalculationResponse {
	private String ticker;
	private String timeframe;
	private String marketRegime; // BULL / BEAR / NEUTRAL
	private String trendStrength; // STRONG / MODERATE / WEAK
	private String signalQuality; // A / B / C / INVALID
	private Map<String, Object> tradePlan;
	private List<String> warnings; // e.g. "Price far above MA200 — extended"
	private List<String> confluences; // e.g. "MA5 > MA20 > MA50 aligned"
	private Instant calculatedAt;
	private Double rsi14;
	private Double atr14;
	private Double volumeRatio;
}
