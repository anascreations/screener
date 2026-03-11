package com.screener.service.model;

public record StockCandidate(String code, String name, double price, long volume, String exchange, double marketCap) {
	public StockCandidate(String code, String name, double price, long volume, String exchange) {
		this(code, name, price, volume, exchange, 0);
	}
}