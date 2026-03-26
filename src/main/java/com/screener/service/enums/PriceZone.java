package com.screener.service.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PriceZone {
	BELOW_MA20("Below MA20", "🚫", "Support broken — downtrend"),
	IDEAL("Ideal Zone", "🎯", "0–2% above MA20 — best entry zone"),
	ACCEPTABLE("Acceptable", "🟢", "2–5% above MA20 — valid entry"),
	STRETCHED("Stretched", "🟡", "5–10% above MA20 — reduce size"),
	OVEREXTENDED("Overextended", "🔴", ">10% above MA20 — do not chase");

	public final String label, emoji, meaning;
}