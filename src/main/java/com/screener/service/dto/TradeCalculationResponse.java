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
	private String marketRegime;
	private String trendStrength;
	private String signalQuality;
	private Map<String, Object> tradePlan;
	private List<String> warnings;
	private List<String> confluences;
	private Instant calculatedAt;
	private Double rsi14;
	private Double atr14;
	private Double volumeRatio;
}
