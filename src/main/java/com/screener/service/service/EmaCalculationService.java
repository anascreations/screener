package com.screener.service.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.screener.service.dto.EmaCalculationRequest;

@Service
public class EmaCalculationService {

	public Map<String, Object> emaCalculate(EmaCalculationRequest req) {
		Map<String, Object> result = new LinkedHashMap<>();

		try {
			double price = req.getMarketPrice();
			double ema8 = req.getEma8();
			double ema21 = req.getEma21();
			double ema55 = req.getEma55();
			String tf = req.getTimeframe() != null ? req.getTimeframe() : "DAILY";

			double pctAboveEMA8 = pct(price, ema8);
			double pctAboveEMA21 = pct(price, ema21);
			double pctAboveEMA55 = pct(price, ema55);
			double ema8AboveEMA21 = pct(ema8, ema21);
			double ema21AboveEMA55 = pct(ema21, ema55);

			boolean priceAboveEMA8 = price > ema8;
			boolean priceAboveEMA21 = price > ema21;
			boolean priceAboveEMA55 = price > ema55;
			boolean ema8AboveEMA21b = ema8 > ema21;
			boolean ema21AboveEMA55b = ema21 > ema55;
			boolean fullBullStack = priceAboveEMA8 && ema8AboveEMA21b && ema21AboveEMA55b;

			// ── Filter steps (ordered map = display order) ───────────────────
			Map<String, Map<String, String>> filterSteps = new LinkedHashMap<>();

			// F1 — Price vs EMA8 (momentum gate)
			Map<String, String> f1 = new LinkedHashMap<>();
			f1.put("name", "F1 — Price vs EMA8 (Momentum Gate)");
			f1.put("rule", "Price must be above EMA8 and within 10%");
			String f1Result = fmt2(pctAboveEMA8) + "% above EMA8";
			if (!priceAboveEMA8) {
				f1.put("status", "FAIL");
				f1.put("result", "Price BELOW EMA8 — " + f1Result);
			} else if (pctAboveEMA8 <= 2.0) {
				f1.put("status", "PASS — Ideal Zone");
				f1.put("result", f1Result);
			} else if (pctAboveEMA8 <= 5.0) {
				f1.put("status", "PASS — Acceptable");
				f1.put("result", f1Result);
			} else if (pctAboveEMA8 <= 10.0) {
				f1.put("status", "PASS — Stretched (reduce size)");
				f1.put("result", f1Result);
			} else {
				f1.put("status", "FAIL — Overextended (>10%)");
				f1.put("result", f1Result);
			}
			filterSteps.put("F1", f1);

			// F2 — EMA8 vs EMA21 (short-term trend direction)
			Map<String, String> f2 = new LinkedHashMap<>();
			f2.put("name", "F2 — EMA8 vs EMA21 (Short-Term Trend)");
			f2.put("rule", "EMA8 must be above EMA21");
			String f2Result = fmt2(ema8AboveEMA21) + "% gap (EMA8 vs EMA21)";
			if (ema8AboveEMA21b) {
				f2.put("status", "PASS — Bullish");
				f2.put("result", f2Result);
			} else if (Math.abs(ema8AboveEMA21) < 0.2) {
				f2.put("status", "NEUTRAL — Flat/Crossing");
				f2.put("result", f2Result);
			} else {
				f2.put("status", "FAIL — EMA8 below EMA21");
				f2.put("result", f2Result);
			}
			filterSteps.put("F2", f2);

			// F3 — EMA21 vs EMA55 (trend maturity)
			Map<String, String> f3 = new LinkedHashMap<>();
			f3.put("name", "F3 — EMA21 vs EMA55 (Trend Maturity)");
			f3.put("rule", "EMA21 > EMA55; gap 1–5% is optimal");
			String trendGrade = calcTrendGrade(ema21AboveEMA55);
			String trendEmoji = trendEmoji(trendGrade);
			String f3Result = fmt2(ema21AboveEMA55) + "% gap → Grade " + trendGrade;
			boolean f3pass = ema21AboveEMA55b && !trendGrade.equals("X");
			f3.put("status", f3pass ? "PASS — " + trendGrade
					: (ema21AboveEMA55b ? "PASS — Exhausted (reduce)" : "FAIL — Bearish"));
			f3.put("result", f3Result);
			filterSteps.put("F3", f3);

			// F4 — KDJ
			String kdjZone = null, kdjEmoji = null, kdjMeaning = null;
			if (req.hasKdj()) {
				double k = req.getKdjK(), d = req.getKdjD(), j = req.getKdjJ();
				Map<String, String> f4 = new LinkedHashMap<>();
				f4.put("name", "F4 — KDJ Oscillator");
				f4.put("rule", "K > D required; J < 80 preferred");
				String[] kdjInfo = classifyKdj(k, d, j);
				kdjZone = kdjInfo[0];
				kdjEmoji = kdjInfo[1];
				kdjMeaning = kdjInfo[2];
				boolean kdjPass = k > d;
				f4.put("status", kdjPass ? "PASS — " + kdjZone : "FAIL — " + kdjZone);
				f4.put("result", "K=" + fmt3(k) + " D=" + fmt3(d) + " J=" + fmt3(j));
				filterSteps.put("F4", f4);
				result.put("kdjZone", kdjZone);
				result.put("kdjEmoji", kdjEmoji);
				result.put("kdjMeaning", kdjMeaning);
			}

			// F5 — MACD
			String macdZone = null, macdEmoji = null, macdMeaning = null;
			if (req.hasMacd()) {
				double dif = req.getMacdDif(), dea = req.getMacdDea();
				Map<String, String> f5 = new LinkedHashMap<>();
				f5.put("name", "F5 — MACD (DIF vs DEA)");
				f5.put("rule", "DIF > DEA required");
				String[] macdInfo = classifyMacd(dif, dea);
				macdZone = macdInfo[0];
				macdEmoji = macdInfo[1];
				macdMeaning = macdInfo[2];
				boolean macdPass = dif > dea;
				f5.put("status", macdPass ? "PASS — " + macdZone : "FAIL — " + macdZone);
				f5.put("result", "DIF=" + fmt3(dif) + " DEA=" + fmt3(dea));
				filterSteps.put("F5", f5);
				result.put("macdZone", macdZone);
				result.put("macdEmoji", macdEmoji);
				result.put("macdMeaning", macdMeaning);
			}

			// F5b — Volume
			String volumeZone = null;
			if (req.hasVolume()) {
				double vr = req.getVolumeRatio();
				Map<String, String> f5b = new LinkedHashMap<>();
				f5b.put("name", "F5b — Volume Ratio");
				f5b.put("rule", ">= 1.5x average preferred");
				volumeZone = classifyVolume(vr);
				boolean vPass = vr >= 1.2;
				f5b.put("status", vPass ? "PASS — " + volumeZone : "NEUTRAL — Below average volume");
				f5b.put("result", "Ratio " + fmt2(vr) + "x");
				filterSteps.put("F5b", f5b);
				result.put("volumeZone", volumeZone);
			}

			result.put("filterSteps", filterSteps);

			// ── Scoring & Decision ───────────────────────────────────────────
			int score = 0;
			if (priceAboveEMA8 && pctAboveEMA8 <= 10)
				score += 25;
			if (ema8AboveEMA21b)
				score += 25;
			if (ema21AboveEMA55b)
				score += 20;
			if (req.hasKdj() && req.getKdjK() > req.getKdjD())
				score += 15;
			if (req.hasMacd() && req.getMacdDif() > req.getMacdDea())
				score += 10;
			if (req.hasVolume() && req.getVolumeRatio() >= 1.5)
				score += 5;

			// Stack grade (based on alignment quality)
			String stackGrade, stackEmoji;
			if (fullBullStack && pctAboveEMA8 <= 2 && ema21AboveEMA55 <= 5) {
				stackGrade = "S++";
				stackEmoji = "🚀";
			} else if (fullBullStack && pctAboveEMA8 <= 5) {
				stackGrade = "A";
				stackEmoji = "✅";
			} else if (priceAboveEMA8 && ema8AboveEMA21b) {
				stackGrade = "B";
				stackEmoji = "⚠️";
			} else if (priceAboveEMA21) {
				stackGrade = "C";
				stackEmoji = "🟡";
			} else {
				stackGrade = "X";
				stackEmoji = "🛑";
			}

			// Hard SKIP conditions
			boolean hardSkip = !priceAboveEMA8 && pctAboveEMA8 < -2.0 || (!ema8AboveEMA21b && !ema21AboveEMA55b);

			String decision, riskLevel, positionSize;
			if (hardSkip || stackGrade.equals("X")) {
				decision = "SKIP";
				riskLevel = "High Risk";
				positionSize = "0% — SKIP";
			} else if (score >= 80) {
				decision = "PROCEED";
				riskLevel = "Low Risk";
				positionSize = "100%";
			} else if (score >= 60) {
				decision = "PROCEED";
				riskLevel = "Medium Risk";
				positionSize = "75%";
			} else if (score >= 45) {
				decision = "PROCEED";
				riskLevel = "Medium Risk";
				positionSize = "50%";
			} else {
				decision = "SKIP";
				riskLevel = "High Risk";
				positionSize = "0% — SKIP";
			}

			result.put("decision", decision);
			result.put("riskLevel", riskLevel);
			result.put("positionSize", positionSize);
			result.put("stackGrade", stackGrade);
			result.put("stackEmoji", stackEmoji);
			result.put("signalScore", score);
			result.put("trendGrade", trendGrade);

			// ── Advice text ──────────────────────────────────────────────────
			result.put("advice", buildAdvice(decision, stackGrade, trendGrade, pctAboveEMA8, ema8AboveEMA21b,
					ema21AboveEMA55b, positionSize));
			if (decision.equals("SKIP")) {
				result.put("reason", buildSkipReason(priceAboveEMA8, ema8AboveEMA21b, ema21AboveEMA55b, pctAboveEMA8));
			}

			// ── RSI zone ────────────────────────────────────────────────────
			if (req.hasRsi()) {
				result.put("rsiZone", classifyRsi(req.getRsi14()));
			}

			// ── Momentum zone (F1) ───────────────────────────────────────────
			result.put("momentumZone", momentumZone(pctAboveEMA8, priceAboveEMA8));

			// ── EMA Alignment block ──────────────────────────────────────────
			Map<String, Object> emaAlignment = new LinkedHashMap<>();
			emaAlignment.put("priceAboveEMA8", priceAboveEMA8);
			emaAlignment.put("priceAboveEMA21", priceAboveEMA21);
			emaAlignment.put("priceAboveEMA55", priceAboveEMA55);
			emaAlignment.put("ema8AboveEMA21", ema8AboveEMA21b);
			emaAlignment.put("ema21AboveEMA55", ema21AboveEMA55b);
			emaAlignment.put("fullBullStack", fullBullStack);
			emaAlignment.put("trend",
					buildTrendDescription(priceAboveEMA8, ema8AboveEMA21b, ema21AboveEMA55b, fullBullStack));
			result.put("emaAlignment", emaAlignment);

			// ── Calculation block ────────────────────────────────────────────
			Map<String, Object> calc = new LinkedHashMap<>();
			calc.put("pctAboveEMA8", fmt2(pctAboveEMA8) + "%");
			calc.put("pctAboveEMA21", fmt2(pctAboveEMA21) + "%");
			calc.put("pctAboveEMA55", fmt2(pctAboveEMA55) + "%");
			calc.put("ema8AboveEMA21", fmt2(ema8AboveEMA21) + "%");
			calc.put("ema21AboveEMA55", fmt2(ema21AboveEMA55) + "%");
			calc.put("momentumResult", buildMomentumResult(pctAboveEMA8, priceAboveEMA8));
			calc.put("thresholds", "Zones: ≤2% Ideal · 2–5% OK · 5–10% Stretched · >10% Skip");
			result.put("calculation", calc);

			// ── Trend Maturity block (EMA21 vs EMA55) ───────────────────────
			Map<String, Object> trendMaturity = new LinkedHashMap<>();
			trendMaturity.put("result", fmt2(ema21AboveEMA55) + "%");
			trendMaturity.put("grade", trendGrade);
			trendMaturity.put("emoji", trendEmoji);
			trendMaturity.put("meaning", trendGradeMeaning(trendGrade));
			result.put("trendMaturity", trendMaturity);

			// ── Hold duration (timeframe-based) ─────────────────────────────
			Map<String, Object> holdDays = holdDuration(tf, stackGrade);
			result.put("holdDays", holdDays);

			// ── Confluences & Warnings ───────────────────────────────────────
			List<String> confluences = new ArrayList<>();
			List<String> warnings = new ArrayList<>();

			if (fullBullStack)
				confluences.add("✅ Full EMA Bull Stack (Price > EMA8 > EMA21 > EMA55)");
			if (pctAboveEMA8 <= 2)
				confluences.add("🎯 Price at ideal EMA8 zone (≤2%)");
			if (ema21AboveEMA55 >= 1 && ema21AboveEMA55 <= 3)
				confluences.add("🚀 Fresh trend (S++ maturity zone 1–3%)");
			if (req.hasKdj() && req.getKdjK() > req.getKdjD() && req.getKdjJ() > 50)
				confluences.add("💪 KDJ bullish with strong J");
			if (req.hasMacd() && req.getMacdDif() > req.getMacdDea() && req.getMacdDea() > 0)
				confluences.add("📈 MACD DIF > DEA > 0 (strong bull)");
			if (req.hasVolume() && req.getVolumeRatio() >= 2.0)
				confluences.add("🔥 Volume surge ≥ 2.0x");
			if (req.hasRsi() && req.getRsi14() >= 50 && req.getRsi14() <= 70)
				confluences.add("✅ RSI in healthy range (50–70)");

			if (!priceAboveEMA8)
				warnings.add("⛔ Price below EMA8 — no momentum");
			if (!ema8AboveEMA21b)
				warnings.add("⚠️ EMA8 below EMA21 — short-term trend broken");
			if (!ema21AboveEMA55b)
				warnings.add("⛔ EMA21 below EMA55 — bearish structure");
			if (pctAboveEMA8 > 10)
				warnings.add("🚫 Price >10% above EMA8 — do not chase");
			if (ema21AboveEMA55 > 10)
				warnings.add("🛑 EMA21 vs EMA55 exhausted (>10%) — late stage trend");
			if (req.hasKdj() && req.getKdjJ() > 80)
				warnings.add("🟡 KDJ J > 80 — overbought signal");
			if (req.hasKdj() && req.getKdjK() < req.getKdjD())
				warnings.add("🔴 KDJ K < D — bearish momentum");
			if (req.hasVolume() && req.getVolumeRatio() < 1.0)
				warnings.add("📉 Volume below average — weak confirmation");
			if (req.hasRsi() && req.getRsi14() > 75)
				warnings.add("🟡 RSI overbought (>75)");
			if (req.hasRsi() && req.getRsi14() < 40)
				warnings.add("🔴 RSI bearish (<40)");

			result.put("confluences", confluences);
			result.put("warnings", warnings);

			// ── Trade Plan ───────────────────────────────────────────────────
			if (!decision.equals("SKIP")) {
				result.put("tradePlan", buildTradePlan(price, ema8, ema21, req, tf, stackGrade));
			}

			Map<String, Object> input = new LinkedHashMap<>();
			input.put("marketPrice", fmt4(price));
			input.put("ema8", fmt4(ema8));
			input.put("ema21", fmt4(ema21));
			input.put("ema55", fmt4(ema55));
			input.put("timeframe", tf);
			input.put("holdDuration", holdDays.get("label"));
			if (req.hasKdj())
				input.put("kdj",
						"K=" + fmt3(req.getKdjK()) + " D=" + fmt3(req.getKdjD()) + " J=" + fmt3(req.getKdjJ()));
			if (req.hasMacd())
				input.put("macd", "DIF=" + fmt3(req.getMacdDif()) + " DEA=" + fmt3(req.getMacdDea()));
			result.put("input", input);

		} catch (Exception e) {
			result.put("error", "Calculation failed: " + e.getMessage());
		}

		return result;
	}


	private String calcTrendGrade(double pctGap) {
		if (pctGap < 0)
			return "X"; // bearish
		if (pctGap < 1.0)
			return "FLAT";
		if (pctGap < 3.0)
			return "S++";
		if (pctGap < 5.0)
			return "A";
		if (pctGap < 8.0)
			return "B";
		if (pctGap < 10.0)
			return "C";
		return "X";
	}

	private String trendEmoji(String grade) {
		return switch (grade) {
		case "S++" -> "🚀";
		case "A" -> "✅";
		case "B" -> "⚠️";
		case "C" -> "🟡";
		case "FLAT" -> "➡️";
		default -> "🛑";
		};
	}

	private String trendGradeMeaning(String grade) {
		return switch (grade) {
		case "S++" -> "Fresh trend (1–3%) — maximum energy, ideal entry";
		case "A" -> "Healthy trend (3–5%) — good entry zone";
		case "B" -> "Maturing trend (5–8%) — reduce position size";
		case "C" -> "Late-stage trend (8–10%) — very tight SL only";
		case "FLAT" -> "Flat / crossing (0–1%) — wait for gap to widen";
		default -> "Exhausted (>10%) or Bearish — skip, wait for reset";
		};
	}

	private String[] classifyKdj(double k, double d, double j) {
		if (k < d) {
			if (d < 50)
				return new String[] { "Bearish (K<D<50)", "🔴", "Bearish momentum, skip" };
			return new String[] { "Bearish (K<D)", "🔴", "Bearish crossover, skip" };
		}
		if (j > 80)
			return new String[] { "Overbought (J>80)", "🟡", "Overbought — reduce size" };
		if (j < 20)
			return new String[] { "Oversold (J<20)", "💡", "Oversold bounce opportunity" };
		if (j > 50)
			return new String[] { "Bullish Strong (K>D, J>50)", "✅", "Strong bullish momentum" };
		return new String[] { "Bullish Building (K>D, J≤50)", "🟢", "Bullish momentum building" };
	}

	/** MACD: returns [zone, emoji, meaning] */
	private String[] classifyMacd(double dif, double dea) {
		if (dif > dea && dea > 0)
			return new String[] { "Strong Bull (DIF>DEA>0)", "🚀", "Both lines above zero — strongest bull" };
		if (dif > dea && dea <= 0)
			return new String[] { "Bullish Cross", "✅", "DIF crossed above DEA — early bull signal" };
		if (Math.abs(dif - dea) < 0.001)
			return new String[] { "Near Crossover", "⚠️", "DIF ≈ DEA — watch for direction" };
		if (dif < dea && dea < 0)
			return new String[] { "Strong Bear (DIF<DEA<0)", "🔻", "Both lines below zero — strongest bear" };
		return new String[] { "Bearish Cross", "🔴", "DIF below DEA — bearish momentum" };
	}

	private String classifyVolume(double vr) {
		if (vr >= 3.0)
			return "🔥 Extreme surge (≥3.0x)";
		if (vr >= 2.0)
			return "🔥 Strong surge (≥2.0x)";
		if (vr >= 1.5)
			return "📈 Surge (≥1.5x)";
		if (vr >= 1.2)
			return "🟢 Above average (≥1.2x)";
		if (vr >= 1.0)
			return "🟡 Average";
		return "📉 Below average (<1.0x)";
	}

	private String classifyRsi(double rsi) {
		if (rsi > 80)
			return "🔴 Heavily overbought (>80) — avoid entry";
		if (rsi > 70)
			return "🟡 Overbought (70–80) — reduce size";
		if (rsi >= 50)
			return "✅ Healthy bull zone (50–70)";
		if (rsi >= 40)
			return "⚠️ Neutral (40–50) — momentum fading";
		return "🔴 Bearish (<40) — skip";
	}

	private String momentumZone(double pctAboveEMA8, boolean above) {
		if (!above)
			return "🔴 Price BELOW EMA8 — no momentum";
		if (pctAboveEMA8 <= 2)
			return "🎯 Ideal Zone (≤2% above EMA8)";
		if (pctAboveEMA8 <= 5)
			return "🟢 Acceptable (2–5% above EMA8)";
		if (pctAboveEMA8 <= 10)
			return "🟡 Stretched (5–10%) — reduce size";
		return "🔴 Overextended (>10%) — do not chase";
	}

	private String buildMomentumResult(double pct, boolean above) {
		if (!above)
			return "Price below EMA8 — F1 FAIL";
		String zone = pct <= 2 ? "Ideal" : pct <= 5 ? "Acceptable" : pct <= 10 ? "Stretched" : "Overextended";
		return String.format("%.2f%% above EMA8 — %s", pct, zone);
	}

	private Map<String, Object> buildTradePlan(double price, double ema8, double ema21, EmaCalculationRequest req,
			String tf, String stackGrade) {
		Map<String, Object> tp = new LinkedHashMap<>();

		double atr = req.hasAtr() ? req.getAtr14() : price * 0.015;

		double entryIdeal = ema8;
		double entryMax = price * 1.02; 
		double stopLoss = ema21 - atr; 

		double risk = entryIdeal - stopLoss;
		double tp1 = entryIdeal + risk * 1.0;
		double tp2 = entryIdeal + risk * 2.0;
		double tp3 = entryIdeal + risk * 3.5;

		int decimals = isMy(req.getMarket()) ? 3 : 2;

		tp.put("entryIdeal", fmtD(entryIdeal, decimals));
		tp.put("entryMax", fmtD(entryMax, decimals));
		tp.put("entryNote", "Queue at EMA8 for best R:R");
		tp.put("stopLoss", fmtD(stopLoss, decimals));
		tp.put("stopLossNote", "Below EMA21 − 1 ATR (" + fmtD(atr, decimals) + ")");
		tp.put("tp1", fmtD(tp1, decimals));
		tp.put("tp2", fmtD(tp2, decimals));
		tp.put("tp3", fmtD(tp3, decimals));

		Map<String, Object> hd = holdDuration(tf, stackGrade);
		tp.put("holdDuration", hd.get("label"));
		tp.put("targetDay", hd.get("targetDays"));
		tp.put("exitRule", hd.get("exitRule"));

		return tp;
	}

	private Map<String, Object> holdDuration(String tf, String grade) {
		Map<String, Object> hd = new LinkedHashMap<>();
		switch (tf.toUpperCase()) {
		case "15M" -> {
			hd.put("label", "Intraday (2–4 hours)");
			hd.put("targetDays", "Same day");
			hd.put("exitRule", "Exit before market close. Use trailing stop after TP1.");
		}
		case "30M" -> {
			hd.put("label", "Short intraday (4–8 hours)");
			hd.put("targetDays", "1 day");
			hd.put("exitRule", "Exit by end of next session. Tighten SL after TP1.");
		}
		case "1H" -> {
			hd.put("label", "Swing (1–3 days)");
			hd.put("targetDays", "2–3");
			hd.put("exitRule", "Exit if price closes below EMA8 on 1H. Move SL to BE after TP1.");
		}
		case "4H" -> {
			hd.put("label", "Swing (3–7 days)");
			hd.put("targetDays", "5–7");
			hd.put("exitRule", "Exit if price closes below EMA21 on 4H. Trail SL using EMA8.");
		}
		case "WEEKLY" -> {
			hd.put("label", "Position (4–12 weeks)");
			hd.put("targetDays", "30–84");
			hd.put("exitRule", "Exit if weekly candle closes below EMA21. Trail using EMA55.");
		}
		default -> {
			int target = grade.equals("S++") ? 10 : grade.equals("A") ? 15 : grade.equals("B") ? 20 : 25;
			hd.put("label", "Swing (5–" + target + " days)");
			hd.put("targetDays", String.valueOf(target));
			hd.put("exitRule", "Exit if daily close below EMA21. Move SL to EMA8 after TP1 hit.");
		}
		}
		return hd;
	}

	private String buildAdvice(String decision, String stack, String trend, double pctEMA8, boolean ema8AboveEMA21,
			boolean ema21AboveEMA55, String size) {
		if (decision.equals("SKIP"))
			return null;
		String base = String.format(
				"EMA stack grade %s · Trend maturity %s · Price is %.2f%% above EMA8. "
						+ "Recommended position size: %s. Queue a limit order at EMA8 for the best risk-reward entry.",
				stack, trend, pctEMA8, size);
		if (pctEMA8 > 5)
			base += " Price is stretched — consider waiting for a pullback to EMA8 before entering.";
		return base;
	}

	private String buildSkipReason(boolean aboveEMA8, boolean ema8AboveEMA21, boolean ema21AboveEMA55, double pct) {
		if (!aboveEMA8)
			return "SKIP: Price is below EMA8. No bullish momentum. Wait for price to reclaim EMA8.";
		if (!ema8AboveEMA21)
			return "SKIP: EMA8 is below EMA21. Short-term trend is bearish. Wait for EMA8 to cross above EMA21.";
		if (!ema21AboveEMA55)
			return "SKIP: EMA21 is below EMA55. Medium-term structure is bearish. Do not trade long.";
		if (pct > 10)
			return "SKIP: Price is " + fmt2(pct)
					+ "% above EMA8 — severely overextended. Do not chase. Wait for a reset to EMA8 or EMA21.";
		return "SKIP: EMA stack conditions not met. Wait for full alignment (Price > EMA8 > EMA21 > EMA55).";
	}

	private String buildTrendDescription(boolean aboveEMA8, boolean ema8AboveEMA21, boolean ema21AboveEMA55,
			boolean fullBull) {
		if (fullBull)
			return "Full Bull Stack — Price > EMA8 > EMA21 > EMA55. All EMAs aligned bullish.";
		if (ema8AboveEMA21 && ema21AboveEMA55 && !aboveEMA8)
			return "EMA stack is bullish but price is below EMA8. Watch for reclaim.";
		if (ema8AboveEMA21 && !ema21AboveEMA55)
			return "Short-term bullish (EMA8 > EMA21) but medium trend bearish (EMA21 < EMA55). Caution.";
		if (!ema8AboveEMA21 && !ema21AboveEMA55)
			return "Full Bear Stack — EMA8 < EMA21 < EMA55. Avoid all long entries.";
		return "Mixed EMA signals — partial alignment only. Wait for full stack confirmation.";
	}

	private double pct(double value, double base) {
		if (base == 0)
			return 0;
		return (value - base) / base * 100.0;
	}

	private String fmt2(double v) {
		return String.format("%.2f", v);
	}

	private String fmt3(double v) {
		return String.format("%.3f", v);
	}

	private String fmt4(double v) {
		return String.format("%.4f", v);
	}

	private String fmtD(double v, int decimals) {
		return String.format("%." + decimals + "f", v);
	}

	private boolean isMy(String market) {
		return market != null && market.equalsIgnoreCase("my");
	}
}