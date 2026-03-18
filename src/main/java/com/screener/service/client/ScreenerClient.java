package com.screener.service.client;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import com.screener.service.dto.MaCalculationRequest;
import com.screener.service.dto.TrendDistanceRequest;

@HttpExchange("api")
public interface ScreenerClient {
	@PostExchange("{market}/scan/{code}")
	Map<String, Object> scanSingle(@PathVariable String market, @PathVariable String code);

	@PostExchange("{market}/scan/batch")
	Map<String, Object> scanBatch(@PathVariable String market, @RequestBody Map<String, List<String>> body);

	@GetExchange("{market}/scan/range")
	Map<String, Object> scanRange(@PathVariable String market, @RequestParam double minPrice,
			@RequestParam double maxPrice, @RequestParam double minScore, @RequestParam String exchange);

	@GetExchange("{market}/watchlist/tomorrow")
	Map<String, Object> watchlistTomorrow(@PathVariable String market, @RequestParam double minScore,
			@RequestParam String decision, @RequestParam(required = false) Double minPrice,
			@RequestParam(required = false) Double maxPrice);

	@GetExchange("{market}/result/today")
	Map<String, Object> resultToday(@PathVariable String market);

	@GetExchange("{market}/result/{code}")
	Object resultStock(@PathVariable String market, @PathVariable String code);

	@PostExchange("{market}/ma/calculation")
	Map<String, Object> maCalculation(@PathVariable String market, @RequestBody MaCalculationRequest request);

	@GetExchange("{market}/ema/calculation/{mp}/{e9}/{e21}/{e50}/{e200}")
	Map<String, Object> emaCalculation(@PathVariable String market, @PathVariable double mp, @PathVariable double e9,
			@PathVariable double e21, @PathVariable double e50, @PathVariable double e200,
			@RequestParam String timeframe);

	@PostExchange("{market}/ma/trend-distance")
	Map<String, Object> trendDistance(@PathVariable String market, @RequestBody TrendDistanceRequest request);

	@GetExchange("{market}/pullback")
	Map<String, Object> pullback(@PathVariable String market, @RequestParam String code, @RequestParam String interval,
			@RequestParam int limit);

	@PostExchange(value = "{market}/level2/analyze-image", contentType = MediaType.MULTIPART_FORM_DATA_VALUE)
	Map<String, Object> level2Image(@PathVariable String market, @RequestPart("image") MultipartFile image);
}