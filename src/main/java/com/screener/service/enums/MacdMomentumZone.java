package com.screener.service.enums;

public enum MacdMomentumZone {
	STRONG_BULL("Strong Bull — DIF>DEA>0", "🚀",
			"DIF and DEA both above zero, histogram positive — peak trend momentum"),
	BULL("Bullish — DIF>DEA", "✅", "DIF crossed above DEA, histogram positive — upward momentum active"),
	NEAR_CROSS("Near Cross — DIF≈DEA", "⚠️", "DIF approaching DEA — watch for golden cross breakout"),
	BEARISH("Bearish — DIF<DEA", "🔴", "DIF below DEA, histogram negative — downward pressure"),
	STRONG_BEAR("Strong Bear — DIF<DEA<0", "🔻", "Both DIF and DEA below zero — confirmed downtrend, no entry"),
	UNKNOWN("Unknown — not provided", "⚪", "MACD values not provided");

	public final String label;
	public final String emoji;
	public final String meaning;

	MacdMomentumZone(String label, String emoji, String meaning) {
		this.label = label;
		this.emoji = emoji;
		this.meaning = meaning;
	}
}