package com.screener.service.dto;

import java.util.List;

public record PullbackRequest(List<Double> priceHistory, double currentPrice, double ma5, double ma20) {
}