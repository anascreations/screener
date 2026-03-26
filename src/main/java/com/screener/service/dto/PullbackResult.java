package com.screener.service.dto;

import java.util.Map;

public record PullbackResult(boolean isPullback, String phase, String signal, String emoji, double pullbackDepth,
		double pullbackTarget, boolean entryNow, String advice, Map<String, Object> detail) {
}