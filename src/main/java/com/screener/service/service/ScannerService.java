package com.screener.service.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.screener.service.enums.Market;
import com.screener.service.model.ScanResult;
import com.screener.service.model.StockCandidate;
import com.screener.service.util.ThreadUtil;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ScannerService {
	private final Map<Market, ExchangeService> exchangeMap;
	private final AnalysisService analysisService;
	private final TradingCalendarService calendarService;
	private static final int TIMEOUT_MIN = 60;
	@Value("${screener.scan-parallelism:4}")
	private int scanParallelism;
	@Value("${screener.delay-between-stocks-ms:800}")
	private long delayBetweenMs;

	public ScannerService(ExchangeMyService myExchange, ExchangeUsService usExchange, AnalysisService analysisService,
			TradingCalendarService calendarService) {
		this.exchangeMap = Map.of(Market.MY, myExchange, Market.US, usExchange);
		this.analysisService = analysisService;
		this.calendarService = calendarService;
	}

	public List<ScanResult> scanByPriceRange(Market market, double minPrice, double maxPrice, double minScore,
			String exchange) {
		LocalDateTime start = LocalDateTime.now(market.zoneId);
		logHeader(market, minPrice, maxPrice, minScore, exchange, start);
		List<StockCandidate> candidates = exchangeMap.get(market).fetchCandidates(minPrice, maxPrice, exchange);
		if (candidates.isEmpty()) {
			log.warn("[{}] No candidates for {}-{}. Run after market close or check screener session.", market,
					market.formatPrice(minPrice), market.formatPrice(maxPrice));
			return Collections.emptyList();
		}
		log.info("[{}] {} candidates → analysis ({} slots, {}ms gap per slot)", market, candidates.size(),
				scanParallelism, delayBetweenMs);
		Semaphore throttle = new Semaphore(scanParallelism);
		ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
		AtomicInteger done = new AtomicInteger(0);
		AtomicInteger buyCount = new AtomicInteger(0);
		AtomicInteger watchCount = new AtomicInteger(0);
		AtomicInteger ignCount = new AtomicInteger(0);
		AtomicInteger noDataCount = new AtomicInteger(0);
		AtomicInteger failCount = new AtomicInteger(0);
		AtomicInteger noMacd = new AtomicInteger(0);
		int total = candidates.size();
		List<Future<Optional<ScanResult>>> futures = new ArrayList<>();
		for (StockCandidate c : candidates) {
			futures.add(pool.submit(() -> {
				try {
					throttle.acquire();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return Optional.<ScanResult>empty();
				}
				try {
					Optional<ScanResult> result = analysisService.scan(market, c.code());
					if (result.isEmpty()) {
						noDataCount.incrementAndGet();
					} else {
						switch (result.get().getDecision()) {
						case "BUY" -> buyCount.incrementAndGet();
						case "WATCH" -> {
							watchCount.incrementAndGet();
							String w = result.get().getWarnings();
							if (w != null && w.contains("NO MACD CROSS"))
								noMacd.incrementAndGet();
						}
						default -> ignCount.incrementAndGet();
						}
					}
					return result;
				} catch (Exception e) {
					log.warn("[{}] Scan exception: {}", c.code(), e.getMessage());
					failCount.incrementAndGet();
					return Optional.<ScanResult>empty();
				} finally {
					ThreadUtil.sleep(delayBetweenMs);
					throttle.release();
					int n = done.incrementAndGet();
					if (n % 20 == 0 || n == total)
						log.info("[{}] ⏳ {}/{} | BUY:{} WATCH:{} IGN:{} NO_MACD:{} NO_DATA:{} FAIL:{}", market, n,
								total, buyCount.get(), watchCount.get(), ignCount.get(), noMacd.get(),
								noDataCount.get(), failCount.get());
				}
			}));
		}
		pool.shutdown();
		try {
			pool.awaitTermination(TIMEOUT_MIN, TimeUnit.MINUTES);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		List<ScanResult> all = futures.stream().map(f -> {
			try {
				return f.get();
			} catch (Exception e) {
				return Optional.<ScanResult>empty();
			}
		}).filter(Optional::isPresent).map(Optional::get)
				.sorted(Comparator.comparingDouble(ScanResult::getScore).reversed()).collect(Collectors.toList());
		logSummary(market, all, minPrice, maxPrice, minScore, noDataCount.get(), failCount.get(), start);
		return all.stream().filter(r -> "BUY".equals(r.getDecision()) && r.getScore() >= minScore)
				.collect(Collectors.toList());
	}

	private void logHeader(Market market, double min, double max, double minScore, String exch, LocalDateTime t) {
		log.info("████████████████████████████████████████████████████████");
		log.info("  {} SCAN | {} — {} | {} | minScore:{}", market, market.formatPrice(min), market.formatPrice(max),
				exch, (int) minScore);
		log.info("  Throttle : {} concurrent slots, {}ms gap per slot", scanParallelism, delayBetweenMs);
		log.info("  Require  : MACD Golden Cross (histogram crosses zero today or confirmed yesterday+accelerating)");
		log.info("  Started  : {}", t);
		log.info("████████████████████████████████████████████████████████");
	}

	private void logSummary(Market market, List<ScanResult> results, double min, double max, double minScore,
			int noData, int fail, LocalDateTime start) {
		long elapsed = java.time.Duration.between(start, LocalDateTime.now(market.zoneId)).getSeconds();
		List<ScanResult> buys = results.stream().filter(r -> "BUY".equals(r.getDecision()) && r.getScore() >= minScore)
				.toList();
		List<ScanResult> watches = results.stream().filter(r -> "WATCH".equals(r.getDecision())).toList();
		LocalDate tradeDate = nextTradingDayFrom(market, LocalDate.now(market.zoneId));
		log.info("████████████████████████████████████████████████████████");
		log.info("  {} DONE in {}s | Analyzed:{} NO_DATA:{} FAIL:{}", market, elapsed, results.size(), noData, fail);
		int totalAttempted = results.size() + noData + fail;
		if (noData > totalAttempted * 0.25) {
			log.warn("  ⚠  HIGH NO_DATA RATE ({}/{}) — likely causes:", noData, totalAttempted);
			log.warn("       · Yahoo rate limiting  →  reduce scan-parallelism to 2, increase delay to 1200ms");
			log.warn("       · Stocks with < 52 bars (newly listed / thin trading)");
			log.warn("       · Running during market hours — wait for confirmed EOD data");
			log.warn("       Current settings: parallelism={} delay={}ms", scanParallelism, delayBetweenMs);
		}
		log.info("████████████████████████████████████████████████████████");
		if (buys.isEmpty()) {
			log.info("  No BUY signals — MACD golden cross is a rare event, this is normal.");
			log.info("  WATCH list below = stocks that passed score/volume but need a fresh cross.");
		} else {
			log.info("  ✅ {} BUY SIGNAL(S) — Trade on {}", buys.size(), tradeDate);
			for (int i = 0; i < buys.size(); i++) {
				ScanResult r = buys.get(i);
				log.info("  #{} {} ({}) | Score:{} | {} | Vol:{}x | SL:{} TP1:{} RR:1:{}", i + 1,
						market.fullTicker(r.getStockCode()), r.getStockName(), String.format("%.1f", r.getScore()),
						market.formatPrice(r.getClosePrice()), String.format("%.2f", r.getVolumeRatio()),
						market.formatPrice(r.getStopLoss()), market.formatPrice(r.getTargetTP1()), r.getRiskReward());
			}
		}
		if (!watches.isEmpty()) {
			log.info("  👀 WATCH ({}) — score passed, no MACD cross yet — monitor tomorrow:", watches.size());
			watches.forEach(
					r -> log.info("      {} | Score:{} | {} | Vol:{}x | {}", market.fullTicker(r.getStockCode()),
							String.format("%.1f", r.getScore()), market.formatPrice(r.getClosePrice()),
							String.format("%.2f", r.getVolumeRatio()), r.getWarnings() != null ? r.getWarnings() : ""));
		}
		log.info("");
	}

	private LocalDate nextTradingDayFrom(Market market, LocalDate from) {
		if (market == Market.MY)
			return calendarService.nextTradingDay(from);
		LocalDate d = from.plusDays(1);
		while (d.getDayOfWeek().getValue() >= 6)
			d = d.plusDays(1);
		return d;
	}

}