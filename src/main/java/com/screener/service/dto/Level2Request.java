package com.screener.service.dto;

import java.util.List;

import com.screener.service.model.Level2Entry;

public record Level2Request(List<Level2Entry> bids, List<Level2Entry> asks, double bidPressurePct,
		double askPressurePct) {
}
