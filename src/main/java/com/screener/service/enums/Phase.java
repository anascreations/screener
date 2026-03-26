package com.screener.service.enums;

public enum Phase {
	ADVANCING("Advancing"), PULLBACK("Pullback"), CONSOLIDATING("Consolidating"), REVERSAL("Reversal");

	private final String label;

	Phase(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}