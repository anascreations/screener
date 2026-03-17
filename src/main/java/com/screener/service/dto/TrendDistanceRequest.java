package com.screener.service.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TrendDistanceRequest {
	@NotBlank
	private String market;
	@NotNull
	@DecimalMin("0.0001")
	private Double marketPrice;
	@NotNull
	@DecimalMin("0.0001")
	private Double ma20;
	@NotNull
	@DecimalMin("0.0001")
	private Double ma50;
	@NotBlank
	private String timeframe;
	private String ticker; // optional, for display
}