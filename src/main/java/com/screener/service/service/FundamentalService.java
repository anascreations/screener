package com.screener.service.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.screener.service.enums.Market;
import com.screener.service.util.ThreadUtil;

import lombok.extern.slf4j.Slf4j;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * FUNDAMENTAL ANALYSIS SERVICE
 *
 * Fetches key financial metrics from Yahoo Finance quoteSummary API and
 * produces a BUY / WATCH / SKIP recommendation with scored reasons.
 *
 * API used: https://query1.finance.yahoo.com/v10/finance/quoteSummary/{ticker}
 * modules: financialData, defaultKeyStatistics, summaryDetail, earningsTrend
 *
 * Scoring philosophy — what actually predicts price going up:
 * ──────────────────────────────────────────────────────────────
 *
 * GREEN FLAGS (add score): Revenue growing QoQ / YoY — top-line momentum Net
 * profit margin > 10% — pricing power EPS growing — earnings per share rising =
 * value P/E ratio reasonable (< 25) — not overpriced ROE > 12% — management
 * using capital well Debt/Equity < 1.0 — not drowning in debt Current ratio >
 * 1.5 — can pay short-term bills Free cash flow positive — real cash coming in,
 * not accounting tricks Dividend yield supported by FCF — dividend is safe, not
 * a trap Insider buying / low short interest — smart money aligned
 *
 * RED FLAGS (subtract score): Revenue growing but profit shrinking — costs out
 * of control (screenshot case!) Net profit margin falling — pricing power
 * erosion EPS declining — each share worth less each quarter High P/E (>40)
 * with no growth — overpriced trap Negative free cash flow — paying dividends
 * from debt Dividend payout ratio > 100% — unsustainable dividend (danger!) D/E
 * > 2.0 — heavily leveraged, rate-sensitive Current ratio < 1.0 — liquidity
 * risk Declining gross margin — losing competitive advantage
 *
 * SCREENING THIS SCREENSHOT: Revenue +3.11% ✅ growing PBT -11.39% ❌ costs
 * rising faster than revenue Net profit -4.98% ❌ shrinking NPM 28.38% ✅ healthy
 * margin BUT declining -7.85% ❌ EPS -4.49% ❌ shareholders getting less per
 * share DPS +140% ⚠️ RED FLAG — dividend raised while profits fall =
 * unsustainable BVPS +8.41% ✅ book value growing (assets building)
 *
 * Verdict: SKIP for swing/scalp — profit quality deteriorating. May be value
 * play long-term but not for short-term momentum.
 * ═══════════════════════════════════════════════════════════════════════════
 */
@Service
@Slf4j
public class FundamentalService {
	private static final String BASE_URL = "https://query2.finance.yahoo.com/v10/finance/quoteSummary/%s"
			+ "?modules=financialData,defaultKeyStatistics,summaryDetail,earningsTrend,incomeStatementHistoryQuarterly"
			+ "&crumb=%s";
	private static final int MAX_RETRIES = 3;
	private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36";
	private static final double BUY_SCORE = 60.0;
	private static final double WATCH_SCORE = 30.0;
	private final HttpClient httpClient;
	private final ObjectMapper json = new ObjectMapper();
	private final Map<Market, SessionProvider> sessionMap;

	public FundamentalService(ExchangeMyService myExchange, ExchangeUsService usExchange) {
		this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2)
				.followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build();
		this.sessionMap = Map.of(Market.MY, (SessionProvider) myExchange, Market.US, (SessionProvider) usExchange);
	}

	/**
	 * Full fundamental analysis for a stock.
	 * 
	 * @param market MY or US
	 * @param code   bare code (e.g. "5243" for MY, "AAPL" for US)
	 * @return FundamentalResult with recommendation + scored reasons
	 */
	public FundamentalResult analyze(Market market, String code) {
		String ticker = market == Market.MY ? code.trim().toUpperCase() + ".KL" : code.trim().toUpperCase();
		log.info("[FUND] Analyzing fundamentals: {}", ticker);
		try {
			ThreadUtil.sleep(ThreadLocalRandom.current().nextLong(300, 700)); // polite delay
			JsonNode root = fetch(market, ticker);
			if (root == null) {
				return FundamentalResult.error(code, "Could not fetch data from Yahoo Finance for " + ticker);
			}
			JsonNode result = root.path("quoteSummary").path("result");
			if (!result.isArray() || result.isEmpty()) {
				JsonNode err = root.path("quoteSummary").path("error");
				String msg = err.isNull() ? "No data returned" : err.path("description").asText("Unknown error");
				return FundamentalResult.error(code, msg);
			}
			JsonNode data = result.get(0);
			JsonNode finData = data.path("financialData");
			JsonNode keyStats = data.path("defaultKeyStatistics");
			JsonNode summary = data.path("summaryDetail");
			JsonNode earnTrend = data.path("earningsTrend");
			JsonNode incomeSt = data.path("incomeStatementHistoryQuarterly");
			return score(market, code, ticker, finData, keyStats, summary, earnTrend, incomeSt);
		} catch (Exception e) {
			log.error("[FUND] {} analysis failed: {}", ticker, e.getMessage());
			return FundamentalResult.error(code, "Analysis error: " + e.getMessage());
		}
	}

	private FundamentalResult score(Market market, String code, String ticker, JsonNode fin, JsonNode ks, JsonNode sd,
			JsonNode earnTrend, JsonNode incomeQ) {
		double totalScore = 0;
		List<String> green = new ArrayList<>();
		List<String> red = new ArrayList<>();
		List<String> neutral = new ArrayList<>();
		Map<String, Object> metrics = new LinkedHashMap<>();
		// ── Extract raw values ─────────────────────────────────────────────────
		// ── Data freshness — when was this data last reported? ──────────────────
		// mostRecentQuarter : Unix timestamp of the last filed quarterly report
		// fiscalYearEnd : Unix timestamp of the last fiscal year end
		// earningsDate : next expected earnings announcement (from calendar)
		long mostRecentQuarter = raw(ks, "mostRecentQuarter") > 0 ? (long) raw(ks, "mostRecentQuarter") : 0L;
		long fiscalYearEnd = raw(ks, "lastFiscalYearEnd") > 0 ? (long) raw(ks, "lastFiscalYearEnd") : 0L;
		long nextEarningsTs = raw(sd, "earningsTimestamp") > 0 // earningsTimestamp not in summaryDetail
				? (long) raw(sd, "earningsTimestamp")
				: 0L;
		// Compute data age in days
		long nowEpoch = java.time.Instant.now().getEpochSecond();
		long ageDays = mostRecentQuarter > 0 ? (nowEpoch - mostRecentQuarter) / 86400 : -1;
		double revenueGrowthQoQ = pct(fin, "revenueGrowth"); // QoQ
		double earningsGrowthQoQ = pct(fin, "earningsGrowth"); // QoQ
		double netProfitMargin = pct(fin, "profitMargins");
		double grossMargin = pct(fin, "grossMargins");
		double operatingMargin = pct(fin, "operatingMargins");
		double roe = pct(fin, "returnOnEquity");
		double roa = pct(fin, "returnOnAssets");
		double debtToEquity = raw(fin, "debtToEquity") / 100.0; // Yahoo gives as %, normalise
		double currentRatio = raw(fin, "currentRatio");
		double freeCashflow = raw(fin, "freeCashflow"); // absolute
		double totalRevenue = raw(fin, "totalRevenue");
		double totalCash = raw(fin, "totalCash");
		double trailingPE = raw(ks, "trailingEps") != 0
				? raw(sd, "marketCap") / (raw(ks, "trailingEps") * raw(ks, "sharesOutstanding"))
				: raw(sd, "trailingPE");
		trailingPE = raw(sd, "trailingPE"); // use summary's PE
		double forwardPE = raw(sd, "forwardPE");
		double priceToBook = raw(ks, "priceToBook");
		double dividendYield = pct(sd, "dividendYield");
		double payoutRatio = pct(sd, "payoutRatio");
		double beta = raw(sd, "beta");
		double eps = raw(ks, "trailingEps");
		double forwardEps = raw(ks, "forwardEps");
		double revenuePerShare = raw(fin, "revenuePerShare");
		double shortRatio = raw(ks, "shortRatio");
		// YoY revenue trend from quarterly income statements
		double revenueGrowthYoY = computeYoYRevenue(incomeQ);
		// EPS growth trend (forward vs trailing)
		double epsGrowth = (eps != 0 && forwardEps != 0) ? (forwardEps - eps) / Math.abs(eps) : 0;
		// Dividend sustainability check
		double fcfPerShare = (freeCashflow != 0 && totalRevenue != 0) ? freeCashflow / totalRevenue * revenuePerShare
				: 0;
		double dividendPerShare = raw(sd, "dividendRate");
		boolean dividendUnsustainable = dividendPerShare > 0 && freeCashflow < 0;
		boolean payoutDangerous = payoutRatio > 1.0; // paying out more than it earns
		// EPS Trend from earningsTrend
		double epsGrowthCurrentQ = earnTrendValue(earnTrend, "0q", "growth");
		double epsGrowthNextQ = earnTrendValue(earnTrend, "+1q", "growth");
		double epsGrowthCurrentY = earnTrendValue(earnTrend, "0y", "growth");
		double epsGrowthNextY = earnTrendValue(earnTrend, "+1y", "growth");
		// Build metrics map for response
		metrics.put("revenue", fmt(totalRevenue));
		metrics.put("revenueGrowthQoQ", fmtPct(revenueGrowthQoQ));
		metrics.put("revenueGrowthYoY", fmtPct(revenueGrowthYoY));
		metrics.put("netProfitMargin", fmtPct(netProfitMargin));
		metrics.put("grossMargin", fmtPct(grossMargin));
		metrics.put("operatingMargin", fmtPct(operatingMargin));
		metrics.put("earningsGrowthQoQ", fmtPct(earningsGrowthQoQ));
		metrics.put("epsTrailing", f4(eps));
		metrics.put("epsForward", f4(forwardEps));
		metrics.put("epsGrowthFwd", fmtPct(epsGrowth));
		metrics.put("epsTrendCurrentQ", fmtPct(epsGrowthCurrentQ));
		metrics.put("epsTrendNextQ", fmtPct(epsGrowthNextQ));
		metrics.put("epsTrendCurrentY", fmtPct(epsGrowthCurrentY));
		metrics.put("epsTrendNextY", fmtPct(epsGrowthNextY));
		metrics.put("roe", fmtPct(roe));
		metrics.put("roa", fmtPct(roa));
		metrics.put("debtToEquity", f2(debtToEquity));
		metrics.put("currentRatio", f2(currentRatio));
		metrics.put("freeCashflow", fmt(freeCashflow));
		metrics.put("trailingPE", f2(trailingPE));
		metrics.put("forwardPE", f2(forwardPE));
		metrics.put("priceToBook", f2(priceToBook));
		metrics.put("dividendYield", fmtPct(dividendYield));
		metrics.put("dividendPerShare", f4(dividendPerShare));
		metrics.put("payoutRatio", fmtPct(payoutRatio));
		metrics.put("beta", f2(beta));
		metrics.put("shortRatio", f2(shortRatio));
		// Data freshness fields
		metrics.put("reportDate",
				mostRecentQuarter > 0
						? java.time.Instant.ofEpochSecond(mostRecentQuarter)
								.atZone(java.time.ZoneId.of("Asia/Kuala_Lumpur")).toLocalDate().toString()
						: "N/A");
		metrics.put("fiscalYearEnd",
				fiscalYearEnd > 0
						? java.time.Instant.ofEpochSecond(fiscalYearEnd)
								.atZone(java.time.ZoneId.of("Asia/Kuala_Lumpur")).toLocalDate().toString()
						: "N/A");
		metrics.put("dataAgeDays", ageDays >= 0 ? (long) ageDays : "N/A");
		metrics.put("dataFreshness",
				ageDays < 0 ? "UNKNOWN"
						: ageDays <= 45 ? "FRESH — within current quarter"
								: ageDays <= 100 ? "RECENT — last quarter"
										: ageDays <= 135 ? "STALE — 1 quarter old, new report due soon"
												: "OLD — 2+ quarters behind, data may be significantly outdated");
		if (ageDays > 135) {
			totalScore -= 10;
			red.add("⚠ DATA IS " + ageDays + " DAYS OLD (2+ quarters) — figures may be significantly outdated. "
					+ "Verify on Bursa website or i3investor before trading.");
		} else if (ageDays > 100) {
			neutral.add("ℹ Data is " + ageDays + " days old (last quarter) — new quarterly report due soon. "
					+ "Watch for upcoming earnings announcement.");
		} else if (ageDays >= 0 && ageDays <= 45) {
			totalScore += 3;
			green.add("✅ Data fresh — latest quarterly report filed " + ageDays + " days ago");
		}
		// Revenue growth
		if (revenueGrowthQoQ >= 0.20) {
			totalScore += 18;
			green.add("🚀 Revenue surging +" + fmtPct(revenueGrowthQoQ) + " QoQ — strong top-line growth");
		} else if (revenueGrowthQoQ >= 0.10) {
			totalScore += 12;
			green.add("✅ Revenue growing +" + fmtPct(revenueGrowthQoQ) + " QoQ");
		} else if (revenueGrowthQoQ >= 0.03) {
			totalScore += 5;
			green.add("Revenue +" + fmtPct(revenueGrowthQoQ) + " QoQ — modest growth");
		} else if (revenueGrowthQoQ >= 0) {
			totalScore += 1;
			neutral.add("Revenue flat +" + fmtPct(revenueGrowthQoQ) + " QoQ");
		} else if (revenueGrowthQoQ >= -0.10) {
			totalScore -= 10;
			red.add("❌ Revenue declining " + fmtPct(revenueGrowthQoQ) + " QoQ");
		} else {
			totalScore -= 20;
			red.add("🚨 Revenue collapsing " + fmtPct(revenueGrowthQoQ) + " QoQ — serious concern");
		}
		// CRITICAL: Revenue growing but profits falling = cost problem (this
		// screenshot)
		if (revenueGrowthQoQ > 0.02 && earningsGrowthQoQ < -0.05) {
			totalScore -= 15;
			red.add("🚨 MARGIN SQUEEZE: Revenue +" + fmtPct(revenueGrowthQoQ) + " but earnings "
					+ fmtPct(earningsGrowthQoQ) + " — costs rising faster than revenue. Momentum traders avoid.");
		}
		// Profit margin absolute level
		if (netProfitMargin >= 0.20) {
			totalScore += 15;
			green.add("✅ Net margin " + fmtPct(netProfitMargin) + " — excellent pricing power");
		} else if (netProfitMargin >= 0.10) {
			totalScore += 8;
			green.add("Net margin " + fmtPct(netProfitMargin) + " — healthy");
		} else if (netProfitMargin >= 0.05) {
			totalScore += 2;
			neutral.add("Net margin " + fmtPct(netProfitMargin) + " — thin but positive");
		} else if (netProfitMargin >= 0) {
			totalScore -= 5;
			red.add("⚠ Net margin " + fmtPct(netProfitMargin) + " — very thin");
		} else {
			totalScore -= 20;
			red.add("🚨 Negative net margin " + fmtPct(netProfitMargin) + " — company losing money");
		}
		// Earnings growth
		if (earningsGrowthQoQ >= 0.20) {
			totalScore += 18;
			green.add("🚀 Earnings growth +" + fmtPct(earningsGrowthQoQ) + " QoQ — profit accelerating");
		} else if (earningsGrowthQoQ >= 0.05) {
			totalScore += 10;
			green.add("✅ Earnings growth +" + fmtPct(earningsGrowthQoQ) + " QoQ");
		} else if (earningsGrowthQoQ >= 0) {
			totalScore += 3;
			neutral.add("Earnings flat +" + fmtPct(earningsGrowthQoQ));
		} else if (earningsGrowthQoQ >= -0.10) {
			totalScore -= 10;
			red.add("❌ Earnings declining " + fmtPct(earningsGrowthQoQ) + " QoQ");
		} else {
			totalScore -= 20;
			red.add("🚨 Earnings collapsing " + fmtPct(earningsGrowthQoQ) + " QoQ");
		}
		if (eps > 0 && forwardEps > eps) {
			totalScore += 15;
			green.add("✅ EPS expected to GROW: trailing=" + f4(eps) + " → forward=" + f4(forwardEps) + " (+"
					+ fmtPct(epsGrowth) + ")");
		} else if (eps > 0 && forwardEps > 0 && forwardEps <= eps) {
			totalScore -= 8;
			red.add("EPS growth stalling: trailing=" + f4(eps) + " forward=" + f4(forwardEps));
		} else if (eps < 0) {
			totalScore -= 15;
			red.add("🚨 Negative EPS " + f4(eps) + " — company not profitable per share");
		}
		// EPS trend (analyst estimates)
		if (epsGrowthCurrentQ > 0.10) {
			totalScore += 10;
			green.add("📊 Analysts expect EPS +" + fmtPct(epsGrowthCurrentQ) + " this quarter");
		} else if (epsGrowthCurrentQ > 0) {
			totalScore += 5;
			green.add("Analysts expect EPS +" + fmtPct(epsGrowthCurrentQ) + " this quarter");
		} else if (epsGrowthCurrentQ < -0.10) {
			totalScore -= 10;
			red.add("📊 Analysts expect EPS " + fmtPct(epsGrowthCurrentQ) + " this quarter");
		}
		if (epsGrowthNextY > 0.15) {
			totalScore += 12;
			green.add("🔭 Strong forward earnings growth +" + fmtPct(epsGrowthNextY) + " next year");
		} else if (epsGrowthNextY > 0) {
			totalScore += 5;
			green.add("Forward EPS growth +" + fmtPct(epsGrowthNextY) + " next year");
		} else if (epsGrowthNextY < 0) {
			totalScore -= 10;
			red.add("Forward EPS declining " + fmtPct(epsGrowthNextY) + " next year");
		}
		if (roe >= 0.20) {
			totalScore += 15;
			green.add("✅ ROE " + fmtPct(roe) + " — excellent, management creating value");
		} else if (roe >= 0.12) {
			totalScore += 8;
			green.add("ROE " + fmtPct(roe) + " — good capital efficiency");
		} else if (roe >= 0.05) {
			totalScore += 2;
			neutral.add("ROE " + fmtPct(roe) + " — mediocre returns");
		} else if (roe >= 0) {
			totalScore -= 5;
			red.add("ROE " + fmtPct(roe) + " — very low returns on equity");
		} else {
			totalScore -= 15;
			red.add("🚨 Negative ROE " + fmtPct(roe) + " — equity being destroyed");
		}
		// Debt/Equity
		if (debtToEquity <= 0.3) {
			totalScore += 10;
			green.add("✅ Debt/Equity " + f2(debtToEquity) + " — low debt, financially solid");
		} else if (debtToEquity <= 0.8) {
			totalScore += 5;
			green.add("Debt/Equity " + f2(debtToEquity) + " — manageable debt");
		} else if (debtToEquity <= 1.5) {
			totalScore -= 5;
			neutral.add("Debt/Equity " + f2(debtToEquity) + " — moderate leverage, watch interest rates");
		} else if (debtToEquity <= 2.5) {
			totalScore -= 12;
			red.add("❌ Debt/Equity " + f2(debtToEquity) + " — high leverage, rate-sensitive");
		} else if (debtToEquity > 2.5) {
			totalScore -= 20;
			red.add("🚨 Debt/Equity " + f2(debtToEquity) + " — dangerously leveraged");
		}
		// Current ratio (liquidity)
		if (currentRatio >= 2.0) {
			totalScore += 10;
			green.add("✅ Current ratio " + f2(currentRatio) + " — strong liquidity");
		} else if (currentRatio >= 1.5) {
			totalScore += 5;
			green.add("Current ratio " + f2(currentRatio) + " — adequate liquidity");
		} else if (currentRatio >= 1.0) {
			totalScore += 0;
			neutral.add("Current ratio " + f2(currentRatio) + " — just enough liquidity");
		} else if (currentRatio > 0) {
			totalScore -= 15;
			red.add("🚨 Current ratio " + f2(currentRatio) + " — liquidity risk! Cannot cover short-term debts");
		}
		// Free Cash Flow — the gold standard
		if (freeCashflow > 0 && freeCashflow > totalRevenue * 0.10) {
			totalScore += 15;
			green.add("✅ Strong Free Cash Flow " + fmt(freeCashflow) + " (>" + "10% of revenue) — real cash business");
		} else if (freeCashflow > 0) {
			totalScore += 8;
			green.add("✅ Positive Free Cash Flow " + fmt(freeCashflow));
		} else if (freeCashflow < 0) {
			totalScore -= 12;
			red.add("❌ Negative Free Cash Flow " + fmt(freeCashflow) + " — burning cash");
		}
		if (trailingPE > 0) {
			if (trailingPE <= 12) {
				totalScore += 12;
				green.add("✅ P/E " + f2(trailingPE) + " — undervalued, cheap entry");
			} else if (trailingPE <= 20) {
				totalScore += 6;
				green.add("P/E " + f2(trailingPE) + " — fair value");
			} else if (trailingPE <= 30) {
				totalScore += 0;
				neutral.add("P/E " + f2(trailingPE) + " — slightly premium, needs growth to justify");
			} else if (trailingPE <= 50) {
				totalScore -= 10;
				red.add("⚠ P/E " + f2(trailingPE) + " — expensive. High expectations baked in.");
			} else {
				totalScore -= 20;
				red.add("🚨 P/E " + f2(trailingPE) + " — extreme valuation. Any miss = large drop.");
			}
		} else if (trailingPE < 0) {
			totalScore -= 15;
			red.add("🚨 Negative P/E — company is loss-making");
		}
		// Forward P/E vs trailing (expectations)
		if (forwardPE > 0 && trailingPE > 0 && forwardPE < trailingPE * 0.85) {
			totalScore += 8;
			green.add("✅ Forward P/E " + f2(forwardPE) + " < trailing " + f2(trailingPE)
					+ " — earnings expected to grow");
		} else if (forwardPE > 0 && trailingPE > 0 && forwardPE > trailingPE * 1.15) {
			totalScore -= 8;
			red.add("Forward P/E " + f2(forwardPE) + " > trailing — earnings expected to fall");
		}
		// Price to Book
		if (priceToBook > 0) {
			if (priceToBook < 1.0) {
				totalScore += 8;
				green.add("✅ P/B " + f2(priceToBook) + " — trading below book value (potential hidden value)");
			} else if (priceToBook < 3.0) {
				totalScore += 3;
				neutral.add("P/B " + f2(priceToBook) + " — reasonable");
			} else if (priceToBook > 8.0) {
				totalScore -= 5;
				red.add("P/B " + f2(priceToBook) + " — premium to book value");
			}
		}
		if (dividendYield > 0) {
			if (dividendUnsustainable) {
				totalScore -= 18;
				red.add("🚨 DIVIDEND TRAP: Yield " + fmtPct(dividendYield)
						+ " but FREE CASH FLOW IS NEGATIVE. Dividend being paid from debt/reserves. Likely cut soon.");
			} else if (payoutDangerous) {
				totalScore -= 12;
				red.add("⚠ PAYOUT RATIO " + fmtPct(payoutRatio)
						+ " — paying out more than it earns. Unsustainable. Dividend cut risk.");
			} else if (payoutRatio > 0.80) {
				totalScore -= 6;
				red.add("Payout ratio " + fmtPct(payoutRatio) + " — high, leaves little for reinvestment/growth");
			} else if (dividendYield >= 0.04 && payoutRatio <= 0.60) {
				totalScore += 10;
				green.add("✅ Healthy dividend " + fmtPct(dividendYield) + " yield, payout " + fmtPct(payoutRatio)
						+ " — sustainable");
			} else if (dividendYield >= 0.02) {
				totalScore += 4;
				green.add("Dividend yield " + fmtPct(dividendYield) + " — modest income");
			}
		}
		if (beta > 0) {
			if (beta >= 1.5) {
				totalScore -= 5;
				neutral.add("Beta " + f2(beta) + " — high volatility, magnifies market moves");
			} else if (beta >= 0.8 && beta < 1.5) {
				totalScore += 3;
				green.add("Beta " + f2(beta) + " — normal market correlation");
			} else if (beta < 0.5) {
				neutral.add("Beta " + f2(beta) + " — low volatility, defensive stock");
			}
		}
		// Short interest (high short = institutions betting against it)
		if (shortRatio > 10) {
			totalScore -= 10;
			red.add("⚠ Short ratio " + f2(shortRatio) + " — high short interest, institutions bearish");
		} else if (shortRatio > 5) {
			totalScore -= 5;
			red.add("Short ratio " + f2(shortRatio) + " — elevated short interest");
		} else if (shortRatio > 0 && shortRatio <= 2) {
			totalScore += 3;
			green.add("✅ Low short interest " + f2(shortRatio) + " — not targeted by short sellers");
		}
		String verdict;
		String verdictDetail;
		// Count critical red flags
		long criticalReds = red.stream().filter(s -> s.contains("🚨")).count();
		long reds = red.size();
		long greens = green.size();
		if (criticalReds >= 2) {
			verdict = "SKIP";
			verdictDetail = "Multiple critical red flags. Fundamental deterioration too severe for short-term trading. "
					+ "Risk of further price decline outweighs any technical signal.";
		} else if (criticalReds == 1 && reds > greens) {
			verdict = "SKIP";
			verdictDetail = "One critical red flag plus overall negative balance. "
					+ "Fundamentals do not support a momentum trade.";
		} else if (totalScore >= BUY_SCORE && criticalReds == 0) {
			verdict = "BUY_SUPPORTED";
			verdictDetail = "Strong fundamentals support the technical signal. "
					+ "Business fundamentally sound — technical entry is well-backed.";
		} else if (totalScore >= WATCH_SCORE) {
			verdict = "WATCH";
			verdictDetail = "Mixed fundamentals. Technical signal may work short-term but "
					+ "fundamentals are not a tailwind. Trade only Grade A/B technical signals.";
		} else {
			verdict = "SKIP";
			verdictDetail = "Weak fundamentals. Even if a MACD cross appears, the underlying business "
					+ "is not generating value. Skip.";
		}
		// Trading recommendation aligned with trade type
		String tradingAdvice = buildTradingAdvice(verdict, totalScore, criticalReds, reds, greens, revenueGrowthQoQ,
				earningsGrowthQoQ, netProfitMargin, eps, freeCashflow);
		log.info("[FUND] {} → {} (score={:.1f}, greens={}, reds={}, critical={})", ticker, verdict, totalScore, greens,
				reds, criticalReds);
		return new FundamentalResult(code.toUpperCase(), ticker, verdict, verdictDetail, tradingAdvice,
				Math.round(totalScore * 10.0) / 10.0, criticalReds, green, red, neutral, metrics);
	}

	private String buildTradingAdvice(String verdict, double score, long critReds, long reds, long greens,
			double revGrowth, double earnGrowth, double margin, double eps, double fcf) {
		return switch (verdict) {
		case "BUY_SUPPORTED" -> """
				✅ FUNDAMENTALS SUPPORT BUYING.
				Execute the technical trade plan with FULL confidence grade sizing.
				This is not just a chart pattern — the business is actually growing.
				Hold toward TP2/TP3 if technical momentum continues.
				After TP1 hit: consider holding a small position longer term.""";
		case "WATCH" -> """
				⚠ TRADE WITH CAUTION — mixed fundamentals.
				If technical signal is Grade A or B: execute at 50% normal position size.
				If technical signal is Grade C: SKIP — not worth the risk.
				Target: TP1 only. Exit quickly. Do not hold overnight more than 2 days.
				Watch for next quarterly report — if fundamentals improve, re-evaluate.""";
		case "SKIP" -> {
			if (revGrowth > 0 && earnGrowth < -0.05)
				yield """
						🚫 SKIP — MARGIN SQUEEZE DETECTED.
						Revenue growing but profits falling = costs out of control.
						Momentum traders avoid — the business is working harder for less profit.
						Even a strong MACD cross here has high failure rate.
						Re-evaluate after next quarterly report shows if margin recovers.""";
			else if (eps < 0)
				yield """
						🚫 SKIP — COMPANY IS LOSS-MAKING.
						Negative EPS means each share represents ongoing losses.
						Any price rise is speculation, not business value.
						Only suitable for very short scalp (same day, strict SL).""";
			else if (fcf < 0)
				yield """
						🚫 SKIP — NEGATIVE FREE CASH FLOW.
						Company burning cash. Any dividend is being paid from debt.
						High risk of sudden negative announcement.
						Scalp only if technical setup is exceptional (Grade A).""";
			else
				yield """
						🚫 SKIP — FUNDAMENTALS DO NOT SUPPORT BUYING.
						Too many negative factors outweigh positives.
						Wait for business improvement before trading.""";
		}
		default -> "No specific advice available.";
		};
	}

	private double computeYoYRevenue(JsonNode incomeQ) {
		try {
			JsonNode stmts = incomeQ.path("incomeStatementHistory");
			if (!stmts.isArray() || stmts.size() < 5)
				return 0;
			double latestQ = stmts.get(0).path("totalRevenue").path("raw").asDouble(0);
			double yearAgoQ = stmts.get(4).path("totalRevenue").path("raw").asDouble(0);
			return yearAgoQ > 0 ? (latestQ - yearAgoQ) / yearAgoQ : 0;
		} catch (Exception e) {
			return 0;
		}
	}

	/** Extract EPS growth from earningsTrend for a specific period */
	private double earnTrendValue(JsonNode earnTrend, String period, String field) {
		try {
			JsonNode trends = earnTrend.path("trend");
			if (!trends.isArray())
				return 0;
			for (JsonNode t : trends) {
				if (period.equals(t.path("period").asText(""))) {
					return t.path(field).path("raw").asDouble(0);
				}
			}
		} catch (Exception e) {
			/* ignore */ }
		return 0;
	}

	private double raw(JsonNode node, String key) {
		try {
			JsonNode v = node.path(key);
			if (v.has("raw"))
				return v.path("raw").asDouble(0);
			return v.asDouble(0);
		} catch (Exception e) {
			return 0;
		}
	}

	private double pct(JsonNode node, String key) {
		return raw(node, key);
	}

	private JsonNode fetch(Market market, String ticker) throws Exception {
		SessionProvider session = sessionMap.get(market);
		for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
			String cookie = session.getSessionCookie();
			String crumb = session.getCrumb();
			if (cookie == null || crumb == null) {
				log.warn("[FUND] No valid Yahoo session for {} (attempt {}/{})", ticker, attempt, MAX_RETRIES);
				session.forceRefresh();
				ThreadUtil.sleep(2000L * attempt);
				continue;
			}
			String url = String.format(BASE_URL, URLEncoder.encode(ticker, StandardCharsets.UTF_8),
					URLEncoder.encode(crumb, StandardCharsets.UTF_8));
			HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", USER_AGENT)
					.header("Accept", "application/json,*/*").header("Accept-Language", "en-US,en;q=0.9")
					.header("Cookie", cookie).header("Referer", "https://finance.yahoo.com/")
					.timeout(Duration.ofSeconds(20)).GET().build();
			HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
			int status = resp.statusCode();
			if (status == 404) {
				log.warn("[FUND] {} not found (404)", ticker);
				return null;
			}
			if (status == 401 || status == 403) {
				log.warn("[FUND] {} auth error ({}), refreshing session (attempt {}/{})", ticker, status, attempt,
						MAX_RETRIES);
				session.forceRefresh();
				ThreadUtil.sleep(2000L * attempt);
				continue;
			}
			if (status != 200) {
				log.warn("[FUND] HTTP {} for {} (attempt {}/{})", status, ticker, attempt, MAX_RETRIES);
				ThreadUtil.sleep(1500L * attempt);
				continue;
			}
			JsonNode root = json.readTree(resp.body());
			JsonNode error = root.path("quoteSummary").path("error");
			if (!error.isNull()) {
				String code = error.path("code").asText("");
				String desc = error.path("description").asText("");
				if (code.equals("Unauthorized") || desc.contains("Invalid Crumb")) {
					log.warn("[FUND] Invalid crumb for {} — refreshing session (attempt {}/{})", ticker, attempt,
							MAX_RETRIES);
					session.forceRefresh();
					ThreadUtil.sleep(2000L * attempt);
					continue;
				}
				if (code.equals("Not Found") || desc.contains("No fundamentals")) {
					log.warn("[FUND] {} has no fundamental data: {}", ticker, desc);
					return null;
				}
				log.warn("[FUND] {} API error: {} — {}", ticker, code, desc);
				return null;
			}
			log.debug("[FUND] {} fetched OK (attempt {})", ticker, attempt);
			return root;
		}
		log.error("[FUND] {} — all {} attempts failed", ticker, MAX_RETRIES);
		return null;
	}

	private String fmtPct(double v) {
		if (v == 0)
			return "N/A";
		return String.format("%+.2f%%", v * 100);
	}

	private String fmt(double v) {
		if (v == 0)
			return "N/A";
		if (Math.abs(v) >= 1_000_000_000)
			return String.format("%.2fB", v / 1_000_000_000.0);
		if (Math.abs(v) >= 1_000_000)
			return String.format("%.2fM", v / 1_000_000.0);
		if (Math.abs(v) >= 1_000)
			return String.format("%.1fK", v / 1_000.0);
		return String.format("%.2f", v);
	}

	private String f2(double v) {
		return v == 0 ? "N/A" : String.format("%.2f", v);
	}

	private String f4(double v) {
		return v == 0 ? "N/A" : String.format("%.4f", v);
	}

	public record FundamentalResult(String code, String ticker, String verdict, // BUY_SUPPORTED | WATCH | SKIP
			String verdictDetail, String tradingAdvice, double score, long criticalRedFlags, List<String> greenFlags,
			List<String> redFlags, List<String> neutralNotes, Map<String, Object> metrics) {
		static FundamentalResult error(String code, String msg) {
			return new FundamentalResult(code, code, "ERROR", msg, "Cannot analyze — data unavailable.", 0, 0,
					List.of(), List.of(), List.of(), Map.of());
		}
	}
}