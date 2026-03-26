package com.screener.service.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RsiZone {
	EXTREME_OB("Extremely Overbought (>80)", "🔴", "Hard skip — reversal imminent"),
	OVERBOUGHT("Overbought (70–80)", "🟡", "Reduce size 50%"),
	STRONG("Strong Momentum (65–70)", "🟢", "Valid — watch closely"),
	IDEAL("Ideal Zone (50–65)", "✅", "Best momentum zone"),
	RECOVERING("Recovering (40–50)", "⚪", "Wait for RSI to cross 50"),
	WEAK("Weak (<40)", "🔴", "Skip — momentum failing"), UNKNOWN("Not Provided", "⚪", "Unconfirmed");

	public final String label, emoji, meaning;
}