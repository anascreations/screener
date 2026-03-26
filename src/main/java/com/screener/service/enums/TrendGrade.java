package com.screener.service.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum TrendGrade {
	BEARISH("BEARISH", "🔻", "MA20 below MA50 — downtrend, avoid longs"),
	FLAT("FLAT", "➡️", "<1% gap — crossover zone, wait for separation"),
	S_PLUS_PLUS("S++", "🚀", "1–3% gap — trend just began, highest probability"),
	A("A", "✅", "3–5% gap — healthy trend, good entry"), B("B", "⚠️", "5–8% gap — maturing trend, smaller size"),
	C("C", "🟡", "8–10% gap — late trend, tight SL required"),
	EXHAUSTED("EXHAUSTED", "🛑", ">10% gap — trend exhausted, do not chase");

	public final String grade, emoji, meaning;
}