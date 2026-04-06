package com.screener.service.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.StochasticOscillatorDIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.num.DoubleNumFactory;
import org.ta4j.core.num.Num;

import com.screener.service.enums.Market;
import com.screener.service.model.DailyBar;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class IndicatorService {
	private static final int MIN_BARS = 52;
	public static final double PAT_MORNING_STAR = 4;
	public static final double PAT_BULL_ENGULFING = 3;
	public static final double PAT_HAMMER = 2;
	public static final double PAT_STRONG_BULL = 1;
	public static final double PAT_NONE = 0;
	public static final double PAT_BEARISH = -1;

	public Map<String, Double> calculate(List<DailyBar> bars, Market market) {
		if (bars.size() < MIN_BARS) {
			log.warn("[{}] Insufficient bars ({}, need {}+)", market, bars.size(), MIN_BARS);
			return Map.of();
		}
		BarSeries series = buildSeries(bars, market);
		int i = series.getEndIndex();
		Map<String, Double> ind = new HashMap<>(55);
		ClosePriceIndicator close = new ClosePriceIndicator(series);
		RSIIndicator rsiInd = new RSIIndicator(close, 14);
		ind.put("rsi", val(rsiInd, i));
		ind.put("rsiPrev", val(rsiInd, i - 1));
		double rsiNow = val(rsiInd, i);
		double rsi5ago = val(rsiInd, Math.max(0, i - 5));
		ind.put("rsiSlope", (rsiNow - rsi5ago) / 5.0);
		MACDIndicator macd = new MACDIndicator(close, 12, 26);
		EMAIndicator macdSig = new EMAIndicator(macd, 9);
		double h0 = val(macd, i) - val(macdSig, i);
		double h1 = val(macd, i - 1) - val(macdSig, i - 1);
		double h2 = val(macd, i - 2) - val(macdSig, i - 2);
		double h3 = val(macd, i - 3) - val(macdSig, i - 3);
		double h4 = val(macd, i - 4) - val(macdSig, i - 4);
		double h5 = val(macd, i - 5) - val(macdSig, i - 5);
		ind.put("macd", val(macd, i));
		ind.put("macdSignal", val(macdSig, i));
		ind.put("macdHist", h0);
		ind.put("macdHistPrev", h1);
		ind.put("macdHistPrev2", h2);
		ind.put("macdHistH3", h3);
		ind.put("macdHistH4", h4);
		ind.put("macdHistH5", h5);
		ind.put("macdSlope", h0 - h2);
		double macdDepth = 0;
		int depthLookback = Math.min(20, i - 1);
		for (int j = i - depthLookback; j < i; j++) {
			double h = val(macd, j) - val(macdSig, j);
			if (h < macdDepth)
				macdDepth = h;
		}
		ind.put("macdDepth", Math.abs(macdDepth));
		ind.put("ema9", val(new EMAIndicator(close, 9), i));
		ind.put("ema21", val(new EMAIndicator(close, 21), i));
		ind.put("ema50", val(new EMAIndicator(close, 50), i));
		int ema200Period = bars.size() >= 200 ? 200 : Math.min(100, bars.size() - 1);
		ind.put("ema200", val(new EMAIndicator(close, ema200Period), i));
		ind.put("ema200Bars", (double) ema200Period);
		SMAIndicator sma20 = new SMAIndicator(close, 20);
		StandardDeviationIndicator std = new StandardDeviationIndicator(close, 20);
		BollingerBandsMiddleIndicator bbMid = new BollingerBandsMiddleIndicator(sma20);
		double bbU = val(new BollingerBandsUpperIndicator(bbMid, std), i);
		double bbL = val(new BollingerBandsLowerIndicator(bbMid, std), i);
		double bbM = val(bbMid, i);
		double closePrice = bars.get(bars.size() - 1).close();
		ind.put("bbUpper", bbU);
		ind.put("bbMid", bbM);
		ind.put("bbLower", bbL);
		ind.put("bbWidth", bbM > 0 ? (bbU - bbL) / bbM * 100 : 0);
		ind.put("bbPct", (bbU - bbL) > 0 ? (closePrice - bbL) / (bbU - bbL) : 0.5);
		double atr = val(new ATRIndicator(series, 14), i);
		ind.put("atr", atr);
		ind.put("atrPct", closePrice > 0 ? atr / closePrice * 100 : 0);
		StochasticOscillatorKIndicator stochK = new StochasticOscillatorKIndicator(series, 14);
		StochasticOscillatorDIndicator stochD = new StochasticOscillatorDIndicator(stochK);
		ind.put("stochK", val(stochK, i));
		ind.put("stochD", val(stochD, i));
		ind.put("stochKPrev", val(stochK, i - 1));
		ind.put("stochDPrev", val(stochD, i - 1));
		ind.put("adx", val(new ADXIndicator(series, 14), i));
		int volLookback = Math.min(20, bars.size() - 1);
		double avgVol = bars.subList(bars.size() - 1 - volLookback, bars.size() - 1).stream()
				.mapToLong(DailyBar::volume).average().orElse(1.0);
		double todayVol = bars.get(bars.size() - 1).volume();
		ind.put("volumeRatio", avgVol > 0 ? todayVol / avgVol : 1.0);
		ind.put("avgVolume", avgVol);
		ind.put("volumeValue", closePrice * todayVol);
		double obvNet = 0;
		for (int j = bars.size() - 5; j < bars.size(); j++) {
			DailyBar b = bars.get(j);
			DailyBar prev = bars.get(j - 1);
			if (b.close() > prev.close())
				obvNet += b.volume();
			else if (b.close() < prev.close())
				obvNet -= b.volume();
		}
		ind.put("obvTrend", obvNet);
		int lookback52 = Math.min(252, bars.size());
		double high52 = bars.subList(bars.size() - lookback52, bars.size()).stream().mapToDouble(DailyBar::high).max()
				.orElse(closePrice);
		double low52 = bars.subList(bars.size() - lookback52, bars.size()).stream().mapToDouble(DailyBar::low).min()
				.orElse(closePrice);
		ind.put("weekHigh52", high52);
		ind.put("weekLow52", low52);
		ind.put("yearPos52", (high52 > low52) ? (closePrice - low52) / (high52 - low52) : 0.5);
		if (bars.size() >= 3) {
			DailyBar d0 = bars.get(bars.size() - 1);
			DailyBar d1 = bars.get(bars.size() - 2);
			DailyBar d2 = bars.get(bars.size() - 3);
			ind.put("candlePattern", detectCandlePattern(d0, d1, d2));
		} else {
			ind.put("candlePattern", PAT_NONE);
		}
		return ind;
	}

	private double detectCandlePattern(DailyBar d0, DailyBar d1, DailyBar d2) {
		double range0 = d0.high() - d0.low();
		double body0 = d0.close() - d0.open();
		double range1 = d1.high() - d1.low();
		double body1 = d1.close() - d1.open();
		double range2 = d2.high() - d2.low();
		if (range0 <= 0)
			return PAT_NONE;
		boolean morningStar = body2IsBearish(body2(d2), range2) && isSmallBody(body1, range1) && body0 > 0
				&& Math.abs(body0) / range0 >= 0.50 && d0.close() > (d2.open() + d2.close()) / 2;
		if (morningStar)
			return PAT_MORNING_STAR;
		boolean bullEngulfing = body1 < 0 && body0 > 0 && d0.open() < d1.close() && d0.close() > d1.open()
				&& Math.abs(body0) > Math.abs(body1) * 0.90;
		if (bullEngulfing)
			return PAT_BULL_ENGULFING;
		double lowerShadow = d0.open() > d0.close() ? d0.close() - d0.low() : d0.open() - d0.low();
		double upperShadow = d0.high() - Math.max(d0.open(), d0.close());
		double bodySize = Math.abs(body0);
		boolean hammer = bodySize > 0 && lowerShadow >= bodySize * 2.0 && upperShadow <= bodySize * 0.4
				&& d0.low() < d1.low() && d0.close() > d0.open() * 0.98;
		if (hammer)
			return PAT_HAMMER;
		boolean strongBull = body0 > 0 && (body0 / range0) >= 0.60 && (d0.close() - d0.low()) / range0 >= 0.75;
		if (strongBull)
			return PAT_STRONG_BULL;
		double upShadow = d0.high() - Math.max(d0.open(), d0.close());
		boolean shootingStar = bodySize > 0 && upShadow >= bodySize * 2.5 && (d0.close() - d0.low()) / range0 < 0.3;
		boolean bearEngulfing = body1 > 0 && body0 < 0 && d0.open() > d1.close() && d0.close() < d1.open();
		if (shootingStar || bearEngulfing)
			return PAT_BEARISH;
		return PAT_NONE;
	}

	private double body2(DailyBar d) {
		return d.close() - d.open();
	}

	private boolean body2IsBearish(double body, double range) {
		return body < 0 && Math.abs(body) / Math.max(range, 0.0001) >= 0.50;
	}

	private boolean isSmallBody(double body, double range) {
		return range > 0 && Math.abs(body) / range <= 0.35;
	}

	private BarSeries buildSeries(List<DailyBar> bars, Market market) {
		LocalTime closeTime = market == Market.MY ? LocalTime.of(17, 0) : LocalTime.of(16, 0);
		BarSeries series = new BaseBarSeriesBuilder().withName(market.name().toLowerCase())
				.withNumFactory(DoubleNumFactory.getInstance()).build();
		for (DailyBar b : bars) {
			Instant endTime = b.date().atTime(closeTime).atZone(market.zoneId).toInstant();
			series.addBar(series.barBuilder().timePeriod(Duration.ofDays(1)).endTime(endTime).openPrice(b.open())
					.highPrice(b.high()).lowPrice(b.low()).closePrice(b.close()).volume(b.volume()).build());
		}
		return series;
	}

	private double val(Indicator<?> indicator, int idx) {
		try {
			Object v = indicator.getValue(idx);
			if (v instanceof Num n)
				return Double.isFinite(n.doubleValue()) ? n.doubleValue() : 0.0;
			return 0.0;
		} catch (Exception e) {
			return 0.0;
		}
	}

	public static String patternName(double code) {
		return switch ((int) Math.round(code)) {
		case 4 -> "🌟 MORNING STAR";
		case 3 -> "🔥 BULL ENGULFING";
		case 2 -> "🔨 HAMMER";
		case 1 -> "💪 STRONG BULL";
		case -1 -> "🚩 BEARISH REVERSAL";
		default -> "—";
		};
	}

	public static boolean isBullishPattern(double code) {
		return code >= PAT_STRONG_BULL;
	}

	public static boolean isStrongPattern(double code) {
		return code >= PAT_HAMMER;
	}
}