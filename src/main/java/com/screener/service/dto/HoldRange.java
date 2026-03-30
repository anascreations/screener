package com.screener.service.dto;

public record HoldRange(int min, int max, int target) {
	public HoldRange shiftFaster(int n) {
		return new HoldRange(Math.max(1, min - n), Math.max(1, max - n), Math.max(1, target - n));
	}

	public HoldRange shiftSlower(int n) {
		return new HoldRange(min + n, max + n, target + n);
	}
}
