package com.screener.service.dto;

public record TimeframeConfig(String label, double lowThreshold, double highThreshold, double slBuffer,
		String holdDuration) {
	public static TimeframeConfig of(String raw) {
		return switch (raw == null ? "DAILY"
				: raw.toUpperCase().trim().replace("-", "").replace("_", "").replace(" ", "")) {
		case "15M", "15MIN", "15MINUTE", "15MINUTES" -> new TimeframeConfig("15M", 1.0, 3.0, 0.0010, "15–60 minutes");
		case "30M", "30MIN", "30MINUTE", "30MINUTES" ->
			new TimeframeConfig("30M", 1.5, 3.5, 0.0010, "30 min – 2 hours");
		case "1H", "60M", "1HOUR", "HOURLY" -> new TimeframeConfig("1H", 1.5, 4.0, 0.0015, "1–4 hours");
		case "2H", "2HOUR" -> new TimeframeConfig("2H", 2.0, 5.0, 0.0020, "2–8 hours");
		case "4H", "4HOUR" -> new TimeframeConfig("4H", 2.0, 5.0, 0.0030, "4–24 hours");
		case "WEEKLY", "1W", "W", "WEEK" -> new TimeframeConfig("WEEKLY", 5.0, 12.0, 0.0050, "2–6 weeks");
		default -> new TimeframeConfig("DAILY", 3.0, 7.0, 0.0010, "2–5 days");
		};
	}
}