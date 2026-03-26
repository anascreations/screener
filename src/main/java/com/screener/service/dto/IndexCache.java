package com.screener.service.dto;

import com.screener.service.service.MarketIndexService.IndexTrend;

public record IndexCache(IndexTrend trend, double indexClose, double ema20, double ema50, long fetchedAt) {
}
