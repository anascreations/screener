package com.screener.service.dto;

import java.util.Map;

public record PullbackResult(boolean isPullback, String phase, // ADVANCING | PULLBACK | CONSOLIDATING | REVERSAL
		String signal, String emoji, double pullbackDepth, // how far price dropped from recent high (%)
		double pullbackTarget, // ideal entry zone
		boolean entryNow, // true = pullback touching MA5, enter now
		String advice, Map<String, Object> detail) {
}