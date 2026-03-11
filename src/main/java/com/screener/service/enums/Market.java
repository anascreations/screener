package com.screener.service.enums;

import java.time.LocalTime;
import java.time.ZoneId;

import com.screener.service.util.DateUtil;

/**
 * All market-specific configuration lives here. Adding a new market = adding
 * one enum constant + overriding the abstract methods.
 *
 * Key design: - tick() — price rounding to exchange-legal increments -
 * bbHeadroom() — distance to BB upper expressed in native units (MY = absolute
 * sen; US = % of price) - bbHeadroomScore/Warn — scoring thresholds translated
 * to that unit - nextTradingDay — MY uses TradingCalendarService; US uses
 * simple weekday skip
 */
public enum Market {
	MY(DateUtil.KL, "RM", ".KL", LocalTime.of(17, 30)) {
		@Override
		public double tick(double price) {
			if (price <= 0)
				return price;
			if (price < 0.10)
				return Math.round(price * 2000) / 2000.0;
			if (price < 0.50)
				return Math.round(price * 200) / 200.0;
			if (price < 1.00)
				return Math.round(price * 100) / 100.0;
			if (price < 2.50)
				return Math.round(price * 50) / 50.0;
			if (price < 5.00)
				return Math.round(price * 20) / 20.0;
			return Math.round(price * 10) / 10.0;
		}

		@Override
		public double bbHeadroom(double bbUpper, double close) {
			return (bbUpper - close) * 100; // sen
		}

		@Override
		public double bbLittleRoomThreshold() {
			return 2.0;
		} // sen

		@Override
		public double bbLimitedRoomThreshold() {
			return 5.0;
		} // sen

		@Override
		public String formatPrice(double price) {
			return String.format("RM%.3f", price);
		}

		@Override
		public String formatRisk(double risk) {
			return String.format("%.2f sen", risk * 100);
		}

		@Override
		public String formatHeadroom(double h) {
			return String.format("%.1f sen", h);
		}
	},
	US(DateUtil.US, "$", "", LocalTime.of(16, 30)) {
		@Override
		public double tick(double price) {
			return price <= 0 ? price : Math.round(price * 100) / 100.0;
		}

		@Override
		public double bbHeadroom(double bbUpper, double close) {
			return bbUpper > 0 ? (bbUpper - close) / close * 100 : 5.0; // percent
		}

		@Override
		public double bbLittleRoomThreshold() {
			return 0.5;
		} // %

		@Override
		public double bbLimitedRoomThreshold() {
			return 1.5;
		} // %

		@Override
		public String formatPrice(double price) {
			return String.format("$%.2f", price);
		}

		@Override
		public String formatRisk(double risk) {
			return String.format("%.2f¢", risk * 100);
		}

		@Override
		public String formatHeadroom(double h) {
			return String.format("%.1f%%", h);
		}
	};

	// ── Fields ───────────────────────────────────────────────────────────────
	public final ZoneId zoneId;
	public final String currency;
	public final String tickerSuffix; // ".KL" for MY, "" for US
	public final LocalTime eodFinal; // after this time today's bar is confirmed EOD

	Market(String zone, String currency, String tickerSuffix, LocalTime eodFinal) {
		this.zoneId = ZoneId.of(zone);
		this.currency = currency;
		this.tickerSuffix = tickerSuffix;
		this.eodFinal = eodFinal;
	}

	// ── Abstract methods every market must implement ──────────────────────────
	public abstract double tick(double price);

	public abstract double bbHeadroom(double bbUpper, double close);

	public abstract double bbLittleRoomThreshold();

	public abstract double bbLimitedRoomThreshold();

	public abstract String formatPrice(double price);

	public abstract String formatRisk(double risk);

	public abstract String formatHeadroom(double h);

	// ── Helpers ───────────────────────────────────────────────────────────────
	public String fullTicker(String code) {
		return code.toUpperCase() + tickerSuffix;
	}

	public static Market from(String s) {
		return valueOf(s.toUpperCase());
	}
}