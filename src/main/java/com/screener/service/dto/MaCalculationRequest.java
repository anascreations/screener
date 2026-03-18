package com.screener.service.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MaCalculationRequest {
	@NotBlank
	private String market;
	@NotBlank
	private String timeframe;
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
	private Double rsi14;
	private Double atr14;
	private Double volumeRatio;
}