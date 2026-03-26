package com.screener.service.enums;

public enum Momentum {
	STRONGLY_UP("⬆️⬆️ Strongly Up"), TURNING_UP("↗️ Turning Up"), NEUTRAL("➡️ Neutral"),
	TURNING_DOWN("↘️ Turning Down"), STRONGLY_DOWN("⬇️⬇️ Strongly Down");

	private final String label;

	Momentum(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}