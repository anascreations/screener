package com.screener.service.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RiskLevel {
	LOW_PROCEED("Low Risk", "🟢", "PROCEED"), MEDIUM_PROCEED("Medium Risk", "🟡", "PROCEED"),
	HIGH_SKIP("High Risk", "🔴", "SKIP");

	public final String label, emoji, decision;
}