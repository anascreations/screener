package com.screener.service.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import com.screener.service.client.ScreenerClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
@RequestMapping("ui")
@RequiredArgsConstructor
public class UiController {
	private final ScreenerClient screenerClient;

	@GetMapping
	public String dashboard() {
		return "index";
	}

	@PostMapping("scan/single")
	public String scanSingle(@RequestParam String market, @RequestParam String code, Model model) {
		model.addAttribute("activeTab", "scan-single");
		model.addAttribute("result", screenerClient.scanSingle(market.toLowerCase(), code.trim()));
		model.addAttribute("market", market);
		model.addAttribute("code", code);
		return "index";
	}

	@PostMapping("scan/batch")
	public String scanBatch(@RequestParam String market, @RequestParam String codesRaw, Model model) {
		List<String> codes = List.of(codesRaw.split("[,\\s]+")).stream().map(String::trim).filter(s -> !s.isBlank())
				.toList();
		model.addAttribute("activeTab", "scan-batch");
		model.addAttribute("result", screenerClient.scanBatch(market.toLowerCase(), Map.of("codes", codes)));
		model.addAttribute("market", market);
		model.addAttribute("codesRaw", codesRaw);
		return "index";
	}

	@PostMapping("scan/range")
	public String scanRange(@RequestParam String market, @RequestParam double minPrice, @RequestParam double maxPrice,
			@RequestParam(defaultValue = "70") double minScore, @RequestParam(defaultValue = "ALL") String exchange,
			Model model) {
		model.addAttribute("activeTab", "scan-range");
		model.addAttribute("result",
				screenerClient.scanRange(market.toLowerCase(), minPrice, maxPrice, minScore, exchange));
		model.addAttribute("market", market);
		model.addAttribute("minPrice", minPrice);
		model.addAttribute("maxPrice", maxPrice);
		model.addAttribute("minScore", minScore);
		model.addAttribute("exchange", exchange);
		return "index";
	}

	@PostMapping("watchlist/tomorrow")
	public String watchlistTomorrow(@RequestParam String market, @RequestParam(required = false) Double minPrice,
			@RequestParam(required = false) Double maxPrice, @RequestParam(defaultValue = "65") double minScore,
			@RequestParam(defaultValue = "BUY") String decision, Model model) {
		model.addAttribute("activeTab", "watchlist");
		model.addAttribute("result",
				screenerClient.watchlistTomorrow(market.toLowerCase(), minScore, decision, minPrice, maxPrice));
		model.addAttribute("market", market);
		model.addAttribute("minPrice", minPrice);
		model.addAttribute("maxPrice", maxPrice);
		model.addAttribute("minScore", minScore);
		model.addAttribute("decision", decision);
		return "index";
	}

	@PostMapping("result/today")
	public String resultToday(@RequestParam String market, Model model) {
		model.addAttribute("activeTab", "result-today");
		model.addAttribute("result", screenerClient.resultToday(market.toLowerCase()));
		model.addAttribute("market", market);
		return "index";
	}

	@SuppressWarnings("unchecked")
	@PostMapping("result/stock")
	public String resultStock(@RequestParam String market, @RequestParam String code, Model model) {
		Object raw = screenerClient.resultStock(market.toLowerCase(), code.trim());
		Map<String, Object> result = raw instanceof List<?> list
				? Map.of("history", list, "code", code.toUpperCase(), "count", list.size())
				: raw instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("error", "Unexpected response");
		model.addAttribute("activeTab", "result-stock");
		model.addAttribute("result", result);
		model.addAttribute("market", market);
		model.addAttribute("code", code);
		return "index";
	}

	@PostMapping("ma/calculation")
	public String maCalculation(@RequestParam String market, @RequestParam double marketPrice, @RequestParam double ma5,
			@RequestParam double ma20, @RequestParam(defaultValue = "DAILY") String timeframe, Model model) {
		model.addAttribute("activeTab", "ma-calc");
		model.addAttribute("result",
				screenerClient.maCalculation(market.toLowerCase(), marketPrice, ma5, ma20, timeframe));
		model.addAttribute("market", market);
		model.addAttribute("marketPrice", marketPrice);
		model.addAttribute("ma5", ma5);
		model.addAttribute("ma20", ma20);
		model.addAttribute("timeframe", timeframe);
		return "index";
	}

	@PostMapping("ema/calculation")
	public String emaCalculation(@RequestParam String market, @RequestParam double marketPrice,
			@RequestParam double ema9, @RequestParam double ema21, @RequestParam double ema50,
			@RequestParam double ema200, @RequestParam(defaultValue = "DAILY") String timeframe, Model model) {
		model.addAttribute("activeTab", "ema-calc");
		model.addAttribute("result", screenerClient.emaCalculation(market.toLowerCase(), marketPrice, ema9, ema21,
				ema50, ema200, timeframe));
		model.addAttribute("market", market);
		model.addAttribute("marketPrice", marketPrice);
		model.addAttribute("ema9", ema9);
		model.addAttribute("ema21", ema21);
		model.addAttribute("ema50", ema50);
		model.addAttribute("ema200", ema200);
		model.addAttribute("timeframe", timeframe);
		return "index";
	}

	@PostMapping("pullback")
	public String pullback(@RequestParam String market, @RequestParam String code,
			@RequestParam(defaultValue = "1d") String interval, @RequestParam(defaultValue = "20") int limit,
			Model model) {
		model.addAttribute("activeTab", "pullback");
		model.addAttribute("result", screenerClient.pullback(market.toLowerCase(), code.trim(), interval, limit));
		model.addAttribute("market", market);
		model.addAttribute("code", code);
		model.addAttribute("interval", interval);
		model.addAttribute("limit", limit);
		return "index";
	}

	@PostMapping("level2/image")
	public String level2Image(@RequestParam String market, @RequestParam("image") MultipartFile file, Model model)
			throws IOException {
		model.addAttribute("activeTab", "level2");
		model.addAttribute("result", screenerClient.level2Image(market.toLowerCase(), file));
		model.addAttribute("market", market);
		return "index";
	}
}