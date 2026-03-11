package com.screener.service.model;

import java.time.LocalDate;

public record DailyBar(String code, String name, LocalDate date, double open, double high, double low, double close,
		long volume) {
}
