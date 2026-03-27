package com.screener.service.controller;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.screener.service.client.ScreenerClient;
import com.screener.service.dto.MaCalculationRequest;
import com.screener.service.dto.TrendDistanceRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
@RequestMapping("ui")
@RequiredArgsConstructor
public class UiController {
	private static final String INDEX = "index";
	private final ScreenerClient screenerClient;
	private final ObjectMapper objectMapper;

	@GetMapping
	public String dashboard() {
		return INDEX;
	}

	@PostMapping("scan/single")
	public String scanSingle(@RequestParam String market, @RequestParam String code, Model model) {
		model.addAttribute("activeTab", "scan-single");
		model.addAttribute("market", market);
		model.addAttribute("code", code);
		try {
			model.addAttribute("result", screenerClient.scanSingle(market.toLowerCase(), code.trim()));
		} catch (WebClientResponseException e) {
			model.addAttribute("result", parseErrorBody(e));
		}
		return INDEX;
	}

	@PostMapping("scan/batch")
	public String scanBatch(@RequestParam String market, @RequestParam String codesRaw, Model model) {
		List<String> codes = Arrays.stream(codesRaw.split("[,\\s]+")).map(String::trim).filter(s -> !s.isBlank())
				.toList();
		model.addAttribute("activeTab", "scan-batch");
		model.addAttribute("market", market);
		model.addAttribute("codesRaw", codesRaw);
		try {
			model.addAttribute("result", screenerClient.scanBatch(market.toLowerCase(), Map.of("codes", codes)));
		} catch (WebClientResponseException e) {
			model.addAttribute("result", parseErrorBody(e));
		}
		return INDEX;
	}

	@PostMapping("scan/range")
	public String scanRange(@RequestParam String market, @RequestParam double minPrice, @RequestParam double maxPrice,
			@RequestParam(defaultValue = "70") double minScore, @RequestParam(defaultValue = "ALL") String exchange,
			Model model) {
		model.addAttribute("activeTab", "scan-range");
		model.addAttribute("market", market);
		model.addAttribute("minPrice", minPrice);
		model.addAttribute("maxPrice", maxPrice);
		model.addAttribute("minScore", minScore);
		model.addAttribute("exchange", exchange);
		try {
			model.addAttribute("result",
					screenerClient.scanRange(market.toLowerCase(), minPrice, maxPrice, minScore, exchange));
		} catch (WebClientResponseException e) {
			model.addAttribute("result", parseErrorBody(e));
		}
		return INDEX;
	}

	@PostMapping("watchlist/tomorrow")
	public String watchlistTomorrow(@RequestParam String market, @RequestParam(required = false) Double minPrice,
			@RequestParam(required = false) Double maxPrice, @RequestParam(defaultValue = "65") double minScore,
			@RequestParam(defaultValue = "BUY") String decision, Model model) {
		model.addAttribute("activeTab", "watchlist");
		model.addAttribute("market", market);
		model.addAttribute("minPrice", minPrice);
		model.addAttribute("maxPrice", maxPrice);
		model.addAttribute("minScore", minScore);
		model.addAttribute("decision", decision);
		try {
			model.addAttribute("result",
					screenerClient.watchlistTomorrow(market.toLowerCase(), minScore, decision, minPrice, maxPrice));
		} catch (WebClientResponseException e) {
			model.addAttribute("result", parseErrorBody(e));
		}
		return INDEX;
	}

	@PostMapping("result/today")
	public String resultToday(@RequestParam String market, Model model) {
		model.addAttribute("activeTab", "result-today");
		model.addAttribute("market", market);
		try {
			model.addAttribute("result", screenerClient.resultToday(market.toLowerCase()));
		} catch (WebClientResponseException e) {
			model.addAttribute("result", parseErrorBody(e));
		}
		return INDEX;
	}

	@SuppressWarnings("unchecked")
	@PostMapping("result/stock")
	public String resultStock(@RequestParam String market, @RequestParam String code, Model model) {
		model.addAttribute("activeTab", "result-stock");
		model.addAttribute("market", market);
		model.addAttribute("code", code);
		try {
			Object raw = screenerClient.resultStock(market.toLowerCase(), code.trim());
			Map<String, Object> result = raw instanceof List<?> list
					? Map.of("history", list, "code", code.toUpperCase(), "count", list.size())
					: raw instanceof Map<?, ?> m ? (Map<String, Object>) m
							: Map.of("error", "Unexpected response format");
			model.addAttribute("result", result);
		} catch (WebClientResponseException e) {
			model.addAttribute("result", parseErrorBody(e));
		}
		return INDEX;
	}

//	@PostMapping("ma/calculation")
//	public String maCalculation(@ModelAttribute @Valid MaCalculationRequest request, BindingResult binding,
//			Model model) {
//		model.addAttribute("activeTab", "ma-calc");
//		model.addAttribute("market", request.getMarket());
//		model.addAttribute("timeframe", request.getTimeframe());
//		model.addAttribute("marketPrice", request.getMarketPrice());
//		model.addAttribute("ma5", request.getMa5());
//		model.addAttribute("ma20", request.getMa20());
//		model.addAttribute("ma50", request.getMa50());
//		model.addAttribute("ma200", request.getMa200());
//		model.addAttribute("rsi14", request.getRsi14());
//		model.addAttribute("atr14", request.getAtr14());
//		model.addAttribute("volumeRatio", request.getVolumeRatio());
//		if (!binding.hasErrors()) {
//			try {
//				model.addAttribute("result", screenerClient.maCalculation(request.getMarket(), request));
//			} catch (WebClientResponseException e) {
//				model.addAttribute("result", parseErrorBody(e));
//			}
//		}
//		return INDEX;
//	}
	// ─────────────────────────────────────────────────────────────────────────────
	// REPLACE the existing maCalculation() method in UiController.java with this:
	// ─────────────────────────────────────────────────────────────────────────────
	@PostMapping("ma/calculation")
	public String maCalculation(@ModelAttribute @Valid MaCalculationRequest request, BindingResult binding,
			Model model) {
		model.addAttribute("activeTab", "ma-calc");
		model.addAttribute("market", request.getMarket());
		model.addAttribute("timeframe", request.getTimeframe());
		model.addAttribute("marketPrice", request.getMarketPrice());
		model.addAttribute("ma5", request.getMa5());
		model.addAttribute("ma20", request.getMa20());
		model.addAttribute("ma50", request.getMa50());
		// MA200 removed — replaced by KDJ + MACD
		model.addAttribute("rsi14", request.getRsi14());
		model.addAttribute("atr14", request.getAtr14());
		model.addAttribute("volumeRatio", request.getVolumeRatio());
		// KDJ
		model.addAttribute("kdjK", request.getKdjK());
		model.addAttribute("kdjD", request.getKdjD());
		model.addAttribute("kdjJ", request.getKdjJ());
		if (!binding.hasErrors()) {
			try {
				model.addAttribute("result", screenerClient.maCalculation(request.getMarket(), request));
			} catch (WebClientResponseException e) {
				model.addAttribute("result", parseErrorBody(e));
			}
		}
		return INDEX;
	}

	@PostMapping("ema/calculation")
	public String emaCalculation(@RequestParam String market, @RequestParam double marketPrice,
			@RequestParam double ema9, @RequestParam double ema21, @RequestParam double ema50,
			@RequestParam double ema200, @RequestParam(defaultValue = "DAILY") String timeframe, Model model) {
		model.addAttribute("activeTab", "ema-calc");
		model.addAttribute("market", market);
		model.addAttribute("timeframe", timeframe);
		model.addAttribute("marketPrice", marketPrice);
		model.addAttribute("ema9", ema9);
		model.addAttribute("ema21", ema21);
		model.addAttribute("ema50", ema50);
		model.addAttribute("ema200", ema200);
		try {
			model.addAttribute("result", screenerClient.emaCalculation(market.toLowerCase(), marketPrice, ema9, ema21,
					ema50, ema200, timeframe));
		} catch (WebClientResponseException e) {
			model.addAttribute("result", parseErrorBody(e));
		}
		return INDEX;
	}

	@PostMapping("ma/trend-distance")
	public String trendDistance(@ModelAttribute @Valid TrendDistanceRequest request, BindingResult binding,
			Model model) {
		model.addAttribute("activeTab", "ma-trend-distance");
		model.addAttribute("market", request.getMarket());
		model.addAttribute("timeframe", request.getTimeframe());
		model.addAttribute("ticker", request.getTicker());
		model.addAttribute("marketPrice", request.getMarketPrice());
		model.addAttribute("ma20", request.getMa20());
		model.addAttribute("ma50", request.getMa50());
		model.addAttribute("rsi14", request.getRsi14());
		model.addAttribute("volumeRatio", request.getVolumeRatio());
		if (!binding.hasErrors()) {
			try {
				model.addAttribute("result", screenerClient.trendDistance(request.getMarket(), request));
			} catch (WebClientResponseException e) {
				model.addAttribute("result", parseErrorBody(e));
			}
		}
		return INDEX;
	}

	@PostMapping("pullback")
	public String pullback(@RequestParam String market, @RequestParam String code,
			@RequestParam(defaultValue = "1d") String interval, @RequestParam(defaultValue = "20") int limit,
			Model model) {
		model.addAttribute("activeTab", "pullback");
		model.addAttribute("market", market);
		model.addAttribute("code", code);
		model.addAttribute("interval", interval);
		model.addAttribute("limit", limit);
		try {
			model.addAttribute("result", screenerClient.pullback(market.toLowerCase(), code.trim(), interval, limit));
		} catch (WebClientResponseException e) {
			model.addAttribute("result", parseErrorBody(e));
		}
		return INDEX;
	}

	@PostMapping("level2/image")
	public String level2Image(@RequestParam String market, @RequestParam("image") MultipartFile file, Model model)
			throws IOException {
		model.addAttribute("activeTab", "level2");
		model.addAttribute("market", market);
		try {
			model.addAttribute("result", screenerClient.level2Image(market.toLowerCase(), file));
		} catch (WebClientResponseException e) {
			model.addAttribute("result", parseErrorBody(e));
		}
		return INDEX;
	}

	private Map<String, Object> parseErrorBody(WebClientResponseException e) {
		log.warn("[UI] Upstream {} — {}", e.getStatusCode().value(), e.getResponseBodyAsString());
		try {
			return objectMapper.readValue(e.getResponseBodyAsString(), new TypeReference<Map<String, Object>>() {
			});
		} catch (Exception ex) {
			return Map.of("error", e.getStatusCode().value() + " " + e.getStatusText(), "reason",
					e.getResponseBodyAsString());
		}
	}
}