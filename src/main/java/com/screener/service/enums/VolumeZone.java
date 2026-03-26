package com.screener.service.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum VolumeZone {
	SURGE("Surge (≥2.0×)", "🚀", "Institutional / news driven"), STRONG("Strong (1.5–2.0×)", "✅", "Clear accumulation"),
	ABOVE_AVG("Above Avg (1.2–1.5×)", "🟢", "Buyers stepping in"),
	NORMAL("Normal (0.8–1.2×)", "⚪", "No strong conviction"), WEAK("Weak (<0.8×)", "🔴", "No buyers — avoid"),
	UNKNOWN("Not Provided", "⚪", "Unconfirmed");

	public final String label, emoji, meaning;
}