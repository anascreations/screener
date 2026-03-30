package com.screener.service.dto;

public record HoldCalculation(int minDays, int maxDays, int targetDays, String basis, String exitRule) {
	public String label() {
		return minDays == maxDays ? minDays + " day" + (minDays > 1 ? "s" : "") : minDays + "–" + maxDays + " days";
	}
}