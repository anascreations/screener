package com.screener.service.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.screener.service.dto.Level2Request;
import com.screener.service.model.Level2Entry;

@Service
public class CalculationService {
	public Map<String, Object> calculate(Level2Request req) {
		List<Level2Entry> bids = req.bids();
		List<Level2Entry> asks = req.asks();
		Map<String, Object> resp = new LinkedHashMap<>();
		double bestBid = bids.get(0).price();
		double bestAsk = asks.get(0).price();
		double spread = round4(bestAsk - bestBid);
		double spreadPct = round4((spread / bestAsk) * 100);
		resp.put("spread", Map.of("bestBid", bestBid, "bestAsk", bestAsk, "spread", spread, "spreadPct",
				spreadPct + "%", "tight", spreadPct < 0.5));
		double totalBidVol = bids.stream().mapToDouble(Level2Entry::volume).sum();
		double totalAskVol = asks.stream().mapToDouble(Level2Entry::volume).sum();
		double obi = totalBidVol / (totalBidVol + totalAskVol);
		String obiEmoji = obi >= 0.60 ? "🟢" : obi >= 0.50 ? "🟡" : obi >= 0.40 ? "🟠" : "🔴";
		String obiSignal = obi >= 0.60 ? "STRONG BUY PRESSURE"
				: obi >= 0.50 ? "MILD BUY PRESSURE" : obi >= 0.40 ? "MILD SELL PRESSURE" : "STRONG SELL PRESSURE";
		resp.put("orderBookImbalance",
				Map.of("totalBidVolume", round0(totalBidVol), "totalAskVolume", round0(totalAskVol), "obiRatio",
						round4(obi), "obiPct", round2(obi * 100) + "%", "signal", obiEmoji + " " + obiSignal));
		double bidW = bids.get(0).volume();
		double askW = asks.get(0).volume();
		double weightedMid = round4((bestBid * askW + bestAsk * bidW) / (bidW + askW));
		double simpleMid = round4((bestBid + bestAsk) / 2.0);
		resp.put("midPrice", Map.of("simple", simpleMid, "weighted", weightedMid, "note",
				"Weighted mid skews toward the thinner side"));
		double avgBidVol = totalBidVol / bids.size();
		double avgAskVol = totalAskVol / asks.size();
		List<Map<String, Object>> bidWalls = bids.stream().filter(b -> b.volume() >= avgBidVol * 2.0).map(b -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("price", b.price());
			m.put("volume", round0(b.volume()));
			m.put("orders", b.orders());
			m.put("strength", round2(b.volume() / avgBidVol) + "× avg");
			m.put("role", "🛡️ BID WALL — support");
			return m;
		}).collect(Collectors.toList());
		List<Map<String, Object>> askWalls = asks.stream().filter(a -> a.volume() >= avgAskVol * 2.0).map(a -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("price", a.price());
			m.put("volume", round0(a.volume()));
			m.put("orders", a.orders());
			m.put("strength", round2(a.volume() / avgAskVol) + "× avg");
			m.put("role", "🧱 ASK WALL — resistance");
			return m;
		}).collect(Collectors.toList());
		resp.put("walls", Map.of("bidWalls", bidWalls, "askWalls", askWalls));
		double top3Bid = bids.stream().limit(3).mapToDouble(Level2Entry::volume).sum();
		double top3Ask = asks.stream().limit(3).mapToDouble(Level2Entry::volume).sum();
		double absRatio = round4(top3Bid / top3Ask);
		resp.put("depthAbsorption", Map.of("top3BidVolume", round0(top3Bid), "top3AskVolume", round0(top3Ask),
				"absorptionRatio", absRatio, "signal", absRatio >= 1.2 ? "✅ Buyers absorbing more — bullish near-term"
						: absRatio <= 0.8 ? "❌ Sellers absorbing more — bearish near-term" : "⚖️ Balanced absorption"));
		double uiDivergence = round2(Math.abs(req.bidPressurePct() - (obi * 100)));
		resp.put("pressureDivergence",
				Map.of("uiBidPressure", req.bidPressurePct() + "%", "uiAskPressure", req.askPressurePct() + "%",
						"volumeOBIPct", round2(obi * 100) + "%", "divergence", uiDivergence + "%", "note",
						uiDivergence > 10
								? "⚠️ UI pressure diverges from OBI by " + uiDivergence + "% — hidden orders possible"
								: "✅ UI pressure aligns with volume OBI"));
		int score = 0;
		List<String> reasons = new ArrayList<>();
		if (obi >= 0.55) {
			score += 2;
			reasons.add("OBI bullish: " + round2(obi * 100) + "%");
		} else if (obi < 0.45) {
			score -= 2;
			reasons.add("OBI bearish: " + round2(obi * 100) + "%");
		}
		if (absRatio >= 1.2) {
			score += 2;
			reasons.add("Absorption: buyers winning");
		} else if (absRatio <= 0.8) {
			score -= 2;
			reasons.add("Absorption: sellers winning");
		}
		if (!bidWalls.isEmpty()) {
			score += 1;
			reasons.add("Bid wall = strong support below");
		}
		if (!askWalls.isEmpty()) {
			score -= 1;
			reasons.add("Ask wall = resistance above");
		}
		if (spreadPct < 0.3) {
			score += 1;
			reasons.add("Tight spread: liquid market");
		} else if (spreadPct > 1.0) {
			score -= 1;
			reasons.add("Wide spread: slippage risk");
		}
		if (req.bidPressurePct() >= 50) {
			score += 1;
			reasons.add("UI bar: bid dominant");
		} else {
			score -= 1;
			reasons.add("UI bar: ask dominant");
		}
		double nearestBidWall = bidWalls.isEmpty() ? bids.get(bids.size() - 1).price()
				: ((Number) bidWalls.get(0).get("price")).doubleValue();
		String action, actionEmoji, strategy;
		double entryPrice;
		if (score >= 3) {
			action = "BUY — AGGRESSIVE";
			actionEmoji = "🟢";
			entryPrice = bestAsk;
			strategy = "Lift the ask. Strong confluence across all factors.";
		} else if (score >= 1) {
			action = "BUY — PASSIVE LIMIT";
			actionEmoji = "🟡";
			entryPrice = weightedMid;
			strategy = "Queue at weighted mid — let price come to you.";
		} else if (score <= -3) {
			action = "SKIP — SELL DOMINANT";
			actionEmoji = "🔴";
			entryPrice = 0;
			strategy = "Do not enter. Wait for OBI shift above 50%.";
		} else {
			action = "WAIT — NEUTRAL";
			actionEmoji = "⚪";
			entryPrice = bestBid;
			strategy = "Queue at best bid only. No clear edge — reduce size.";
		}
		double stopLoss = round4(nearestBidWall * 0.995);
		double riskR = entryPrice > 0 ? round4(entryPrice - stopLoss) : 0;
		Map<String, Object> rec = new LinkedHashMap<>();
		rec.put("action", actionEmoji + " " + action);
		rec.put("score", score + " / 7  (≥3 Buy · ≤-3 Skip · else Wait)");
		rec.put("strategy", strategy);
		rec.put("entryPrice", entryPrice > 0 ? entryPrice : "N/A");
		rec.put("stopLoss", stopLoss > 0 ? stopLoss : "N/A");
		rec.put("stopNote", "0.5% below nearest bid wall at " + nearestBidWall);
		rec.put("riskPerUnit", riskR > 0 ? riskR : "N/A");
		rec.put("target1", entryPrice > 0 ? round4(entryPrice + riskR * 1.5) : "N/A");
		rec.put("target1Note", "R:R 1:1.5 — take 50% off here");
		rec.put("target2", entryPrice > 0 ? round4(entryPrice + riskR * 3.0) : "N/A");
		rec.put("target2Note", "R:R 1:3.0 — trail remainder");
		rec.put("scoringBreakdown", reasons);
		resp.put("recommendation", rec);
		return resp;
	}

	private double round4(double v) {
		return Math.round(v * 10000.0) / 10000.0;
	}

	private double round2(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	private double round0(double v) {
		return Math.round(v);
	}
}