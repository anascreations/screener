package com.screener.service.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.screener.service.enums.Market;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Unified scan result — MY and US markets in one table.
 *
 * v2 additions: tradeType — SCALP | SWING | BOTH confidence — A | B | C (A =
 * all boxes ticked) scalpEntry/SL/TP1/RR — dedicated scalp entry plan ema200 —
 * major trend EMA volumeValue — daily turnover (liquidity check) weekHigh52 —
 * 52-week high weekLow52 — 52-week low macdDepth — depth of histogram before
 * cross (reversal energy) atrPct — ATR as % of price (volatility qualifier)
 * candlePattern — HAMMER | BULL_ENGULFING | MORNING_STAR | STRONG_BULL |
 * BEARISH | —
 *
 * Database migration: H2 with ddl-auto=update adds new columns automatically.
 * For PostgreSQL/MySQL run: ALTER TABLE scan_results ADD COLUMN IF NOT EXISTS
 * trade_type VARCHAR(10); ALTER TABLE scan_results ADD COLUMN IF NOT EXISTS
 * confidence VARCHAR(3); ALTER TABLE scan_results ADD COLUMN IF NOT EXISTS
 * scalp_entry DOUBLE; ALTER TABLE scan_results ADD COLUMN IF NOT EXISTS
 * scalp_sl DOUBLE; ALTER TABLE scan_results ADD COLUMN IF NOT EXISTS scalp_tp1
 * DOUBLE; ALTER TABLE scan_results ADD COLUMN IF NOT EXISTS scalp_rr DOUBLE;
 * ALTER TABLE scan_results ADD COLUMN IF NOT EXISTS ema200 DOUBLE; ALTER TABLE
 * scan_results ADD COLUMN IF NOT EXISTS volume_value DOUBLE; ALTER TABLE
 * scan_results ADD COLUMN IF NOT EXISTS week_high52 DOUBLE; ALTER TABLE
 * scan_results ADD COLUMN IF NOT EXISTS week_low52 DOUBLE; ALTER TABLE
 * scan_results ADD COLUMN IF NOT EXISTS macd_depth DOUBLE; ALTER TABLE
 * scan_results ADD COLUMN IF NOT EXISTS atr_pct DOUBLE; ALTER TABLE
 * scan_results ADD COLUMN IF NOT EXISTS candle_pattern VARCHAR(40);
 */
@Entity
@Table(name = "scan_results", indexes = { @Index(name = "idx_market_scandate", columnList = "market,scan_date"),
		@Index(name = "idx_market_decision", columnList = "market,decision,scan_date"),
		@Index(name = "idx_market_code", columnList = "market,stock_code") })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanResult {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	@Enumerated(EnumType.STRING)
	@Column(length = 3, nullable = false)
	private Market market;
	private String stockCode;
	private String stockName;
	private String exchange;
	private LocalDateTime scannedAt;
	private LocalDate scanDate;
	private LocalDate tradeDate;
	private String decision;
	@Column(length = 10)
	private String tradeType;
	@Column(length = 3)
	private String confidence;
	private double score;
	private double closePrice;
	private double openPrice;
	private double highPrice;
	private double lowPrice;
	private long volume;
	private double volumeRatio;
	private double volumeValue;
	private double weekHigh52;
	private double weekLow52;
	private double scalpEntry;
	private double scalpSL;
	private double scalpTP1;
	private double scalpRR;
	private double entryPrice;
	private double entryMax;
	private double stopLoss;
	private double targetTP1;
	private double targetTP2;
	private double targetTP3;
	private double riskReward;
	private double rsi;
	private double macdHistogram;
	private double macdDepth;
	private double ema9;
	private double ema21;
	private double ema50;
	private double ema200;
	private double adx;
	private double atr;
	private double atrPct;
	@Column(length = 40)
	private String candlePattern;
	@Column(length = 2000)
	private String reasons;
	@Column(length = 800)
	private String warnings;
}