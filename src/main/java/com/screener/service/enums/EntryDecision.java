package com.screener.service.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EntryDecision {
	PROCEED_FULL("PROCEED ✅", "🚀", "100%", "All 5 filters aligned — full position"),
	PROCEED_REDUCED("PROCEED (reduced) 🟡", "🟡", "50%", "Some filters yellow — reduce size"),
	WAIT_PULLBACK("WAIT ⏳", "⏳", "0%", "Fresh trend (S++) but price overextended — wait for MA20"),
	SKIP_BEARISH("SKIP 🔻", "🔻", "0%", "MA20 below MA50 — no long trades"),
	SKIP_EXHAUSTED("SKIP 🛑", "🛑", "0%", "Trend >10% exhausted — high reversal risk"),
	SKIP_BELOW_MA20("SKIP 🚫", "🚫", "0%", "Price below MA20 — support broken"),
	SKIP_OVEREXTENDED("SKIP 🔴", "🔴", "0%", "Price >10% above MA20 — do not chase"),
	SKIP_RSI_OB("SKIP 🔴", "🔴", "0%", "RSI >80 — extremely overbought"),
	SKIP_RSI_WEAK("SKIP 🔴", "🔴", "0%", "RSI <40 — momentum failing"),
	SKIP_LOW_VOLUME("SKIP 🔴", "🔴", "0%", "Volume ratio <0.8× — no buyers");

	public final String label, emoji, positionSize, note;
}
