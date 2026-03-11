package com.screener.service.service;

import java.util.List;

import com.screener.service.enums.Market;
import com.screener.service.model.DailyBar;
import com.screener.service.model.StockCandidate;

public interface ExchangeService {
	Market getMarket();

	List<DailyBar> fetchHistory(String code);

	List<StockCandidate> fetchCandidates(double minPrice, double maxPrice, String exchange);
}