package com.screener.service.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.screener.service.enums.Market;
import com.screener.service.model.DailyBar;
import com.screener.service.model.ScanResult;
import com.screener.service.repository.ScanResultRepository;
import com.screener.service.service.MarketIndexService.IndexTrend;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class AnalysisService {
	private final Map<Market, ExchangeService> exchangeMap;
	private final IndicatorService indicatorService;
	private final MarketIndexService indexService;
	private final ScanResultRepository repository;
	private final TradingCalendarService calendarService;
	@Value("${screener.min-score:65}")
	private double minScore;
	@Value("${screener.min-volume-ratio:1.3}")
	private double minVolumeRatio;
	private static final double RSI_HARD_MAX = 75.0;
	private static final double BB_PCT_HARD_MAX = 0.90;
	private static final double MIN_TP1_RR = 1.2;
	private static final double MIN_TP2_RR = 2.0;
	private static final double MIN_SCORE_FOR_BUY = 108.0;
	private static final double MIN_MACD_DEPTH = 0.003;
	private static final double MY_MIN_VOL_VALUE = 30_000.0;
	private static final double US_MIN_VOL_VALUE = 300_000.0;
	private static final double MIN_ATR_PCT = 0.8;
	private static final double CONF_A_MIN = 145.0;
	private static final double CONF_B_MIN = 120.0;
	private static final double SCALP_ADX_MIN = 28.0;
	private static final double SCALP_VOL_MIN = 2.5;
	private static final double SCALP_ATR_PCT = 1.5;
	private static final double SCALP_RSI_MIN = 42.0;
	private static final double SCALP_RSI_MAX = 67.0;

	public AnalysisService(ExchangeMyService myExchange, ExchangeUsService usExchange,
			IndicatorService indicatorService, MarketIndexService indexService, ScanResultRepository repository,
			TradingCalendarService calendarService) {
		this.exchangeMap = Map.of(Market.MY, myExchange, Market.US, usExchange);
		this.indicatorService = indicatorService;
		this.indexService = indexService;
		this.repository = repository;
		this.calendarService = calendarService;
	}

	public Optional<ScanResult> scan(Market market, String code) {
		log.info("──────────────────────────────────────────────────");
		log.info("[{}] ANALYZE: {}", market, market.fullTicker(code));
		List<DailyBar> bars = exchangeMap.get(market).fetchHistory(code);
		if (bars.isEmpty()) {
			log.warn("[{}] SKIP — no data", code);
			return Optional.empty();
		}
		if (bars.size() < 52) {
			log.warn("[{}] SKIP — only {} bars (need 52+)", code, bars.size());
			return Optional.empty();
		}
		Map<String, Double> ind = indicatorService.calculate(bars, market);
		if (ind.isEmpty()) {
			log.warn("[{}] SKIP — indicator calculation failed", code);
			return Optional.empty();
		}
		DailyBar today = bars.get(bars.size() - 1);
		DailyBar yesterday = bars.get(bars.size() - 2);
		DailyBar twoDaysAgo = bars.get(bars.size() - 3);
		logIndicators(market, code, today, ind);
		double rsi = ind.get("rsi");
		double bbPct = ind.get("bbPct");
		double volRat = ind.get("volumeRatio");
		double volVal = ind.get("volumeValue");
		double atrPct = ind.get("atrPct");
		double ema200 = ind.get("ema200");
		double depth = ind.get("macdDepth");
		double minVol = market == Market.MY ? MY_MIN_VOL_VALUE : US_MIN_VOL_VALUE;
		if (rsi >= RSI_HARD_MAX) {
			log.info("[{}] ⛔ BLOCK — RSI {:.1f} overbought (max {})", code, rsi, RSI_HARD_MAX);
			return Optional.of(buildTransient(market, code, today, ind, "RSI OVERBOUGHT " + f1(rsi)));
		}
		if (bbPct >= BB_PCT_HARD_MAX) {
			log.info("[{}] ⛔ BLOCK — BB {}% at upper band", code, f0(bbPct * 100));
			return Optional.of(buildTransient(market, code, today, ind, "BB UPPER BAND " + f0(bbPct * 100) + "%"));
		}
		if (volVal < minVol) {
			log.info("[{}] ⛔ BLOCK — Volume value {} below minimum {} — ILLIQUID", code, market.formatPrice(volVal),
					market.formatPrice(minVol));
			return Optional
					.of(buildTransient(market, code, today, ind, "ILLIQUID < " + market.formatPrice(minVol) + "/day"));
		}
		MacdCrossState macdCross = evaluateMacdCross(code, ind);
		IndexTrend indexTrend = indexService.getTrend(market);
		log.info("  [{}] Market index: {} ({})", code, indexTrend, indexService.getSummary(market));
		int ema200Bars = ind.get("ema200Bars").intValue();
		boolean isEma200Real = ema200Bars >= 150;
		boolean aboveEma200 = today.close() > ema200;
		ScoringResult scoring = evaluate(market, today, yesterday, twoDaysAgo, ind, indexTrend, aboveEma200,
				isEma200Real);
		logScoring(code, scoring);
		String decision = determineDecision(scoring.score(), volRat, rsi, bbPct, macdCross, indexTrend, aboveEma200,
				isEma200Real, depth, atrPct);
		String tradeType = classifyTradeType(ind, today, yesterday, macdCross, volRat, atrPct);
		EntryPlan entry = EntryPlan.ZERO;
		if ("BUY".equals(decision) || "WATCH".equals(decision)) {
			entry = calculateEntry(market, today, yesterday, twoDaysAgo, ind, tradeType);
			double risk = entry.ideal() - entry.sl();
			if (risk <= 0) {
				log.warn("[{}] ⚠ SL >= entry — downgrade to IGNORE", code);
				decision = "IGNORE";
				entry = EntryPlan.ZERO;
			} else {
				double tp1RR = (entry.tp1() - entry.ideal()) / risk;
				double tp2RR = (entry.tp2() - entry.ideal()) / risk;
				if (entry.tp1() <= entry.ideal()) {
					entry = fixEntryPlan(market, entry, risk, tradeType);
					tp1RR = (entry.tp1() - entry.ideal()) / risk;
				}
				if (tp1RR < MIN_TP1_RR) {
					log.warn("[{}] ⚠ TP1 R:R {} < {} — downgrade to WATCH", code, f1(tp1RR), MIN_TP1_RR);
					decision = "WATCH";
				}
				if ("BUY".equals(decision) && tp2RR < MIN_TP2_RR) {
					log.warn("[{}] ⚠ TP2 R:R {} < {} — downgrade to WATCH", code, f1(tp2RR), MIN_TP2_RR);
					decision = "WATCH";
				}
			}
		}
		String confidence = gradeConfidence(scoring.score(), macdCross, indexTrend, aboveEma200, volRat, rsi, ind);
		LocalDate scanDate = LocalDate.now(market.zoneId);
		LocalDate tradeDate = nextTradingDay(market, scanDate);
		List<String> warnings = new ArrayList<>(scoring.negatives());
		if (macdCross == MacdCrossState.NONE)
			warnings.add(0, "NO MACD CROSS — histogram positive but no fresh cross today");
		if (!aboveEma200)
			warnings.add(0, "BELOW EMA" + ema200Bars + " — structural downtrend risk");
		if (depth < MIN_MACD_DEPTH && macdCross != MacdCrossState.NONE)
			warnings.add("SHALLOW MACD DEPTH " + f4(depth) + " — may be noise");
		if (indexTrend == IndexTrend.DOWNTREND)
			warnings.add(0, market + " INDEX DOWNTREND — elevated failure rate");
		ScanResult result = ScanResult.builder().market(market).stockCode(code.toUpperCase()).stockName(today.name())
				.exchange(market == Market.US ? "" : "Bursa").scannedAt(LocalDateTime.now(market.zoneId))
				.scanDate(scanDate).tradeDate(tradeDate).decision(decision).tradeType(tradeType).confidence(confidence)
				.score(Math.round(scoring.score() * 10) / 10.0).closePrice(today.close()).highPrice(today.high())
				.lowPrice(today.low()).volume(today.volume()).volumeRatio(ind.get("volumeRatio"))
				.volumeValue(ind.get("volumeValue")).weekHigh52(ind.get("weekHigh52")).weekLow52(ind.get("weekLow52"))
				.entryPrice(entry.ideal()).entryMax(entry.max()).stopLoss(entry.sl()).scalpEntry(entry.scalpEntry())
				.scalpSL(entry.scalpSL()).scalpTP1(entry.scalpTP1()).scalpRR(entry.scalpRR()).targetTP1(entry.tp1())
				.targetTP2(entry.tp2()).targetTP3(entry.tp3()).riskReward(entry.rrr()).rsi(ind.get("rsi"))
				.macdHistogram(ind.get("macdHist")).macdDepth(ind.get("macdDepth")).ema9(ind.get("ema9"))
				.ema21(ind.get("ema21")).ema50(ind.get("ema50")).ema200(ema200).adx(ind.get("adx")).atr(ind.get("atr"))
				.atrPct(atrPct).candlePattern(IndicatorService.patternName(ind.get("candlePattern")))
				.reasons(String.join(" | ", scoring.positives())).warnings(String.join(" | ", warnings)).build();
		if ("BUY".equals(decision)) {
			repository.save(result);
			log.info("[{}] ✅ BUY saved to database", code);
		}
		logDecision(market, result, tradeDate);
		return Optional.of(result);
	}

	private MacdCrossState evaluateMacdCross(String code, Map<String, Double> ind) {
		double curr = ind.get("macdHist");
		double prev = ind.get("macdHistPrev");
		double prev2 = ind.get("macdHistPrev2");
		if (prev <= 0.0 && curr > 0.0) {
			log.info("  [{}] ✅ MACD FRESH CROSS — prev={} → curr={} (depth={})", code, f4(prev), f4(curr),
					f4(ind.get("macdDepth")));
			return MacdCrossState.FRESH;
		}
		if (prev2 <= 0.0 && prev > 0.0 && curr > prev) {
			log.info("  [{}] ✅ MACD CONFIRMED — prev2={} prev={} curr={} (accelerating)", code, f4(prev2), f4(prev),
					f4(curr));
			return MacdCrossState.CONFIRMED;
		}
		log.info("  [{}] NO MACD CROSS — hist={} prev={} prev2={}", code, f4(curr), f4(prev), f4(prev2));
		return MacdCrossState.NONE;
	}

	private enum MacdCrossState {
		FRESH, CONFIRMED, NONE
	}

	private ScoringResult evaluate(Market market, DailyBar today, DailyBar yesterday, DailyBar twoDaysAgo,
			Map<String, Double> ind, IndexTrend indexTrend, boolean aboveEma200, boolean isEma200Real) {
		double score = 0;
		List<String> pos = new ArrayList<>();
		List<String> neg = new ArrayList<>();
		double rsi = ind.get("rsi");
		double rsiSlope = ind.get("rsiSlope");
		double macdHist = ind.get("macdHist");
		double macdPrev = ind.get("macdHistPrev");
		double macdPrev2 = ind.get("macdHistPrev2");
		double macdSlope = ind.get("macdSlope");
		double macdDepth = ind.get("macdDepth");
		double ema9 = ind.get("ema9");
		double ema21 = ind.get("ema21");
		double ema50 = ind.get("ema50");
		double ema200 = ind.get("ema200");
		double adx = ind.get("adx");
		double stochK = ind.get("stochK");
		double stochD = ind.get("stochD");
		double stochKP = ind.get("stochKPrev");
		double stochDP = ind.get("stochDPrev");
		double volRatio = ind.get("volumeRatio");
		double bbPct = ind.get("bbPct");
		double bbWidth = ind.get("bbWidth");
		double bbUpper = ind.get("bbUpper");
		double bbLower = ind.get("bbLower");
		double bbMid = ind.get("bbMid");
		double obvTrend = ind.get("obvTrend");
		double candlePat = ind.get("candlePattern");
		double yearPos52 = ind.get("yearPos52");
		int ema200b = ind.get("ema200Bars").intValue();
		double close = today.close();
		switch (indexTrend) {
		case UPTREND -> {
			score += 20;
			pos.add("📈 Market index UPTREND — macro tailwind (+20)");
		}
		case NEUTRAL -> pos.add("➡️ Market index NEUTRAL — no bonus");
		case DOWNTREND -> {
			score -= 30;
			neg.add("📉 Market index DOWNTREND — macro headwind (-30). HIGH FAILURE RISK.");
		}
		}
		if (aboveEma200) {
			score += 10;
			String label = isEma200Real ? "EMA200" : ("EMA" + ema200b);
			pos.add(String.format("✅ Price(%s) above %s(%s) — institutional uptrend", market.formatPrice(close), label,
					market.formatPrice(ema200)));
		} else {
			double penalty = isEma200Real ? -20 : -10;
			score += penalty;
			String label = isEma200Real ? "EMA200" : ("EMA" + ema200b);
			neg.add(String.format("❌ Price(%s) BELOW %s(%s) — structural downtrend (%.0f)", market.formatPrice(close),
					label, market.formatPrice(ema200), penalty));
		}
		if (macdPrev <= 0.0 && macdHist > 0.0) {
			score += 30;
			pos.add(String.format("🔥 MACD FRESH GOLDEN CROSS! hist=%.4f (prev=%.4f)", macdHist, macdPrev));
		} else if (macdPrev2 <= 0.0 && macdPrev > 0.0 && macdHist > macdPrev) {
			score += 22;
			pos.add(String.format("✅ MACD CROSS CONFIRMED hist=%.4f accelerating", macdHist));
		} else if (macdHist > 0 && macdSlope > 0) {
			score += 10;
			pos.add(String.format("MACD positive & accelerating (no fresh cross) %.4f", macdHist));
		} else if (macdHist > 0 && macdHist > macdPrev) {
			score += 6;
			pos.add(String.format("MACD positive growing (no fresh cross) %.4f", macdHist));
		} else if (macdHist > 0) {
			score += 2;
			pos.add(String.format("MACD positive flat (no fresh cross) %.4f", macdHist));
		} else if (macdHist < 0 && macdHist > macdPrev) {
			score -= 2;
			neg.add(String.format("MACD negative recovering %.4f", macdHist));
		} else {
			score -= 10;
			neg.add(String.format("MACD negative declining %.4f", macdHist));
		}
		if (macdPrev <= 0.0 && macdHist > 0.0 || macdPrev2 <= 0.0 && macdPrev > 0.0) {
			if (macdDepth >= 0.05) {
				score += 15;
				pos.add(String.format("💎 Deep MACD valley %.4f — high reversal energy!", macdDepth));
			} else if (macdDepth >= 0.02) {
				score += 10;
				pos.add(String.format("✅ Strong MACD depth %.4f — good reversal conviction", macdDepth));
			} else if (macdDepth >= 0.008) {
				score += 5;
				pos.add(String.format("MACD depth %.4f — moderate", macdDepth));
			} else if (macdDepth < 0.003) {
				score -= 8;
				neg.add(String.format("⚠ SHALLOW MACD depth %.4f — may be noise cross", macdDepth));
			}
		}
		if (ema9 > ema21 && ema21 > ema50 && aboveEma200) {
			score += 25;
			pos.add(String.format("EMA9(%s) > EMA21(%s) > EMA50(%s) > EMA%d — perfect uptrend stack!",
					market.formatPrice(ema9), market.formatPrice(ema21), market.formatPrice(ema50), ema200b));
		} else if (ema9 > ema21 && ema21 > ema50) {
			score += 18;
			pos.add("EMA9>21>50 uptrend (not yet above EMA200)");
		} else if (ema9 > ema21 && close > ema50) {
			score += 12;
			pos.add("EMA9>EMA21 & price above EMA50");
		} else if (ema9 > ema21) {
			score += 6;
			pos.add("EMA9>EMA21 short-term only");
		} else if (ema9 < ema21 && ema21 < ema50) {
			score -= 15;
			neg.add("EMA9<EMA21<EMA50 confirmed downtrend");
		} else {
			score -= 5;
			neg.add("EMA not aligned");
		}
		if (close > ema21) {
			score += 5;
			pos.add(String.format("Price above EMA21(%s)", market.formatPrice(ema21)));
		} else {
			score -= 3;
			neg.add(String.format("Price below EMA21(%s)", market.formatPrice(ema21)));
		}
		if (rsi >= 50 && rsi <= 62) {
			score += 15;
			pos.add(String.format("RSI %.1f ideal zone (50-62)", rsi));
		} else if (rsi > 62 && rsi <= 68) {
			score += 5;
			pos.add(String.format("RSI %.1f acceptable but getting warm", rsi));
		} else if (rsi > 68 && rsi < RSI_HARD_MAX) {
			score -= 18;
			neg.add(String.format("RSI %.1f elevated — reversal risk", rsi));
		} else if (rsi >= 40 && rsi < 50) {
			score -= 3;
			neg.add(String.format("RSI %.1f below 50 (recovering)", rsi));
		} else if (rsi < 35) {
			score += 8;
			pos.add(String.format("RSI %.1f oversold — bounce setup", rsi));
		} else {
			score -= 5;
			neg.add(String.format("RSI %.1f weak zone", rsi));
		}
		if (rsiSlope >= 1.5) {
			score += 8;
			pos.add(String.format("RSI rising fast +%.1f/bar — momentum building!", rsiSlope));
		} else if (rsiSlope >= 0.5) {
			score += 4;
			pos.add(String.format("RSI rising +%.1f/bar", rsiSlope));
		} else if (rsiSlope < -1.0) {
			score -= 8;
			neg.add(String.format("RSI falling %.1f/bar — DIVERGENCE WARNING", rsiSlope));
		} else if (rsiSlope < 0) {
			score -= 3;
			neg.add(String.format("RSI slope negative %.1f/bar", rsiSlope));
		}
		if (volRatio >= 3.0) {
			score += 22;
			pos.add(String.format("Volume %.1fx — SMART MONEY signal!", volRatio));
		} else if (volRatio >= 2.0) {
			score += 16;
			pos.add(String.format("Volume %.1fx — strong conviction", volRatio));
		} else if (volRatio >= 1.5) {
			score += 8;
			pos.add(String.format("Volume %.1fx — above average", volRatio));
		} else if (volRatio >= 1.0) {
			score += 1;
			pos.add(String.format("Volume %.1fx — near average", volRatio));
		} else {
			score -= 12;
			neg.add(String.format("Volume %.1fx — low conviction", volRatio));
		}
		if (obvTrend > 0 && volRatio >= 1.5) {
			score += 5;
			pos.add("OBV rising — accumulation confirmed");
		}
		switch ((int) Math.round(candlePat)) {
		case 4 -> {
			score += 18;
			pos.add("🌟 MORNING STAR — 3-bar reversal confirmation! (+18)");
		}
		case 3 -> {
			score += 16;
			pos.add("🔥 BULL ENGULFING — strong reversal candle! (+16)");
		}
		case 2 -> {
			score += 12;
			pos.add("🔨 HAMMER — support rejection! (+12)");
		}
		case 1 -> {
			score += 8;
			pos.add("💪 STRONG BULL candle — bullish close (+8)");
		}
		case -1 -> {
			score -= 12;
			neg.add("🚩 BEARISH REVERSAL candle — shooting star/bear engulf (-12)");
		}
		}
		double range = today.high() - today.low();
		if (range > 0) {
			double closePos = (close - today.low()) / range;
			if (closePos >= 0.70) {
				score += 8;
				pos.add(String.format("Strong close %.0f%% of day range", closePos * 100));
			} else if (closePos <= 0.30) {
				score -= 8;
				neg.add(String.format("Weak close %.0f%% of day range", closePos * 100));
			}
			double body = today.close() - today.open();
			double bodyPct = body / range;
			if (body > 0 && bodyPct >= 0.5) {
				score += 8;
				pos.add(String.format("Bullish candle body %.0f%%", bodyPct * 100));
			} else if (body < 0 && Math.abs(bodyPct) >= 0.5) {
				score -= 8;
				neg.add(String.format("Bearish candle body %.0f%%", Math.abs(bodyPct) * 100));
			}
		}
		if (close > yesterday.high()) {
			score += 15;
			pos.add(String.format("BREAKOUT close(%s) > yesterday high(%s)", market.formatPrice(close),
					market.formatPrice(yesterday.high())));
		}
		if (today.high() > yesterday.high() && today.low() > yesterday.low()) {
			score += 6;
			pos.add("Higher High + Higher Low");
		}
		if (yesterday.low() > twoDaysAgo.low() && today.low() > yesterday.low()) {
			score += 5;
			pos.add("3 consecutive Higher Lows");
		}
		if (yearPos52 <= 0.30) {
			score += 12;
			pos.add(String.format("💡 Lower %.0f%% of 52-week range — lots of upside room!", yearPos52 * 100));
		} else if (yearPos52 <= 0.50) {
			score += 6;
			pos.add(String.format("Lower half of 52-week range (%.0f%%) — good room", yearPos52 * 100));
		} else if (yearPos52 <= 0.70) {
		} else if (yearPos52 <= 0.85) {
			score -= 10;
			neg.add(String.format("Upper %.0f%% of 52-week range — limited upside room", yearPos52 * 100));
		} else {
			score -= 25;
			neg.add(String.format("⚠ NEAR 52-WEEK HIGH (%.0f%%) — very little room, high reversal risk",
					yearPos52 * 100));
		}
		if (bbPct > 0.90) {
			score -= 25;
			neg.add("Price near BB upper — OVERBOUGHT");
		} else if (bbPct > 0.80) {
			score -= 12;
			neg.add(String.format("BB %.0f%% approaching upper band", bbPct * 100));
		} else if (bbPct >= 0.50 && bbPct <= 0.72) {
			score += 3;
			pos.add(String.format("Healthy BB zone %.0f%%", bbPct * 100));
		}
		if (bbWidth < 3.0 && close > yesterday.high()) {
			score += 8;
			pos.add(String.format("BB squeeze %.1f%% + breakout!", bbWidth));
		}
		double headroom = market.bbHeadroom(bbUpper, close);
		if (headroom < 0) {
			score -= 20;
			neg.add("Price ABOVE BB upper — overextended!");
		} else if (headroom < market.bbLittleRoomThreshold()) {
			score -= 10;
			neg.add(market.formatHeadroom(headroom) + " to BB upper — very tight");
		} else if (headroom < market.bbLimitedRoomThreshold()) {
			score -= 4;
			neg.add(market.formatHeadroom(headroom) + " to BB upper — limited");
		} else {
			score += 2;
			pos.add(market.formatHeadroom(headroom) + " headroom to BB upper (" + market.formatPrice(bbUpper) + ")");
		}
		if (close > bbMid && bbPct <= 0.80) {
			score += 3;
			pos.add("Above BB midline");
		}
		if (adx > 35) {
			score += 10;
			pos.add(String.format("ADX %.1f — very strong trend", adx));
		} else if (adx > 25) {
			score += 7;
			pos.add(String.format("ADX %.1f — strong trend", adx));
		} else if (adx > 20) {
			score += 3;
			pos.add(String.format("ADX %.1f — developing trend", adx));
		} else {
			score -= 5;
			neg.add(String.format("ADX %.1f — weak/ranging", adx));
		}
		if (stochKP < stochDP && stochK > stochD && stochK < 80) {
			score += 10;
			pos.add(String.format("Stochastic FRESH bullish cross K(%.1f)>D(%.1f)", stochK, stochD));
		} else if (stochK > stochD && stochK > 50 && stochK < 80) {
			score += 5;
			pos.add(String.format("Stochastic bullish K(%.1f)>D(%.1f)", stochK, stochD));
		} else if (stochK > 80) {
			score -= 8;
			neg.add(String.format("Stochastic overbought K(%.1f)", stochK));
		} else if (stochK < stochD && stochK < 50) {
			score -= 3;
			neg.add(String.format("Stochastic bearish K(%.1f)<D(%.1f)", stochK, stochD));
		}
		return new ScoringResult(score, pos, neg);
	}

	private String determineDecision(double score, double volRatio, double rsi, double bbPct, MacdCrossState macdCross,
			IndexTrend indexTrend, boolean aboveEma200, boolean isEma200Real, double macdDepth, double atrPct) {
		if (rsi >= RSI_HARD_MAX)
			return "IGNORE";
		if (bbPct >= BB_PCT_HARD_MAX)
			return "IGNORE";
		if (volRatio < minVolumeRatio)
			return "IGNORE";
		if (score < minScore)
			return "IGNORE";
		if (!aboveEma200 && isEma200Real)
			return "WATCH";
		if (indexTrend == IndexTrend.DOWNTREND)
			return "WATCH";
		if (macdCross == MacdCrossState.NONE)
			return "WATCH";
		if (score >= MIN_SCORE_FOR_BUY && volRatio >= minVolumeRatio)
			return "BUY";
		return "WATCH";
	}

	private String classifyTradeType(Map<String, Double> ind, DailyBar today, DailyBar yesterday,
			MacdCrossState macdCross, double volRatio, double atrPct) {
		double adx = ind.get("adx");
		double rsi = ind.get("rsi");
		double bbW = ind.get("bbWidth");
		boolean scalpADX = adx >= SCALP_ADX_MIN;
		boolean scalpVol = volRatio >= SCALP_VOL_MIN;
		boolean scalpATR = atrPct >= SCALP_ATR_PCT;
		boolean scalpRSI = rsi >= SCALP_RSI_MIN && rsi <= SCALP_RSI_MAX;
		boolean freshCross = macdCross == MacdCrossState.FRESH;
		boolean breakout = today.close() > yesterday.high();
		int scalpScore = (scalpADX ? 1 : 0) + (scalpVol ? 1 : 0) + (scalpATR ? 1 : 0) + (scalpRSI ? 1 : 0)
				+ (freshCross ? 1 : 0) + (breakout ? 1 : 0);
		if (scalpScore >= 6)
			return "BOTH";
		if (scalpScore >= 4 && scalpADX && freshCross)
			return "SCALP";
		return "SWING";
	}

	private String gradeConfidence(double score, MacdCrossState macdCross, IndexTrend indexTrend, boolean aboveEma200,
			double volRatio, double rsi, Map<String, Double> ind) {
		double ema9 = ind.get("ema9");
		double ema21 = ind.get("ema21");
		double ema50 = ind.get("ema50");
		boolean allEmasAligned = ema9 > ema21 && ema21 > ema50 && aboveEma200;
		boolean uptrend = indexTrend == IndexTrend.UPTREND;
		boolean goodVolume = volRatio >= 2.0;
		boolean idealRsi = rsi >= 45 && rsi <= 65;
		boolean freshCross = macdCross == MacdCrossState.FRESH;
		boolean strongCandle = IndicatorService.isStrongPattern(ind.get("candlePattern"));
		double depth = ind.get("macdDepth");
		boolean deepCross = depth >= 0.02;
		int aFlags = 0;
		if (uptrend)
			aFlags++;
		if (allEmasAligned)
			aFlags++;
		if (goodVolume)
			aFlags++;
		if (idealRsi)
			aFlags++;
		if (freshCross)
			aFlags++;
		if (strongCandle)
			aFlags++;
		if (deepCross)
			aFlags++;
		if (score >= CONF_A_MIN && aFlags >= 6)
			return "A";
		if (score >= CONF_B_MIN && aFlags >= 4)
			return "B";
		return "C";
	}

	private EntryPlan calculateEntry(Market market, DailyBar today, DailyBar yesterday, DailyBar twoDaysAgo,
			Map<String, Double> ind, String tradeType) {
		double close = today.close(), atr = ind.get("atr");
		double ema9 = ind.get("ema9"), ema21 = ind.get("ema21");
		double scalpEntry = market.tick(close);
		double scalpSL = market.tick(today.low() - atr * 0.3);
		if (scalpSL >= scalpEntry)
			scalpSL = market.tick(scalpEntry - atr * 0.8);
		double scalpRisk = scalpEntry - scalpSL;
		double scalpTP1 = market.tick(scalpEntry + scalpRisk * 1.0);
		double scalpTP2 = market.tick(scalpEntry + scalpRisk * 1.5);
		double scalpRR = scalpRisk > 0 ? Math.round((scalpTP2 - scalpEntry) / scalpRisk * 10) / 10.0 : 0;
		double entryFib = today.high() - (today.high() - today.low()) * 0.382;
		double emaSupport = (ema9 < close && ema9 > ema21) ? ema9 : (ema21 < close) ? ema21 : ema9;
		double entryDip = close - (atr * 0.35);
		double ideal = Math.max(entryFib, Math.max(emaSupport, entryDip));
		if (ideal >= close)
			ideal = close * 0.995;
		if (ideal < close * 0.97)
			ideal = close * 0.99;
		ideal = market.tick(ideal);
		double max = market.tick(close * 1.015);
		double recentLow = Math.min(today.low(), Math.min(yesterday.low(), twoDaysAgo.low()));
		double sl = market.tick(Math.min(ideal - atr * 1.5, recentLow * 0.992));
		if (ideal - sl > ideal * 0.10)
			sl = market.tick(ideal - ideal * 0.10);
		if (sl >= ideal)
			sl = market.tick(ideal - atr);
		double risk = ideal - sl;
		double bbU = ind.get("bbUpper");
		double tp1Raw = ideal + risk * 1.5;
		double tp1Cap = (bbU > tp1Raw) ? tp1Raw : (bbU > ideal + risk * MIN_TP1_RR) ? bbU * 0.985 : tp1Raw;
		double tp1 = market.tick(tp1Cap);
		double tp2 = market.tick(ideal + risk * 2.5);
		double tp3 = market.tick(ideal + risk * 4.0);
		if (tp1 <= ideal)
			tp1 = market.tick(ideal + risk * MIN_TP1_RR);
		if (tp2 <= tp1)
			tp2 = market.tick(tp1 + risk);
		if (tp3 <= tp2)
			tp3 = market.tick(tp2 + risk);
		double rrr = risk > 0 ? Math.round((tp2 - ideal) / risk * 10.0) / 10.0 : 0;
		return new EntryPlan(scalpEntry, scalpSL, scalpTP1, scalpRR, ideal, max, sl, tp1, tp2, tp3, rrr);
	}

	private EntryPlan fixEntryPlan(Market market, EntryPlan e, double risk, String tradeType) {
		double tp1 = market.tick(e.ideal() + risk * MIN_TP1_RR);
		double tp2 = market.tick(e.ideal() + risk * 2.5);
		double tp3 = market.tick(e.ideal() + risk * 4.0);
		double rrr = Math.round((tp2 - e.ideal()) / risk * 10.0) / 10.0;
		return new EntryPlan(e.scalpEntry(), e.scalpSL(), e.scalpTP1(), e.scalpRR(), e.ideal(), e.max(), e.sl(), tp1,
				tp2, tp3, rrr);
	}

	private LocalDate nextTradingDay(Market market, LocalDate from) {
		if (market == Market.MY)
			return calendarService.nextTradingDay(from);
		LocalDate d = from.plusDays(1);
		while (d.getDayOfWeek().getValue() >= 6)
			d = d.plusDays(1);
		return d;
	}

	private ScanResult buildTransient(Market market, String code, DailyBar today, Map<String, Double> ind,
			String reason) {
		LocalDate now = LocalDate.now(market.zoneId);
		return ScanResult.builder().market(market).stockCode(code.toUpperCase()).stockName(today.name())
				.scannedAt(LocalDateTime.now(market.zoneId)).scanDate(now).tradeDate(nextTradingDay(market, now))
				.decision("IGNORE").confidence("—").tradeType("—").score(0).closePrice(today.close())
				.highPrice(today.high()).lowPrice(today.low()).volume(today.volume())
				.volumeRatio(ind.getOrDefault("volumeRatio", 0.0)).volumeValue(ind.getOrDefault("volumeValue", 0.0))
				.rsi(ind.getOrDefault("rsi", 0.0)).macdHistogram(ind.getOrDefault("macdHist", 0.0))
				.macdDepth(ind.getOrDefault("macdDepth", 0.0)).ema9(ind.getOrDefault("ema9", 0.0))
				.ema21(ind.getOrDefault("ema21", 0.0)).ema50(ind.getOrDefault("ema50", 0.0))
				.ema200(ind.getOrDefault("ema200", 0.0)).adx(ind.getOrDefault("adx", 0.0))
				.atr(ind.getOrDefault("atr", 0.0)).atrPct(ind.getOrDefault("atrPct", 0.0)).reasons("").warnings(reason)
				.build();
	}

	private void logIndicators(Market market, String code, DailyBar today, Map<String, Double> ind) {
		String label = market.fullTicker(code);
		int ema200b = ind.get("ema200Bars").intValue();
		log.info("[{}] EOD  : {}  O={} H={} L={} C={} | Candle: {}", label, today.date(),
				market.formatPrice(today.open()), market.formatPrice(today.high()), market.formatPrice(today.low()),
				market.formatPrice(today.close()), IndicatorService.patternName(ind.get("candlePattern")));
		log.info("[{}] Vol  : {} ({}x avg) | Value: {} | 52wk: {}% of range", label, fVol(today.volume()),
				f2(ind.get("volumeRatio")), market.formatPrice(ind.get("volumeValue")), f0(ind.get("yearPos52") * 100));
		log.info("[{}] RSI  : {} {} | Slope: +{}/bar", label, f2(ind.get("rsi")), rsiLabel(ind.get("rsi")),
				f2(ind.get("rsiSlope")));
		log.info("[{}] MACD : hist={} prev={} prev2={} | depth={} | {}", label, f4(ind.get("macdHist")),
				f4(ind.get("macdHistPrev")), f4(ind.get("macdHistPrev2")), f4(ind.get("macdDepth")),
				macdLabel(ind.get("macdHist"), ind.get("macdHistPrev"), ind.get("macdHistPrev2")));
		log.info("[{}] EMA  : 9={} 21={} 50={} {}{}={} | {}", label, market.formatPrice(ind.get("ema9")),
				market.formatPrice(ind.get("ema21")), market.formatPrice(ind.get("ema50")), ema200b < 200 ? "~" : "",
				ema200b, market.formatPrice(ind.get("ema200")),
				emaLabel(ind.get("ema9"), ind.get("ema21"), ind.get("ema50"), today.close(), ind.get("ema200")));
		log.info("[{}] BB   : U={} M={} L={} Pct={}% Width={}% | ADX={} | Stoch K={} D={}", label,
				market.formatPrice(ind.get("bbUpper")), market.formatPrice(ind.get("bbMid")),
				market.formatPrice(ind.get("bbLower")), f1(ind.get("bbPct") * 100), f2(ind.get("bbWidth")),
				f2(ind.get("adx")), f2(ind.get("stochK")), f2(ind.get("stochD")));
	}

	private void logScoring(String code, ScoringResult sc) {
		log.info("[{}] SCORE: {} (v2 — BUY requires {}+)", code, f1(sc.score()), (int) MIN_SCORE_FOR_BUY);
		if (!sc.positives().isEmpty())
			log.info("[{}] ✅ {}", code, String.join(" | ", sc.positives()));
		if (!sc.negatives().isEmpty())
			log.info("[{}] ⚠  {}", code, String.join(" | ", sc.negatives()));
	}

	private void logDecision(Market market, ScanResult r, LocalDate tradeDate) {
		log.info("");
		if ("BUY".equals(r.getDecision())) {
			double entry = r.getEntryPrice(), sl = r.getStopLoss(), risk = entry - sl;
			String conf = r.getConfidence();
			String confIcon = switch (conf) {
			case "A" -> "🏆";
			case "B" -> "⭐";
			default -> "✔";
			};
			log.info("╔══════════════════════════════════════════════════════════════╗");
			log.info("║  {} {} {} ({}) | Score:{} | Grade:{} {}", "✅", r.getTradeType(),
					market.fullTicker(r.getStockCode()), r.getStockName(), f1(r.getScore()), conf, confIcon);
			log.info("║  Trade date: {} | Close: {} | Pattern: {}", tradeDate, market.formatPrice(r.getClosePrice()),
					r.getCandlePattern());
			log.info("║  Index: {} | 52wk position: {}%", indexService.getSummary(market),
					f0(r.getWeekHigh52() > r.getWeekLow52()
							? (r.getClosePrice() - r.getWeekLow52()) / (r.getWeekHigh52() - r.getWeekLow52()) * 100
							: 50));
			log.info("╠══════════════════════════════════════════════════════════════╣");
			if (!"SWING".equals(r.getTradeType())) {
				log.info("║  [SCALP] Entry: {} | SL: {} | TP1(1:1): {} | TP2(1:1.5): {}",
						market.formatPrice(r.getScalpEntry()), market.formatPrice(r.getScalpSL()),
						market.formatPrice(r.getScalpTP1()),
						market.formatPrice(r.getScalpEntry() + (r.getScalpEntry() - r.getScalpSL()) * 1.5));
				log.info("║  [SCALP] Hold: same day / next morning open");
			}
			if (!"SCALP".equals(r.getTradeType())) {
				log.info("║  [SWING] Entry: {} — {} (limit order, skip if opens above max)", market.formatPrice(entry),
						market.formatPrice(r.getEntryMax()));
				log.info("║  [SWING] SL  ⛔: {} (risk: {})", market.formatPrice(sl), market.formatRisk(risk));
				log.info("║  [SWING] TP1(40%): {} R:R 1:{}", market.formatPrice(r.getTargetTP1()),
						f1((r.getTargetTP1() - entry) / risk));
				log.info("║  [SWING] TP2(40%): {} R:R 1:{}", market.formatPrice(r.getTargetTP2()),
						f1((r.getTargetTP2() - entry) / risk));
				log.info("║  [SWING] TP3(20%): {} R:R 1:{}", market.formatPrice(r.getTargetTP3()),
						f1((r.getTargetTP3() - entry) / risk));
				log.info("║  [SWING] After TP1 hit → move SL to breakeven");
			}
			log.info("║  Vol: {}x | RSI: {} | ADX: {} | MACD depth: {}", f2(r.getVolumeRatio()), f1(r.getRsi()),
					f1(r.getAdx()), f4(r.getMacdDepth()));
			log.info("╚══════════════════════════════════════════════════════════════╝");
		} else if ("WATCH".equals(r.getDecision())) {
			log.info("┌─ 👀 WATCH {} | Score:{} | {} | Vol:{}x", market.fullTicker(r.getStockCode()), f1(r.getScore()),
					market.formatPrice(r.getClosePrice()), f2(r.getVolumeRatio()));
			log.info("└─ {}", r.getWarnings());
		} else {
			log.info("⛔ IGNORE {} — {}", market.fullTicker(r.getStockCode()), r.getWarnings());
		}
		log.info("");
	}

	private String rsiLabel(double rsi) {
		if (rsi >= RSI_HARD_MAX)
			return "🚫 BLOCKED";
		if (rsi > 68)
			return "⚠ Elevated";
		if (rsi >= 50)
			return "✅ Momentum";
		if (rsi >= 40)
			return "⚠ Below 50";
		if (rsi < 30)
			return "💡 Oversold";
		return "⚠ Weak";
	}

	private String macdLabel(double hist, double prev, double prev2) {
		if (prev <= 0 && hist > 0)
			return "🔥 FRESH GOLDEN CROSS";
		if (prev2 <= 0 && prev > 0 && hist > prev)
			return "✅ CROSS CONFIRMED";
		if (hist > 0 && hist > prev)
			return "⚡ Positive growing";
		if (hist > 0)
			return "⚡ Positive flat";
		if (hist < 0 && hist > prev)
			return "⚠ Negative recovering";
		return "❌ Negative declining";
	}

	private String emaLabel(double e9, double e21, double e50, double close, double e200) {
		if (e9 > e21 && e21 > e50 && close > e200)
			return "✅ 9>21>50>200 PERFECT";
		if (e9 > e21 && e21 > e50)
			return "✅ 9>21>50 (below 200)";
		if (e9 > e21)
			return "⚡ 9>21 only";
		if (e9 < e21 && e21 < e50)
			return "❌ Downtrend";
		return "⚠ Mixed";
	}

	private String f0(double v) {
		return String.format("%.0f", v);
	}

	private String f1(double v) {
		return String.format("%.1f", v);
	}

	private String f2(double v) {
		return String.format("%.2f", v);
	}

	private String f4(double v) {
		return String.format("%.4f", v);
	}

	private String fVol(long v) {
		if (v >= 1_000_000)
			return String.format("%.2fM", v / 1_000_000.0);
		if (v >= 1_000)
			return String.format("%.1fK", v / 1_000.0);
		return String.valueOf(v);
	}

	private record ScoringResult(double score, List<String> positives, List<String> negatives) {
	}

	private record EntryPlan(double scalpEntry, double scalpSL, double scalpTP1, double scalpRR, double ideal,
			double max, double sl, double tp1, double tp2, double tp3, double rrr) {
		static final EntryPlan ZERO = new EntryPlan(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
	}
}