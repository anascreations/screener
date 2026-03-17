package com.screener.service.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MaCalculationRequest {

	// ── Required ──────────────────────────────────────────────────────────────
	@NotNull
	@DecimalMin("0.0001")
	private Double marketPrice;
	@NotNull
	@DecimalMin("0.0001")
	private Double ma5;
	@NotNull
	@DecimalMin("0.0001")
	private Double ma20;
	@NotNull
	@DecimalMin("0.0001")
	private Double ma50;
	@NotNull
	@DecimalMin("0.0001")
	private Double ma200;
	@NotBlank
	private String timeframe;

	// ── Optional enrichment ───────────────────────────────────────────────────
	private Double rsi14; // 0–100
	private Double atr14; // dynamic SL sizing
	private Double volume; // today's volume
	private Double avgVolume20; // 20-day avg volume
}